<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Activity, RefreshCw, Gauge, Plus, Pencil, Trash2, History, ShieldCheck, RotateCcw } from '@lucide/vue';
import ObservabilityWorkspaceView from './ObservabilityWorkspaceView.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import BaseModal from '@/components/BaseModal.vue';
import FormField from '@/components/FormField.vue';
import { useAuthStore } from '@/stores/auth';
import { useTrafficGovernance } from '@/composables/useTrafficGovernance';
import type { TrafficRule } from '@/api/trafficGovernance';
import '@/styles/pages/observability-governance.css';
const route = useRoute(); const auth = useAuthStore();
const ciCode = computed(() => typeof route.query.ciCode === 'string' ? route.query.ciCode : '');
const manager = useTrafficGovernance(() => ciCode.value);
const { workspace, history, loading, busy, error, notice, selectedType, plan } = manager;
const selected = computed(() => workspace.value?.ruleSets.find(s => s.type === selectedType.value));
const section = ref<'rules' | 'history'>('rules'); const editing = ref(false); const editingIndex = ref(-1);
const draft = ref<TrafficRule>({}); const comment = ref('');
const status = (value?: string) => ({ AVAILABLE: '可读取', APPLIED: '已应用', PENDING: '等待客户端加载', PUBLISHED: '已发布，待确认', UNCONFIRMED: '结果未确认', REJECTED: '已拒绝', REQUESTED: '已提交', NOT_CONFIGURED: '尚未配置', NOT_INTEGRATED: '尚未接入', UNAVAILABLE: '暂不可读', DISABLED: '未启用', UNKNOWN: '待核对', NO_SAMPLES: '暂无样本' }[value || ''] || '待核对');
const metric = (value: number | null | undefined, unit = '') => value == null ? '—' : `${Number.isInteger(value) ? value : value.toFixed(2)}${unit}`;
const metrics = computed(() => [
  { label: '通过 QPS', value: metric(workspace.value?.summary.passQps), hint: '完整问答请求入口' },
  { label: '拦截 QPS', value: metric(workspace.value?.summary.blockQps), hint: '入口与请求保护合计' },
  { label: '平均响应耗时', value: metric(workspace.value?.summary.avgRt, ' ms'), hint: '包含流式请求生命周期' },
  { label: '当前并发', value: metric(workspace.value?.summary.activeThreads), hint: '尚未完成的问答请求' },
]);
function openEditor(index = -1) {
  if (!selected.value?.editable || busy.value) return;
  editingIndex.value = index;
  draft.value = index >= 0 ? JSON.parse(JSON.stringify(selected.value.persistedRules[index])) : selectedType.value === 'FLOW'
    ? { resource: 'ops-rag-request', grade: 1, count: 10, strategy: 0, refResource: 'ops-rag-ask', controlBehavior: 0, warmUpPeriodSec: 10, maxQueueingTimeMs: 500 }
    : selectedType.value === 'DEGRADE' ? { resource: 'ops-rag-request', grade: 0, count: 10000, timeWindow: 10, minRequestAmount: 5, statIntervalMs: 1000, slowRatioThreshold: 0.8 }
    : { highestSystemLoad: -1, highestCpuUsage: 0.85, avgRt: -1, maxThread: -1, qps: -1 };
  editing.value = true;
}
async function prepareEditor() {
  if (!selected.value) return;
  const rows = JSON.parse(JSON.stringify(selected.value.persistedRules)) as TrafficRule[];
  if (editingIndex.value < 0) rows.push({ ...draft.value }); else rows[editingIndex.value] = { ...draft.value };
  await manager.prepare(selected.value, rows);
  if (plan.value) { editing.value = false; comment.value = ''; }
}
async function removeRule(index: number) { if (selected.value) { await manager.prepare(selected.value, selected.value.persistedRules.filter((_, i) => i !== index)); comment.value = ''; } }
function summary(rule: TrafficRule) {
  if (selectedType.value === 'FLOW') return `${Number(rule.grade) === 1 ? 'QPS' : '并发线程'} ≤ ${rule.count} · ${['快速失败', '预热', '排队等待'][Number(rule.controlBehavior || 0)]} · ${Number(rule.strategy) === 1 ? '关联' : '直接'}`;
  if (selectedType.value === 'DEGRADE') return `${['慢调用', '异常比例', '异常数'][Number(rule.grade)]}阈值 ${rule.count} · 熔断 ${rule.timeWindow}s · 最少 ${rule.minRequestAmount} 次请求`;
  return Object.entries(rule).filter(([, value]) => typeof value === 'number' && value >= 0).map(([key, value]) => `${key}: ${value}`).join(' · ');
}
let timer: ReturnType<typeof setInterval>;
onMounted(() => { void manager.load(); void manager.loadHistory(); timer = setInterval(() => { if (!document.hidden && !busy.value && !editing.value && !plan.value) void manager.load(); }, 15000); });
onBeforeUnmount(() => clearInterval(timer));
watch([() => auth.token, ciCode, selectedType], () => { editing.value = false; draft.value = {}; comment.value = ''; });
</script>
<template>
  <ObservabilityWorkspaceView title="服务与观测" description="实时核对流量与保护规则，让每一次限制都有依据。">
    <template #actions><button class="button secondary" :disabled="loading || busy" @click="manager.load"><RefreshCw :size="16" />刷新运行态</button></template>
    <section class="gov-intro"><span class="gov-intro-icon"><Gauge :size="27" /></span><div><span class="gov-eyebrow">TRAFFIC GOVERNANCE</span><h2>看见流量，守住服务边界。</h2><p>{{ ciCode || 'ops-rag-service' }} · Sentinel 运行态与 Nacos 持久化规则</p></div><span class="gov-access"><ShieldCheck :size="16" />{{ auth.isAdmin ? '管理员受控发布' : '当前账号只读' }}</span></section>
    <InlineError v-if="error" :message="error" />
    <p v-if="notice" class="gov-note" role="status">{{ notice }}</p>
    <LoadingState v-if="loading && !workspace" text="正在核对流量与规则源…" />
    <template v-if="workspace">
      <div v-if="workspace.summary.status === 'NOT_INTEGRATED'" class="panel gov-detail-body"><EmptyState :icon="Gauge" title="该服务尚未接入流量治理" :description="workspace.summary.message" /><RouterLink :to="{ path: '/observability/traffic', query: { ...route.query, ciCode: 'ops-rag-service' } }" class="button secondary">查看已接入的 RAG 服务</RouterLink></div>
      <template v-else>
        <div class="gov-metrics"><article v-for="item in metrics" :key="item.label"><span>{{ item.label }}</span><strong>{{ item.value }}</strong><small>{{ item.hint }}</small></article></div>
        <p class="gov-note" :class="{ warning: workspace.summary.status !== 'AVAILABLE' }">{{ workspace.summary.message }} · 每 15 秒刷新 · {{ status(workspace.summary.status) }}</p>
        <section class="panel gov-resources"><header class="gov-section-heading"><div><h3>纳管资源</h3><p>入口校验和完整问答各自计量，避免把校验耗时当成模型响应。</p></div><span class="gov-badge">{{ workspace.resources.length }} 个资源</span></header><div class="obs-table-scroll"><table class="obs-table"><thead><tr><th>资源</th><th>通过 QPS</th><th>拦截 QPS</th><th>平均 RT</th><th>并发</th><th>状态</th></tr></thead><tbody><tr v-for="resource in workspace.resources" :key="resource.resource"><td><strong>{{ resource.label }}</strong><small>{{ resource.resource }}</small><small>{{ resource.measurement }}</small></td><td>{{ metric(resource.passQps) }}</td><td>{{ metric(resource.blockQps) }}</td><td>{{ metric(resource.avgRt, ' ms') }}</td><td>{{ metric(resource.activeThreads) }}</td><td><span class="gov-badge">{{ status(resource.status) }}</span></td></tr><tr v-if="!workspace.resources.length"><td colspan="6">运行态暂不可读，未填充示例数据。</td></tr></tbody></table></div></section>
        <section class="panel gov-rules"><header class="gov-section-heading"><div><h3>保护规则</h3><p>发布前检查差异，发布后分别核对持久化与客户端状态。</p></div><span class="gov-badge">Nacos → Sentinel</span></header>
          <nav class="gov-rule-types" aria-label="规则类型"><button v-for="set in workspace.ruleSets" :key="set.type" :class="{ active: selectedType === set.type }" @click="selectedType = set.type"><span>{{ set.label }}</span><small>{{ set.supported ? set.persistedRules.length : '未接入' }}</small></button></nav>
          <div v-if="selected" class="gov-detail-body"><div class="gov-rule-heading"><div><h3>{{ selected.label }}</h3><p>{{ selected.dataId || selected.message }}</p></div><span class="gov-badge" :class="{ positive: selected.applicationStatus === 'APPLIED' }">{{ status(selected.applicationStatus) }}</span></div>
            <p class="gov-note" :class="{ warning: selected.status === 'UNAVAILABLE' }">{{ selected.message }}</p>
            <template v-if="selected.supported"><div class="gov-rule-actions"><nav class="gov-local-tabs"><button :class="{ active: section === 'rules' }" @click="section = 'rules'"><Activity :size="15" />当前规则</button><button :class="{ active: section === 'history' }" @click="section = 'history'; manager.loadHistory()"><History :size="15" />发布审计</button></nav><button v-if="selected.editable" class="button primary" :disabled="busy || (selected.type === 'SYSTEM' && selected.persistedRules.length > 0)" @click="openEditor()"><Plus :size="15" />添加规则</button></div>
              <template v-if="section === 'rules'"><article v-for="(rule, index) in selected.persistedRules" :key="index" class="gov-rule-card"><span class="gov-rule-icon"><ShieldCheck :size="21" /></span><div><strong>{{ rule.resource || '全服务系统保护' }}</strong><p>{{ summary(rule) }}</p></div><div v-if="selected.editable" class="row-actions"><button class="icon-button" :aria-label="`编辑规则${index + 1}`" :disabled="busy" @click="openEditor(index)"><Pencil :size="16" /></button><button class="icon-button" :aria-label="`删除规则${index + 1}`" :disabled="busy" @click="removeRule(index)"><Trash2 :size="16" /></button></div></article><EmptyState v-if="!selected.persistedRules.length" :icon="ShieldCheck" :title="selected.status === 'UNAVAILABLE' ? '持久化规则暂不可读' : '当前没有该类型的持久化规则'" description="按实际业务容量配置，避免把未配置误认为异常。" /><details class="gov-runtime-code"><summary>查看客户端实际已加载规则（{{ selected.appliedRules.length }} 条）</summary><pre class="gov-code">{{ JSON.stringify(selected.appliedRules, null, 2) }}</pre></details></template>
              <template v-else><article v-for="change in history" :key="change.id" class="gov-history-card"><header><strong>发布 #{{ change.id }} · {{ change.action === 'ROLLBACK' ? '回退' : '规则变更' }}</strong><span class="gov-badge" :class="{ positive: change.status === 'APPLIED' }">{{ status(change.status) }}</span><button v-if="selected.editable && change.status === 'APPLIED'" class="button secondary small" :disabled="busy" @click="manager.prepare(selected, change.after, change.id)"><RotateCcw :size="14" />回退至此版本</button></header><p>{{ change.comment }}</p><small>{{ change.actor }} · {{ new Date(change.createdAt).toLocaleString('zh-CN') }}</small><p>{{ change.message }}</p><details><summary>查看变更前后</summary><div class="gov-diff"><pre class="gov-code">{{ JSON.stringify(change.before, null, 2) }}</pre><pre class="gov-code">{{ JSON.stringify(change.after, null, 2) }}</pre></div></details></article><EmptyState v-if="!history.length" :icon="History" title="暂无平台发布记录" description="从此入口发布的版本将保留操作者、原因、差异与应用结果。" /></template>
            </template>
          </div>
        </section>
      </template>
    </template>
    <BaseModal v-if="editing" :title="`${editingIndex < 0 ? '添加' : '编辑'}${selected?.label}`" description="表单只修改待发布草稿，下一步核对完整规则差异。" wide @close="!busy && (editing = false)"><form id="traffic-rule-editor" class="gov-rule-form" @submit.prevent="prepareEditor">
      <template v-if="selectedType !== 'SYSTEM'"><FormField label="资源"><select v-model="draft.resource"><option value="ops-rag-request">ops-rag-request · 完整问答</option><option v-if="selectedType === 'FLOW'" value="ops-rag-ask">ops-rag-ask · 入口校验</option></select></FormField><FormField :label="selectedType === 'FLOW' ? '限流维度' : '熔断策略'"><select v-model.number="draft.grade"><template v-if="selectedType === 'FLOW'"><option :value="1">QPS</option><option :value="0">并发线程数</option></template><template v-else><option :value="0">慢调用比例</option><option :value="1">异常比例</option><option :value="2">异常数</option></template></select></FormField><FormField :label="selectedType === 'DEGRADE' && Number(draft.grade) === 0 ? '慢调用耗时阈值（ms）' : '阈值'"><input v-model.number="draft.count" type="number" min="0" :max="selectedType === 'DEGRADE' && Number(draft.grade) === 1 ? 1 : 600000" step="any" required /></FormField></template>
      <template v-if="selectedType === 'FLOW'"><FormField label="流控模式"><select v-model.number="draft.strategy"><option :value="0">直接</option><option :value="1">关联资源</option></select></FormField><FormField v-if="Number(draft.strategy) === 1" label="关联资源"><select v-model="draft.refResource"><option value="ops-rag-ask">ops-rag-ask</option><option value="ops-rag-request">ops-rag-request</option></select></FormField><FormField label="超限行为"><select v-model.number="draft.controlBehavior"><option :value="0">快速失败</option><option :value="1" :disabled="Number(draft.grade) === 0">预热 Warm Up</option><option :value="2" :disabled="Number(draft.grade) === 0">排队等待</option></select></FormField><FormField v-if="Number(draft.controlBehavior) === 1" label="预热时长（秒）"><input v-model.number="draft.warmUpPeriodSec" type="number" min="1" max="3600" required /></FormField><FormField v-if="Number(draft.controlBehavior) === 2" label="最大排队时间（ms）"><input v-model.number="draft.maxQueueingTimeMs" type="number" min="0" max="30000" required /></FormField></template>
      <template v-else-if="selectedType === 'DEGRADE'"><FormField label="熔断窗口（秒）"><input v-model.number="draft.timeWindow" type="number" min="1" max="3600" required /></FormField><FormField label="最少请求数"><input v-model.number="draft.minRequestAmount" type="number" min="1" max="10000" required /></FormField><FormField label="统计窗口（ms）"><input v-model.number="draft.statIntervalMs" type="number" min="1000" max="600000" required /></FormField><FormField v-if="Number(draft.grade) === 0" label="慢调用比例阈值（0–1）"><input v-model.number="draft.slowRatioThreshold" type="number" min="0" max="1" step="0.01" required /></FormField></template>
      <template v-else><p class="gov-form-wide gov-note">系统保护作用于 RAG 的 IN 入口资源。-1 表示该项不限制。</p><FormField v-for="field in [{ key: 'highestSystemLoad', label: '系统负载' }, { key: 'highestCpuUsage', label: 'CPU使用率（0–1）' }, { key: 'avgRt', label: '平均RT（ms）' }, { key: 'maxThread', label: '最大并发' }, { key: 'qps', label: '入口QPS' }]" :key="field.key" :label="field.label"><input v-model.number="draft[field.key]" type="number" min="-1" :max="field.key === 'highestCpuUsage' ? 1 : 1000000" step="any" required /></FormField></template>
      <InlineError v-if="error" class="gov-form-wide" :message="error" />
    </form><template #footer><button class="button secondary" :disabled="busy" @click="editing = false">取消</button><button class="button primary" form="traffic-rule-editor" type="submit" :disabled="busy">{{ busy ? '正在校验…' : '校验并预览差异' }}</button></template></BaseModal>
    <BaseModal v-if="plan" :title="plan.rollbackVersionId ? '确认回退规则' : '确认发布规则'" description="核对完整规则快照，提交将通过Nacos CAS持久化并检查客户端应用。" wide @close="!busy && (plan = undefined)"><div class="gov-diff"><section><h3>发布前</h3><pre class="gov-code">{{ JSON.stringify(plan.before, null, 2) }}</pre></section><section><h3>发布后</h3><pre class="gov-code">{{ JSON.stringify(plan.after, null, 2) }}</pre></section></div><FormField label="变更原因" help="与操作者、版本及应用结果一起保存。"><textarea v-model="comment" rows="3" maxlength="500" :disabled="busy" placeholder="说明调整原因与预期影响…"></textarea></FormField><InlineError v-if="error" :message="error" /><template #footer><button class="button secondary" :disabled="busy" @click="plan = undefined">返回</button><button class="button primary" :disabled="busy || !comment.trim()" @click="manager.confirm(comment)">{{ busy ? '正在发布并核对…' : '确认发布' }}</button></template></BaseModal>
  </ObservabilityWorkspaceView>
</template>
