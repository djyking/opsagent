<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Activity, ArrowRight, Bot, Check, FileText, GitBranch, History, Layers3, Play, RefreshCw, ShieldCheck, TriangleAlert } from '@lucide/vue';
import { automationApi, type Model, type Definition, type PendingApproval } from '@/api/automation';
import { useEventWorkspace } from '@/composables/useEventWorkspace';
import { eventRetrospective, eventRunLabels, eventRecordAuthor, currentlyVerified, recoveryLabel, observationFresh, definitelyRejected } from '@/utils/event-workspace';
import { automationMessage, automationRunPresentation } from '@/utils/automation-presentation';
import { useAuthStore } from '@/stores/auth';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import ApprovalCard from '@/components/automation/ApprovalCard.vue';
import BaseModal from '@/components/BaseModal.vue';
import FormField from '@/components/FormField.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import DetailPanel from '@/components/DetailPanel.vue';
import EventDependencyMap from './EventDependencyMap.vue';
import EventRecoveryBinding from './EventRecoveryBinding.vue';
import EventManualRecovery from './EventManualRecovery.vue';
import EventKnowledgeDraft from './EventKnowledgeDraft.vue';
import type { Ticket, TicketLog, TicketWorkRecord } from '@/types/api';
import type { EventLifecycle, EventAction } from '@/api/event-lifecycle';
import '@/styles/pages/event-workspace.css';

const props = defineProps<{ ticket: Ticket; lifecycle?: EventLifecycle; lifecycleError?: string; records?: TicketWorkRecord[]; logs?: TicketLog[]; sla?: Record<string, unknown>; canResolve?: boolean }>();
const emit = defineEmits<{ refresh: []; question: []; records: []; documents: []; lifecycle: [action: EventAction]; resolve: [] }>();
const auth = useAuthStore();
const inbox = useApprovalInboxStore();
const identity = () => auth.user ? `${auth.user.userId}:${auth.user.roles.join(',')}` : '';
const workspace = useEventWorkspace(() => props.ticket.id, identity);
const { data, loading, error } = workspace;
const busy = ref('');
const actionError = ref('');
const notice = ref('');
const now = ref(Date.now());
const contextOpen = ref(false);
const evidenceOpen = ref(false);
const processOpen = ref(false);
const activityOpen = ref(false);
const recoveryBlockers = ref<string[]>(['正在读取恢复关联']);
const latestRun = computed(() => data.value?.runs[0]);
const runPresentation = computed(() => automationRunPresentation(latestRun.value, data.value?.stage.code === 'VERIFYING' && ['QUEUED', 'RUNNING'].includes(latestRun.value?.status || '')));
const runActive = computed(() => ['AI', 'APPROVAL', 'INPUT', 'PAUSED', 'VERIFYING'].includes(runPresentation.value.code));
const awaitingDecision = computed(() => ['APPROVAL', 'INPUT'].includes(runPresentation.value.code));
const recoveryStage = computed(() => !!props.lifecycle && ['VERIFYING', 'READY_TO_CLOSE', 'CLOSED'].includes(props.lifecycle.stage)
  && (!runActive.value || props.lifecycle.stage === 'CLOSED'));
const needsHandoff = computed(() => runPresentation.value.code === 'HANDOFF');
const closed = computed(() => props.lifecycle?.stage === 'CLOSED');
const archived = computed(() => props.lifecycle?.stage === 'LEGACY_ARCHIVED');
const phaseIndex = computed(() => closed.value ? 4 : recoveryStage.value || runPresentation.value.code === 'VERIFYING' ? 3
  : runPresentation.value.code === 'AI' && data.value?.stage.code === 'DIAGNOSING' ? 1
  : runActive.value || needsHandoff.value ? 2
  : ['ASSIGNED', 'PROCESSING', 'SUSPENDED', 'WAITING_CONFIRM', 'RESOLVED', 'CLOSED'].includes(props.ticket.status) ? 2
  : data.value?.stage.code === 'DIAGNOSING' ? 1 : 0);
