<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Activity, ArrowRight, Bot, Check, FileText, GitBranch, History, Layers3, Play, RefreshCw, ShieldCheck, TriangleAlert } from '@lucide/vue';
import { automationApi, type Model, type Definition, type PendingApproval } from '@/api/automation';
import { useEventWorkspace } from '@/composables/useEventWorkspace';
import { eventRetrospective, eventRunLabels, currentlyVerified, recoveryLabel, observationFresh, definitelyRejected } from '@/utils/event-workspace';
import { useAuthStore } from '@/stores/auth';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import { request } from '@/api/http';
import ApprovalCard from '@/components/automation/ApprovalCard.vue';
import BaseModal from '@/components/BaseModal.vue';
import FormField from '@/components/FormField.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import type { Ticket } from '@/types/api';
import '@/styles/pages/event-workspace.css';

const props = defineProps<{ ticket: Ticket }>();
const emit = defineEmits<{ refresh: []; question: []; records: []; documents: [] }>();
const auth = useAuthStore();
const inbox = useApprovalInboxStore();
const identity = () => auth.user ? `${auth.user.userId}:${auth.user.roles.join(',')}` : '';
const workspace = useEventWorkspace(() => props.ticket.id, identity);
const { data, loading, error } = workspace;
const busy = ref('');
const actionError = ref('');
const notice = ref('');
const now = ref(Date.now());
const verified = computed(() => currentlyVerified(data.value, now.value));
const staleObservation = computed(() => data.value?.verification.scope === 'CURRENT' && !!data.value.verification.observedAt && !observationFresh(data.value, now.value));
const verificationTitle = computed(() => staleObservation.value ? '当前观测已过期，等待重新核对' : data.value?.verification.label);
const stageTitle = computed(() => staleObservation.value && data.value?.stage.code === 'RECOVERED' ? '重新核对业务状态' : data.value?.stage.label);
const approvals = computed(() => inbox.availableItems.filter(item => item.ticketId === props.ticket.id
  && data.value?.pendingApprovalIds.includes(item.id)));
const steps = [
  { label: '异常与事件', codes: ['UNBOUND', 'OBSERVING'] },
  { label: '证据与诊断', codes: ['DIAGNOSING', 'NEEDS_ATTENTION'] },
  { label: '审批与执行', codes: ['WAITING_APPROVAL', 'EXECUTING'] },
  { label: '业务验证', codes: ['VERIFYING', 'RECOVERED'] },
];
const models = ref<Model[]>([]);
const definitions = ref<Definition[]>([]);
const model = ref('');
const definition = ref('');
const createOpen = ref(false);
let createAttempt: { ticketId: number; provider: string; definitionId: string; requestId: string } | undefined;
const createUnconfirmed = ref(false);
const draft = ref('');
const draftOpen = ref(false);
const savedDocument = ref<number>();
const draftUnconfirmed = ref(false);
const canSaveKnowledge = computed(() => !auth.isDemo && (auth.isAdmin || props.ticket.creatorId === auth.user?.userId || props.ticket.assigneeId === auth.user?.userId));
let timer: ReturnType<typeof setInterval> | undefined;
let epoch = 0;
let disposed = false;
function date(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '未提供时间'; }
function scopeLabel(value: string) { return ({ CURRENT: '当前观测', HISTORICAL: '历史记录', TICKET: '事件资料', RUN: '执行证据' } as Record<string, string>)[value] || '待核对'; }
function changeRows(change: { before?: Record<string, unknown>; after?: Record<string, unknown> }) {
  const labels: Record<string, string> = { sentinelQps: 'Sentinel 每秒阈值', sentinelResource: '限流资源', consumerEnabled: '消费者启用', revision: '配置版本', catalogTitle: '目录标题', notice: '业务提示', discountPercent: '折扣比例', queue: '通知队列', vhost: '虚拟主机', redisHost: 'Redis 地址', redisPort: 'Redis 端口', rateLimit: '限流阈值', consumerPaused: '消费者暂停', configRevision: '配置版本', redisMode: 'Redis 连接模式', sentinelLimit: 'Sentinel 限流阈值' };
  const value = (input: unknown) => input == null ? '未记录' : typeof input === 'boolean' ? (input ? '是' : '否') : typeof input === 'object' ? JSON.stringify(input) : String(input);
  return [...new Set([...Object.keys(change.before || {}), ...Object.keys(change.after || {})])]
    .filter(key => JSON.stringify(change.before?.[key]) !== JSON.stringify(change.after?.[key]))
    .map(key => ({ key, label: labels[key] || key, before: value(change.before?.[key]), after: value(change.after?.[key]) }));
}
function status(value: string) { return eventRunLabels[value] || value; }
async function refresh() { await Promise.allSettled([workspace.load(), inbox.refresh(false)]); }
async function decide(approval: PendingApproval, value: { approved: boolean; reason: string }) {
  if (busy.value || !inbox.isCurrent(approval)) return;
  const stamp = epoch;
  busy.value = approval.id; actionError.value = '';
  try { await inbox.decide(approval, value.approved, value.reason); if (stamp === epoch) notice.value = inbox.notice; }
  catch (cause) { if (stamp === epoch) actionError.value = cause instanceof Error ? cause.message : '审批提交失败'; }
  finally { if (stamp === epoch) { busy.value = ''; await refresh(); } }
}
async function prepareRun() {
  if (busy.value || !data.value?.actions.canDiagnose) return;
  const stamp = epoch;
  createOpen.value = true; busy.value = 'models'; actionError.value = '';
  try {
    const [available, workflows] = await Promise.all([automationApi.models(), automationApi.definitions()]);
    if (disposed || stamp !== epoch) return;
    models.value = available.models.filter(item => item.configured && item.toolCalling);
    definitions.value = workflows.filter(item => item.published_version > 0 && (!auth.isDemo || item.id === 'isolated-recovery'));
    model.value = createAttempt?.provider || models.value.find(item => item.provider.toUpperCase() === 'DEEPSEEK')?.provider || models.value[0]?.provider || '';
    definition.value = createAttempt?.definitionId || data.value?.actions.definitionId || definitions.value[0]?.id || '';
  } catch (cause) { if (stamp === epoch) actionError.value = cause instanceof Error ? cause.message : '无法读取可用模型和流程'; }
  finally { if (stamp === epoch) busy.value = ''; }
}
async function createRun() {
  if (busy.value || !data.value?.actions.canDiagnose || !models.value.some(m => m.provider === model.value)
      || !definitions.value.some(d => d.id === definition.value)) return;
  const stamp = epoch;
  busy.value = 'create'; actionError.value = '';
  if (!createUnconfirmed.value) createAttempt = { ticketId: props.ticket.id, provider: model.value, definitionId: definition.value, requestId: crypto.randomUUID() };
  const attempt = createAttempt!;
  createUnconfirmed.value = true;
  try {
    await automationApi.create(attempt.ticketId, attempt.definitionId, attempt.provider, attempt.requestId);
    if (disposed || stamp !== epoch) return;
    createAttempt = undefined; createUnconfirmed.value = false; createOpen.value = false;
    notice.value = '诊断已启动，本事件的证据、审批和验证结果将在下方更新。';
    await refresh();
  } catch (cause) {
    if (stamp === epoch) {
      if (definitelyRejected(cause)) { createAttempt = undefined; createUnconfirmed.value = false; }
      actionError.value = `${cause instanceof Error ? cause.message : '启动结果未确认'}${createUnconfirmed.value ? '。重试将复用同一请求，请先核对下方运行记录。' : '。请核对并调整选项后重新提交。'}`;
    }
  }
  finally { if (stamp === epoch) busy.value = ''; }
}
function prepareDraft() {
  if (!data.value) return;
  if (draftUnconfirmed.value || savedDocument.value) { draftOpen.value = true; return; }
  draft.value = eventRetrospective(data.value, props.ticket.title); savedDocument.value = undefined;
  draftOpen.value = true; actionError.value = '';
}
async function saveDraft() {
  if (!canSaveKnowledge.value || busy.value || draftUnconfirmed.value || savedDocument.value || !draft.value.trim()) return;
  const stamp = epoch;
  busy.value = 'draft'; actionError.value = '';
  try {
    const form = new FormData();
    form.append('file', new File([draft.value], `event-${props.ticket.id}-retrospective.md`, { type: 'text/markdown' }));
    form.append('ticketId', String(props.ticket.id)); form.append('visibility', 'PRIVATE');
    const id = await request<number>({ method: 'POST', url: '/api/knowledge/bases/1/documents', data: form });
    if (disposed || stamp !== epoch) return;
    savedDocument.value = id;
    notice.value = '复盘草稿已保存为私有关联文档。请在文档页解析、核对内容后提交知识审核。';
    emit('refresh');
  } catch (cause) { if (stamp === epoch) {
    draftUnconfirmed.value = !definitelyRejected(cause);
    actionError.value = cause instanceof Error ? cause.message : '复盘草稿保存失败';
  } }
  finally { if (stamp === epoch) busy.value = ''; }
}
watch(() => data.value?.runs.map(run => `${run.id}:${run.status}`).join('|'), (value, previous) => {
  if (previous != null && value && value !== previous) emit('refresh');
});
watch([() => props.ticket.id, identity], () => {
  epoch++; busy.value = ''; actionError.value = ''; notice.value = ''; createOpen.value = false; draftOpen.value = false;
  createAttempt = undefined; createUnconfirmed.value = false; models.value = []; definitions.value = []; draft.value = ''; savedDocument.value = undefined; draftUnconfirmed.value = false;
}, { flush: 'sync' });
watch(() => inbox.decisionVersion, () => void workspace.load());
watch(() => props.ticket.version, () => void workspace.load());
function visibilityChanged() { now.value = Date.now(); if (!document.hidden) void refresh(); }
let clockTimer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  void refresh();
  timer = setInterval(() => { if (!document.hidden && !busy.value) void workspace.load(); }, 8000);
  clockTimer = setInterval(() => { now.value = Date.now(); }, 1000);
  document.addEventListener('visibilitychange', visibilityChanged);
});
onBeforeUnmount(() => { disposed = true; epoch++; if (timer) clearInterval(timer); if (clockTimer) clearInterval(clockTimer); document.removeEventListener('visibilitychange', visibilityChanged); workspace.dispose(); });
</script>