const manualTitle = computed(() => archived.value ? '历史工单档案' : closed.value ? '事件已关闭' : recoveryStage.value ? '恢复验证'
  : latestRun.value ? runPresentation.value.title : phaseIndex.value === 2 ? '事件处置' : '异常发现与诊断');
const currentTask = computed(() => props.lifecycle?.result?.content || (props.ticket.status === 'CREATED'
  ? '接收工单并核对告警证据' : '完成处置后提交处理结果'));
const allowed = (action: EventAction) => props.lifecycle?.allowedActions.includes(action) === true;
const recordLabels: Record<string, string> = { DIAGNOSIS: '诊断依据', ACTION: '处理记录', VERIFICATION: '验证记录', ROOT_CAUSE: '根因记录', BUSINESS_REPLY: '业务回复', EVENT_RESULT: '处理结果', EVENT_TECH_PASS: '技术恢复确认', EVENT_TECH_FAIL: '验证未通过', EVENT_BUSINESS_CONFIRM: '业务恢复确认', EVENT_CLOSE: '事件关闭', EVENT_REOPEN: '重新处置', EVENT_RECOVERY_BINDING: '恢复关联更正' };
const verified = computed(() => currentlyVerified(data.value, now.value));
const staleObservation = computed(() => data.value?.verification.scope === 'CURRENT' && !!data.value.verification.observedAt && !observationFresh(data.value, now.value));
const verificationTitle = computed(() => staleObservation.value ? '当前观测已过期，等待重新核对' : data.value?.verification.label);
const stageTitle = computed(() => staleObservation.value && data.value?.stage.code === 'RECOVERED' ? '重新核对业务状态' : data.value?.stage.label);
const approvals = computed(() => inbox.availableItems.filter(item => item.ticketId === props.ticket.id
  && data.value?.pendingApprovalIds.includes(item.id)));