<template>
  <div class="event-workspace">
    <div class="event-workspace-toolbar"><span><Layers3 :size="18" />事件处置工作区</span><button class="button secondary" :disabled="loading || !!busy" @click="refresh"><RefreshCw :size="16" />刷新证据</button></div>
    <InlineError v-if="error" :message="error" />
    <InlineError v-if="actionError" :message="actionError" />
    <p v-if="notice" class="event-notice" role="status">{{ notice }}</p>
    <LoadingState v-if="loading && !data" text="正在关联本事件的诊断、变更与恢复证据…" />
    <template v-if="data">
      <section class="event-focus" :class="{ recovered: verified }">
        <div class="event-focus-content"><span class="event-eyebrow">{{ data.targetCode || '待关联服务' }} · {{ date(data.generatedAt) }}</span><h2>{{ stageTitle }}</h2><p>{{ staleObservation && data.stage.code === 'RECOVERED' ? '此前已记录恢复，当前需要新鲜业务探针确认。' : data.stage.basis }}</p>
          <div class="event-focus-actions"><button v-if="data.actions.canDiagnose" class="button primary" :disabled="!!busy" @click="prepareRun"><Play :size="16" />发起 AI 诊断</button><button class="button secondary" @click="emit('question')"><Bot :size="16" />询问本事件</button><button class="button secondary" @click="emit('records')">补充处置记录</button></div>
          <p v-if="!data.actions.canDiagnose && data.actions.reason" class="event-context-note">{{ data.actions.reason }}</p>
        </div>
        <div class="event-focus-result"><ShieldCheck v-if="verified" :size="30" /><Activity v-else :size="30" /><strong>{{ verificationTitle }}</strong><span>{{ data.verification.scope === 'HISTORICAL' ? '历史结论，不代表目标当前健康' : recoveryLabel(data.verification.source) }}</span></div>
      </section>
      <ol class="event-stage-rail" aria-label="事件处理阶段"><li v-for="(step, index) in steps" :key="step.label" :class="{ active: step.codes.includes(staleObservation && data.stage.code === 'RECOVERED' ? 'VERIFYING' : data.stage.code) }"><span>{{ index + 1 }}</span><strong>{{ step.label }}</strong><ArrowRight v-if="index < steps.length - 1" :size="16" /></li></ol>
      <p v-if="data.access.notice" class="event-context-note">{{ data.access.notice }}</p>
      <ApprovalCard v-for="approval in approvals" :key="`${approval.id}:${approval.revision}`" :approval="approval" :available="inbox.isCurrent(approval)" :busy="!!busy || !!inbox.busy" @decision="decide(approval, $event)" />
      <p v-if="data.pendingApprovalIds.length && !approvals.length" class="event-notice">有审批状态需要核对，请刷新证据或打开顶部审批收件箱。</p>
      <div class="event-evidence-grid">
        <section class="event-card"><header><div><span class="event-eyebrow">OBSERVED EVIDENCE</span><h3>已取得的事实</h3></div><Activity :size="20" /></header><div class="event-card-body"><dl v-if="data.facts.length" class="event-fact-list"><div v-for="fact in data.facts" :key="fact.id"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}<small>{{ scopeLabel(fact.scope) }} · {{ fact.source }} · {{ date(fact.observedAt) }}</small></dd></div></dl><p v-else class="event-empty">暂无可读取的事实。缺少观测不表示服务正常。</p></div></section>
        <section class="event-card"><header><div><span class="event-eyebrow">DIAGNOSIS</span><h3>诊断判断与证据缺口</h3></div><Bot :size="20" /></header><div class="event-card-body"><p class="event-diagnosis-summary">{{ data.diagnosis.summary || '尚无已保存的 AI 诊断；可以发起诊断或补充事件资料。' }}</p>
          <p v-if="data.diagnosis.recordedAt" class="event-context-note">诊断记录于 {{ date(data.diagnosis.recordedAt) }}</p>
          <article v-for="item in data.hypotheses" :key="item.id" class="event-hypothesis"><span>候选原因 · 待证据核对</span><h4>{{ item.title }}</h4><p>{{ item.reason }}</p><small v-if="item.evidenceIds.length">引用证据：{{ item.evidenceIds.join('、') }}</small></article>
          <div v-if="data.gaps.length || data.diagnosis.evidenceGaps.length" class="event-gaps"><strong><TriangleAlert :size="16" />仍需核对</strong><ul><li v-for="gap in [...new Set([...data.gaps.map(item => item.message), ...data.diagnosis.evidenceGaps])]" :key="gap">{{ gap }}</li></ul></div>
          <p class="event-context-note">AI 判断与实际观测分开展示；已提出方案不表示已执行或已恢复。</p>
        </div></section>
      </div>
      <section class="event-card"><header><div><span class="event-eyebrow">CHANGE CORRELATION</span><h3>本事件关联的变更</h3><p>核对同一目标和事件窗口的变更。时间相关性不等于根因已被证实。</p></div><History :size="20" /></header><div class="event-card-body"><div v-if="data.changes.length" class="event-change-list"><article v-for="change in data.changes" :key="change.id"><span class="event-change-dot"></span><div><h4>{{ change.summary }}</h4><p>{{ change.source }} · {{ date(change.observedAt) }} · {{ change.status }}</p><details v-if="change.revisionBefore || change.revisionAfter"><summary>核对配置版本</summary><code>{{ change.revisionBefore || '未提供旧版本' }} → {{ change.revisionAfter || '未提供新版本' }}</code></details><details v-if="changeRows(change).length"><summary>查看变更前后</summary><dl class="event-change-diff"><div v-for="row in changeRows(change)" :key="row.key"><dt>{{ row.label }}</dt><dd><span>{{ row.before }}</span><ArrowRight :size="14" /><strong>{{ row.after }}</strong></dd></div></dl></details></div></article></div><p v-else class="event-empty">当前授权范围内没有可关联的变更记录。不能据此排除配置原因。</p></div></section>
      <div class="event-evidence-grid">
        <section class="event-card"><header><div><span class="event-eyebrow">EXECUTION</span><h3>执行计划与运行</h3><p>当前显示 {{ data.runs.length }} / {{ data.runTotal }} 次可访问运行</p></div><GitBranch :size="20" /></header><div class="event-card-body"><div v-if="data.runs.length" class="event-run-list"><RouterLink v-for="run in data.runs" :key="run.id" :to="{ path: '/automation', query: { run: run.id, ticketId: ticket.id } }"><div><strong>{{ status(run.status) }}</strong><p>{{ run.message || '查看当前节点、动作计划与完整执行轨迹' }}</p><small>{{ date(run.createdAt) }} · {{ run.id.slice(0, 8) }}</small></div><ArrowRight :size="18" /></RouterLink></div><p v-else class="event-empty">暂无当前账号可读取的运行记录。人工处理记录仍保留在本事件中。</p></div></section>
        <section class="event-card"><header><div><span class="event-eyebrow">BUSINESS VERIFICATION</span><h3>业务恢复验证</h3></div><ShieldCheck :size="20" /></header><div class="event-card-body"><strong class="event-verification-title" :class="{ success: verified }">{{ verificationTitle }}</strong><p class="event-context-note">{{ scopeLabel(data.verification.scope) }} · {{ date(data.verification.observedAt) }}</p><dl class="event-fact-list"><div><dt>恢复来源</dt><dd>{{ recoveryLabel(data.verification.source) }}</dd></div><div><dt>业务观测</dt><dd>{{ data.verification.scope === 'NONE' ? '尚无可确认观测' : data.verification.businessHealthy ? '验证记录中业务请求成功' : '业务成功尚未确认' }}</dd></div><div><dt>连续成功探针</dt><dd>{{ data.verification.scope === 'NONE' ? '—' : data.verification.consecutiveSuccesses }}</dd></div><div><dt>告警恢复</dt><dd>{{ data.verification.alertResolved ? '已取得本事件告警恢复记录' : '尚未取得恢复确认' }}</dd></div></dl><p class="event-context-note">工作流完成、人工标记解决和到期保护恢复均单独记录；业务验证结果以这里的证据为准。</p></div></section>
      </div>
      <section class="event-retrospective"><div><FileText :size="23" /><div><h3>把本次处置沉淀为经验</h3><p>根据当前证据生成可编辑复盘草稿，核对适用条件后再进入知识审核。</p></div></div><button class="button secondary" :disabled="!!busy" @click="prepareDraft">整理复盘草稿<ArrowRight :size="16" /></button></section>
      <details class="event-sources"><summary>数据来源与可读取范围</summary><ul><li v-for="item in data.sources" :key="item.name"><strong>{{ item.name }}</strong> · {{ item.status === 'AVAILABLE' ? '已读取' : item.status === 'RESTRICTED' ? '当前权限不可读取' : item.status === 'UNAVAILABLE' ? '暂不可用' : '不适用' }}<span>{{ item.message }}</span></li></ul></details>
    </template>
    <BaseModal v-if="createOpen" title="为本事件发起 AI 诊断" @close="createOpen = false"><div class="event-dialog-body"><p>读取当前事件证据并执行选定流程；需要授权的修改动作会另行发起审批。</p><InlineError v-if="actionError" :message="actionError" /><FormField label="工作流"><select v-model="definition" :disabled="!!busy || createUnconfirmed"><option v-for="item in definitions" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.published_version }}</option></select></FormField><FormField label="已验证的模型"><select v-model="model" :disabled="!!busy || createUnconfirmed"><option v-for="item in models" :key="item.provider" :value="item.provider">{{ item.model }}</option></select></FormField><p v-if="!models.length && !busy" class="event-context-note">尚无已验证的可用模型，请在自动化中心核对模型连接。</p><button class="button primary" :disabled="!!busy || !model || !definition" @click="createRun"><Play :size="16" />{{ busy ? '正在处理…' : createUnconfirmed ? '使用同一请求重试' : '启动诊断' }}</button></div></BaseModal>
    <BaseModal v-if="draftOpen" title="事件复盘草稿" @close="draftOpen = false"><div class="event-dialog-body"><p>请核对诊断假设、有效动作和适用环境；草稿默认私有，不自动发布或提交给外部模型。</p><InlineError v-if="actionError" :message="actionError" /><FormField label="复盘内容"><textarea v-model="draft" rows="16" :disabled="!!busy || !!savedDocument || draftUnconfirmed" /></FormField><p v-if="draftUnconfirmed" class="event-notice" role="status">保存结果尚未确认。请先打开事件文档核对是否已生成复盘，避免重复上传。</p><p v-if="savedDocument" role="status">草稿文档 #{{ savedDocument }} 已保存。请继续解析并提交审核。</p><button v-if="canSaveKnowledge && !savedDocument && !draftUnconfirmed" class="button primary" :disabled="!!busy || !draft.trim()" @click="saveDraft"><Check :size="16" />保存私有复盘文档</button><p v-if="!canSaveKnowledge" class="event-context-note">当前账号可以阅读草稿；保存关联知识需要事件创建人、处理人或管理员权限。</p><button class="button secondary" @click="draftOpen = false; emit('documents')">{{ savedDocument ? '前往关联文档' : '查看事件文档' }}</button></div></BaseModal>
  </div>
</template>