const steps = [
  { label: '发现' }, { label: '诊断' }, { label: '处置' }, { label: '恢复验证' }, { label: '知识沉淀' },
];
const models = ref<Model[]>([]);
const definitions = ref<Definition[]>([]);
const model = ref('');
const definition = ref('');
const createOpen = ref(false);
let createAttempt: { ticketId: number; provider: string; definitionId: string; requestId: string } | undefined;
const createUnconfirmed = ref(false);
const draftOpen = ref(false);
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
function showCurrentApproval() {
  if (approvals.value[0]) inbox.select(approvals.value[0]); else inbox.show();
}
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
watch(() => data.value?.runs.map(run => `${run.id}:${run.status}`).join('|'), (value, previous) => {
  if (previous != null && value && value !== previous) emit('refresh');
});
watch([() => props.ticket.id, identity], () => {
  epoch++; busy.value = ''; actionError.value = ''; notice.value = ''; createOpen.value = false; draftOpen.value = false;
  createAttempt = undefined; createUnconfirmed.value = false; models.value = []; definitions.value = [];
  contextOpen.value = evidenceOpen.value = processOpen.value = activityOpen.value = false;
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
    <InlineError v-if="error" :message="error" />
    <InlineError v-if="lifecycleError" :message="lifecycleError" />
    <InlineError v-if="actionError" :message="actionError" />
    <p v-if="notice" class="event-notice" role="status">{{ notice }}</p>
    <ol class="event-stage-rail" aria-label="事件处理阶段"><li v-for="(step, index) in steps" :key="step.label" :class="{ active: index === phaseIndex, complete: index < phaseIndex }"><span>{{ index + 1 }}</span><strong>{{ step.label }}</strong><Check v-if="index < phaseIndex" :size="15" /><ArrowRight v-else-if="index < steps.length - 1" :size="16" /></li></ol>
    <section class="event-current-card">
      <header><h2>{{ manualTitle }}</h2><span class="event-state-label">{{ closed ? '已关闭' : recoveryStage ? '验证与确认' : '当前阶段' }}</span><div class="event-inline-actions"><button class="button secondary" @click="contextOpen = true">查看事件上下文</button><button class="icon-button" title="刷新事件" :disabled="loading || !!busy" @click="refresh(); emit('refresh')"><RefreshCw :size="16" /></button></div></header>
      <template v-if="archived"><p class="event-main-message">升级前已结束的工单，保留原始处理历史。</p><p class="event-main-support">没有补造技术验证、业务确认或事件关闭记录。如问题再次发生，请报告新事件。</p><RouterLink class="button secondary" :to="{ path: '/tickets', query: { create: '1' } }">报告新问题</RouterLink></template><template v-else-if="recoveryStage">
        <p class="event-main-message">{{ closed ? '恢复结论和关闭责任已记录' : lifecycle?.stage === 'READY_TO_CLOSE' ? '验证与业务确认已完成，可以关闭事件' : '逐项核对恢复结果，再关闭事件' }}</p>
        <dl class="event-focus-rows">
          <div><dt><Activity :size="21" />技术验证</dt><dd><strong>{{ lifecycle?.technical ? '已确认技术恢复' : '待技术确认' }}</strong><small>{{ lifecycle?.technical ? `用户 #${lifecycle.technical.createBy} · ${date(lifecycle.technical.createTime)}` : '依据实际指标、探针或人工检查记录确认' }}</small></dd><Check v-if="lifecycle?.technical" class="event-check" :size="20" /></div>
          <div><dt><ShieldCheck :size="21" />业务确认</dt><dd><strong>{{ !lifecycle?.businessRequired ? '按服务规则无需独立确认' : lifecycle.business ? '业务已确认恢复' : '等待业务确认' }}</strong><small>{{ lifecycle?.business ? `用户 #${lifecycle.business.createBy} · ${date(lifecycle.business.createTime)}` : lifecycle?.businessRule }}</small></dd><Check v-if="lifecycle?.business || !lifecycle?.businessRequired" class="event-check" :size="20" /></div>
          <div><dt><FileText :size="21" />当前主工单</dt><dd><strong>{{ ticket.ticketNo }}</strong><small>{{ ['RESOLVED', 'CLOSED'].includes(ticket.status) ? '处理结果已解决；事件关闭单独记录' : '完成处置后需标记工单已解决' }}</small></dd></div>
        </dl>
        <details class="event-verification-detail"><summary>查看验证明细与关闭条件</summary><p v-if="data">{{ verificationTitle }} · {{ data.verification.scope === 'HISTORICAL' ? '历史结论，不代表目标当前健康' : recoveryLabel(data.verification.source) }}</p><p v-if="data" class="event-context-note">{{ scopeLabel(data.verification.scope) }} · {{ date(data.verification.observedAt) }} · 连续成功 {{ data.verification.consecutiveSuccesses }} 次</p><article v-for="item in [lifecycle?.technical, lifecycle?.business, lifecycle?.closed].filter(Boolean)" :key="item!.id"><strong>{{ recordLabels[item!.recordType] }}</strong><p>{{ item!.content }}</p><small>{{ item!.evidence }}</small></article><ul v-if="lifecycle?.blockers.length"><li v-for="item in lifecycle.blockers" :key="item">{{ item }}</li></ul></details>
        <footer class="event-current-actions"><EventManualRecovery v-if="!closed" :ticket-id="ticket.id" :target-code="ticket.affectedCiCode" :incident-id="ticket.incidentId" @restored="emit('refresh'); refresh()" /><button v-if="allowed('TECH_FAIL')" class="button secondary" @click="emit('lifecycle', 'TECH_FAIL')">验证未通过</button><button v-if="allowed('TECH_PASS') && !lifecycle?.technical" class="button primary" :disabled="recoveryBlockers.length > 0" :title="recoveryBlockers.join('；')" @click="emit('lifecycle', 'TECH_PASS')">确认技术恢复</button><button v-else-if="allowed('BUSINESS_CONFIRM') && !lifecycle?.business" class="button primary" @click="emit('lifecycle', 'BUSINESS_CONFIRM')">确认业务恢复</button><button v-else-if="canResolve && !['RESOLVED', 'CLOSED'].includes(ticket.status)" class="button primary" @click="emit('resolve')">标记工单已解决</button><button v-else-if="allowed('CLOSE')" class="button primary" @click="emit('lifecycle', 'CLOSE')">关闭事件</button><button v-if="allowed('REOPEN')" class="button secondary" @click="emit('lifecycle', 'REOPEN')">重新处置</button><button v-if="closed" class="button primary" :disabled="!data || !!busy" @click="draftOpen = true">整理知识草稿</button></footer>
      </template>
      <template v-else>
        <p class="event-main-message" role="status">{{ latestRun ? runPresentation.description : ticket.assigneeId ? `当前由用户 #${ticket.assigneeId} 继续处理` : '等待负责人接单处理' }}</p>
        <p v-if="needsHandoff && latestRun?.message" class="event-main-support">{{ automationMessage(latestRun.message) }}</p>
        <p v-if="runActive && latestRun" class="event-main-support">运行状态 {{ status(latestRun.status) }} · {{ date(latestRun.updatedAt || latestRun.createdAt) }} 更新</p>
        <details v-if="latestRun?.message" class="event-verification-detail"><summary>查看运行说明与技术详情</summary><pre class="event-technical-message">{{ latestRun.message }}</pre></details>
        <dl v-if="!runActive" class="event-focus-rows">
          <div><dt><FileText :size="21" />关联工单</dt><dd><strong>{{ ticket.ticketNo }}</strong><small>工单状态与事件恢复状态分别记录</small></dd></div>
          <div><dt><ShieldCheck :size="21" />当前处理人</dt><dd><strong>{{ ticket.assigneeId ? `用户 #${ticket.assigneeId}` : '待接单' }}</strong><small>{{ date(ticket.updateTime) }} 更新</small></dd></div>
          <div><dt><Check :size="21" />当前任务</dt><dd><strong>{{ currentTask }}</strong><small>提交处理结果后进入恢复验证</small></dd></div>
        </dl>
        <footer class="event-current-actions"><button v-if="awaitingDecision" class="button primary" :disabled="!!busy || !!inbox.busy" @click="showCurrentApproval">{{ runPresentation.action }}</button><RouterLink v-else-if="runActive && latestRun" class="button primary" :to="{ path: '/automation', query: { run: latestRun.id, ticketId: ticket.id } }">{{ runPresentation.action }}</RouterLink><EventManualRecovery v-if="!runActive" :ticket-id="ticket.id" :target-code="ticket.affectedCiCode" :incident-id="ticket.incidentId" @restored="emit('refresh'); refresh()" /><button v-if="data?.actions.canDiagnose && !runActive" class="button secondary" :disabled="!!busy" @click="prepareRun"><Bot :size="16" />AI 诊断</button><button v-if="!auth.isDemo" class="button secondary" @click="emit('records')">记录处理</button><button v-if="allowed('RESULT') && !runActive" class="button primary" @click="emit('lifecycle', 'RESULT')">提交处理结果</button><span v-if="!lifecycle" class="event-context-note">事件确认规则尚未读取，暂不可推进验证</span></footer>
      </template>
      <EventRecoveryBinding :ticket-id="ticket.id" :version="ticket.version" :closed="closed || archived" @status="recoveryBlockers = $event" @saved="emit('refresh'); refresh()" />
      <ApprovalCard v-for="approval in approvals" :key="`${approval.id}:${approval.revision}`" :approval="approval" :available="inbox.isCurrent(approval)" :busy="!!busy || !!inbox.busy" @decision="decide(approval, $event)" />
      <p v-if="data?.pendingApprovalIds.length && !approvals.length" class="event-context-note">待审批状态需要核对，可打开顶部审批收件箱。</p>
    </section>
    <details class="event-fold" :open="evidenceOpen" @toggle="evidenceOpen = ($event.target as HTMLDetailsElement).open"><summary><strong>证据与诊断</strong><span>影响、根因与链路 · 按需展开</span></summary><div class="event-fold-body">
      <EventDependencyMap v-if="evidenceOpen" :code="ticket.affectedCiCode" :environment="ticket.environment" />
      <LoadingState v-if="loading && !data" text="正在读取事件证据…" />
      <template v-if="data">
        <section><h3>影响与根因</h3><p>{{ data.targetCode || ticket.affectedCiCode || '受影响服务待关联' }}</p><p>{{ data.diagnosis.summary || '尚无已保存的诊断结论' }}</p><RouterLink v-if="ticket.affectedCiCode" :to="{ path: '/observability/topology', query: { ciCode: ticket.affectedCiCode } }">查看关联服务与小链路</RouterLink><p class="event-context-note">候选原因不等于已确认根因；恢复与根因复核分别记录。</p></section>
        <section><h3>已取得的事实</h3><dl v-if="data.facts.length" class="event-fact-list"><div v-for="fact in data.facts" :key="fact.id"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}<small>{{ scopeLabel(fact.scope) }} · {{ fact.source }} · {{ date(fact.observedAt) }}</small></dd></div></dl><p v-else class="event-empty">暂无可读取事实，缺少观测不表示正常。</p></section>
        <section><h3>诊断判断与证据缺口</h3><article v-for="item in data.hypotheses" :key="item.id" class="event-hypothesis"><span>候选原因 · 待核对</span><h4>{{ item.title }}</h4><p>{{ item.reason }}</p><small v-if="item.evidenceIds.length">引用证据：{{ item.evidenceIds.join('、') }}</small></article><div v-if="data.gaps.length || data.diagnosis.evidenceGaps.length" class="event-gaps"><strong>仍需核对</strong><ul><li v-for="gap in [...new Set([...data.gaps.map(item => item.message), ...data.diagnosis.evidenceGaps])]" :key="gap">{{ gap }}</li></ul></div></section>
        <details class="event-sources"><summary>数据来源与可读取范围</summary><ul><li v-for="item in data.sources" :key="item.name"><strong>{{ item.name }}</strong> · {{ item.status === 'AVAILABLE' ? '已读取' : item.status === 'RESTRICTED' ? '当前权限不可读取' : item.status === 'UNAVAILABLE' ? '暂不可用' : '不适用' }}<span>{{ item.message }}</span></li></ul></details>
      </template>
      <article v-for="record in (records || []).filter(row => ['DIAGNOSIS', 'ROOT_CAUSE', 'VERIFICATION'].includes(row.recordType))" :key="record.id" class="event-record"><strong>{{ recordLabels[record.recordType] }}</strong><p>{{ record.content }}</p><small>{{ record.evidence }} · {{ eventRecordAuthor(record) }} · {{ date(record.createTime) }}</small></article>
    </div></details>
    <details class="event-fold" :open="processOpen" @toggle="processOpen = ($event.target as HTMLDetailsElement).open"><summary><strong>处置过程</strong><span>{{ data?.runs.length || 0 }} 次可见运行 · 人工处理记录</span></summary><div class="event-fold-body">
      <div v-if="data?.runs.length" class="event-run-list"><RouterLink v-for="run in data.runs" :key="run.id" :to="{ path: '/automation', query: { run: run.id, ticketId: ticket.id } }"><div><strong>{{ status(run.status) }}</strong><p>{{ automationMessage(run.message) || '查看完整执行轨迹' }}</p><small>{{ date(run.createdAt) }} · {{ run.id.slice(0, 8) }}</small></div><ArrowRight :size="18" /></RouterLink></div>
      <section v-if="data"><h3>本事件关联的变更</h3><div class="event-change-list"><article v-for="change in data.changes" :key="change.id"><div><h4>{{ change.summary }}</h4><p>{{ change.source }} · {{ date(change.observedAt) }} · {{ change.status }}</p><details v-if="changeRows(change).length"><summary>查看变更前后</summary><dl class="event-change-diff"><div v-for="row in changeRows(change)" :key="row.key"><dt>{{ row.label }}</dt><dd><span>{{ row.before }}</span><ArrowRight :size="14" /><strong>{{ row.after }}</strong></dd></div></dl></details></div></article></div><p v-if="!data.changes.length" class="event-empty">暂无可读取的关联变更，不据此排除配置原因。</p></section>
      <article v-for="record in (records || []).filter(row => ['ACTION', 'EVENT_RESULT'].includes(row.recordType))" :key="record.id" class="event-record"><strong>{{ recordLabels[record.recordType] }}</strong><p>{{ record.content }}</p><small>{{ record.evidence }} · {{ eventRecordAuthor(record) }} · {{ date(record.createTime) }}</small></article>
    </div></details>
    <details class="event-fold" :open="activityOpen" @toggle="activityOpen = ($event.target as HTMLDetailsElement).open"><summary><strong>事件活动流</strong><span>最近更新 {{ date(ticket.updateTime) }}</span></summary><div class="event-fold-body"><article v-for="record in lifecycle?.history || []" :key="`event-${record.id}`" class="event-record"><strong>{{ recordLabels[record.recordType] || record.recordType }}</strong><p>{{ record.content }}</p><small>{{ eventRecordAuthor(record) }} · {{ date(record.createTime) }}</small></article><details><summary>工单状态历史 · {{ logs?.length || 0 }} 条</summary><article v-for="log in logs || []" :key="log.id" class="event-record"><strong>{{ log.fromStatus || '创建' }} → {{ log.toStatus }}</strong><p>{{ log.remark }}</p><small>用户 #{{ log.operatorId }} · {{ date(log.createTime) }}</small></article></details><button class="button secondary" @click="emit('documents')">查看关联文档与问答</button></div></details>
    <DetailPanel v-if="contextOpen" title="事件上下文" :subtitle="ticket.ticketNo" @close="contextOpen = false"><dl class="event-fact-list"><div><dt>负责人</dt><dd>{{ ticket.assigneeId ? `用户 #${ticket.assigneeId}` : '待分配' }}</dd></div><div><dt>关联服务</dt><dd>{{ ticket.affectedCiCode || '未关联' }}</dd></div><div><dt>当前主工单</dt><dd>{{ ticket.ticketNo }} · {{ ticket.status }}</dd></div><div v-if="sla"><dt>解决截止</dt><dd>{{ date(String(sla.resolutionDeadline || '')) }}</dd></div><div v-if="sla"><dt>响应截止</dt><dd>{{ date(String(sla.responseDeadline || '')) }}</dd></div></dl><div class="event-context-links"><RouterLink :to="{ path: '/itsm/alerts', query: { ciCode: ticket.affectedCiCode } }">关联服务告警</RouterLink><RouterLink to="/itsm/oncall">值班协作</RouterLink><RouterLink :to="{ path: '/automation', query: { ticketId: ticket.id } }">自动化运行</RouterLink></div><p v-if="data?.access.notice" class="event-context-note">{{ data.access.notice }}</p></DetailPanel>
    <BaseModal v-if="createOpen" title="为本事件发起 AI 诊断" @close="createOpen = false"><div class="event-dialog-body"><p>读取当前事件证据并执行选定流程；需要授权的修改动作会另行发起审批。</p><InlineError v-if="actionError" :message="actionError" /><FormField label="工作流"><select v-model="definition" :disabled="!!busy || createUnconfirmed"><option v-for="item in definitions" :key="item.id" :value="item.id">{{ item.name }} · v{{ item.published_version }}</option></select></FormField><FormField label="已验证的模型"><select v-model="model" :disabled="!!busy || createUnconfirmed"><option v-for="item in models" :key="item.provider" :value="item.provider">{{ item.model }}</option></select></FormField><p v-if="!models.length && !busy" class="event-context-note">尚无已验证的可用模型，请在自动化中心核对模型连接。</p><button class="button primary" :disabled="!!busy || !model || !definition" @click="createRun"><Play :size="16" />{{ busy ? '正在处理…' : createUnconfirmed ? '使用同一请求重试' : '启动诊断' }}</button></div></BaseModal>
    <EventKnowledgeDraft :key="`${ticket.id}:${auth.user?.userId}`" :open="draftOpen" :ticket-id="ticket.id" :ticket-title="ticket.title" :initial-content="data ? eventRetrospective(data, ticket.title) : ''" :can-save="canSaveKnowledge" @close="draftOpen = false" @saved="emit('refresh')" />
  </div>
</template>
