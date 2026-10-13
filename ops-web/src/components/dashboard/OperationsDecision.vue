<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { ArrowUpRight, CheckCircle2, RefreshCw, ShieldCheck, TicketCheck, TriangleAlert } from '@lucide/vue';
import { request } from '@/api/http';
import { automationApi, type RunDetail, type RunRow } from '@/api/automation';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import { useAuthStore } from '@/stores/auth';

defineProps<{ eventCount: number; eventsKnown: boolean; eventsLoading: boolean; eventsStale: boolean }>();
interface AutomationSummary {
  scope: 'OWNER' | 'ADMIN'; totalRuns: number; statusCounts: Record<string, number>; activeRuns: number;
  pendingApprovals: number;
}
const auth = useAuthStore();
const approvals = useApprovalInboxStore();
const summary = ref<AutomationSummary>();
const summaryError = ref('');
const recentRuns = ref<RunRow[]>([]);
const details = ref<RunDetail[]>([]);
const sampleLoaded = ref(false);
const sampleError = ref('');
const missingDetails = ref(0);
const loading = ref(false);
const checkedAt = ref('');
let generation = 0;
let disposed = false;
const problemStatuses = ['NEEDS_ATTENTION', 'BUDGET_EXCEEDED', 'EXPIRED', 'REJECTED', 'FAILED'];
const statusLabels: Record<string, string> = { NEEDS_ATTENTION: '需要人工处理', BUDGET_EXCEEDED: '预算已耗尽',
  EXPIRED: '运行已过期', REJECTED: '审批被拒绝', FAILED: '运行失败' };
const scopeLabel = computed(() => summary.value?.scope === 'ADMIN' ? '管理员可见的全部运行' : '当前账号可见的全部运行');
const problemCount = computed(() => summary.value
  ? problemStatuses.reduce((count, status) => count + (summary.value!.statusCounts[status] || 0), 0) : undefined);
const strictRecoveries = computed(() => details.value.filter(run => run.status === 'COMPLETED'
  && run.state.ticketResolved === true && run.state.recoveryVerification?.resolved === true
  && ['RESOLVED', 'CLOSED'].includes(run.state.recoveryVerification.toStatus || '')));
const recentProblems = computed(() => recentRuns.value.filter(row =>
  problemStatuses.includes(details.value.find(run => run.id === row.id)?.status || row.status)));
const summaryReady = computed(() => !!summary.value && !summaryError.value);
const recoveryCount = computed(() => loading.value ? '核对中' : !sampleLoaded.value ? '未确认'
  : sampleError.value || missingDetails.value ? '部分待确认' : strictRecoveries.value.length);
const sampleScope = computed(() => sampleLoaded.value && !loading.value
  ? '最近 ' + recentRuns.value.length + ' 次可见运行；已读取 ' + details.value.length + ' 次详情'
  : loading.value ? '正在核对最近 10 次可见运行' : '最近运行样本尚未取得');
function runLink(id: string) { return { path: '/automation', query: { run: id } }; }
function runTicket(row: RunRow) { return row.ticketId || row.ticket_id || details.value.find(run => run.id === row.id)?.state.ticketId; }
function current(epoch: number, actor: unknown) { return !disposed && epoch === generation && actor === auth.user?.userId; }
async function load() {
  if (disposed || loading.value) return;
  const epoch = ++generation; const actor = auth.user?.userId;
  loading.value = true; summaryError.value = ''; sampleError.value = '';
  const [totals, rows] = await Promise.allSettled([
    request<AutomationSummary>({ url: '/api/automation/summary' }), automationApi.runs(1),
  ]);
  if (!current(epoch, actor)) return;
  if (totals.status === 'fulfilled') summary.value = totals.value;
  else summaryError.value = '运行与审批汇总未能更新，请刷新核对。';
  if (rows.status === 'fulfilled') {
    recentRuns.value = [...new Map(rows.value.items.map(row => [row.id, row])).values()].slice(0, 10);
    const fetched = await Promise.allSettled(recentRuns.value.map(row => automationApi.run(row.id)));
    if (!current(epoch, actor)) return;
    details.value = fetched.flatMap((result, index) => result.status === 'fulfilled' && result.value.id === recentRuns.value[index]?.id ? [result.value] : []);
    missingDetails.value = fetched.length - details.value.length;
    sampleLoaded.value = true;
    if (missingDetails.value) sampleError.value = missingDetails.value + ' 次运行详情尚未取得，恢复摘要不完整。';
  } else {
    sampleError.value = '最近运行列表未能更新，暂不能确认恢复数量。';
  }
  checkedAt.value = new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
  loading.value = false;
}
watch([() => auth.user?.userId, () => auth.isAdmin], () => {
  generation++; summary.value = undefined; recentRuns.value = []; details.value = []; sampleLoaded.value = false;
  missingDetails.value = 0; checkedAt.value = ''; loading.value = false;
  if (auth.user) void load();
}, { flush: 'sync' });
watch(() => approvals.decisionVersion, () => { void load(); });
onMounted(load);
onBeforeUnmount(() => { disposed = true; generation++; });
</script>

<template>
  <section class="operations-decision" aria-label="事件与执行摘要">
    <div class="decision-metrics">
      <RouterLink to="/tickets" class="decision-card">
        <span class="decision-icon"><TicketCheck :size="21" /></span><div><span>活跃事件</span><strong>{{ eventsKnown ? eventCount : eventsLoading ? '读取中' : '未确认' }}</strong><small>{{ eventsStale ? '上次事件数据 · 请刷新' : '当前可见事件队列' }}</small></div><ArrowUpRight :size="15" />
      </RouterLink>
      <button type="button" class="decision-card" @click="approvals.show()">
        <span class="decision-icon approval"><ShieldCheck :size="21" /></span><div><span>待处理审批</span><strong>{{ summaryReady ? summary!.pendingApprovals : loading ? '读取中' : '未确认' }}</strong><small>核对具体动作、参数与影响范围</small></div><ArrowUpRight :size="15" />
      </button>
      <RouterLink to="/automation" class="decision-card">
        <span class="decision-icon warning"><TriangleAlert :size="21" /></span><div><span>运行问题</span><strong>{{ summaryReady ? problemCount : loading ? '读取中' : '未确认' }}</strong><small>人工处理、预算耗尽、过期或拒绝等状态</small></div><ArrowUpRight :size="15" />
      </RouterLink>
      <RouterLink to="/automation" class="decision-card">
        <span class="decision-icon success"><CheckCircle2 :size="21" /></span><div><span>已验证恢复</span><strong>{{ recoveryCount }}</strong><small>仅核对最近 {{ recentRuns.length || 10 }} 次运行的恢复证据</small></div><ArrowUpRight :size="15" />
      </RouterLink>
    </div>
    <div class="decision-evidence">
      <div class="decision-evidence-heading"><div><h3>最近运行的处置与恢复</h3><p>{{ sampleScope }}。事件已解决、最新恢复验证通过且运行完成，才计入恢复。</p></div><button type="button" class="text-button" :disabled="loading" @click="load"><RefreshCw :size="15" />{{ loading ? '核对中…' : '刷新摘要' }}</button></div>
      <p v-if="summaryError || sampleError" class="decision-read-error" role="status">{{ summaryError }} {{ sampleError }}</p>
      <div v-if="!loading" class="decision-evidence-columns">
        <div><h4>运行问题 · 最近样本</h4><RouterLink v-for="row in recentProblems.slice(0, 3)" :key="row.id" :to="runLink(row.id)" class="decision-run"><span>{{ runTicket(row) ? '事件 #' + runTicket(row) : '运行 ' + row.id.slice(0, 8) }}</span><small>{{ statusLabels[details.find(run => run.id === row.id)?.status || row.status] || '需要核对' }}</small><ArrowUpRight :size="14" /></RouterLink><p v-if="sampleLoaded && !recentProblems.length">{{ sampleError ? '列表或证据未完整取得，请到自动化中心核对。' : '最近样本中暂无上述问题；全部运行见自动化中心。' }}</p><p v-else-if="!sampleLoaded">运行问题样本尚未取得。</p></div>
        <div><h4>已核实的恢复记录</h4><RouterLink v-for="run in strictRecoveries.slice(0, 3)" :key="run.id" :to="runLink(run.id)" class="decision-run"><span>事件 #{{ run.state.ticketId }}</span><small>最新恢复验证已通过</small><ArrowUpRight :size="14" /></RouterLink><p v-if="!strictRecoveries.length">{{ sampleLoaded && !sampleError ? '最近样本中尚无满足完整恢复条件的运行。' : '恢复证据待确认，不按零次恢复统计。' }}</p></div>
      </div>
      <p v-else>正在逐项读取本次样本的最新状态与恢复证据…</p>
      <footer><span>{{ summaryReady ? scopeLabel + '：共 ' + summary!.totalRuns + ' 次，其中 ' + summary!.activeRuns + ' 次仍占用运行额度（含暂停及等待）' : '全量运行范围尚未确认' }}</span><span v-if="checkedAt">最近核对 {{ checkedAt }}</span></footer>
    </div>
  </section>
</template>

<style scoped>
.operations-decision { display: grid; gap: 16px; min-width: 0; }
.decision-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.decision-card { display: grid; grid-template-columns: 38px minmax(0, 1fr) 15px; align-items: center; gap: 12px; min-width: 0; width: 100%; padding: 20px; border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); background: var(--oa-bg-surface); text-align: left; color: var(--oa-text-primary); font: inherit; }
.decision-card:hover { border-color: var(--oa-primary); }
.decision-card > div { display: grid; gap: 7px; min-width: 0; }
.decision-card > div > span { color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.decision-card strong { font-size: 28px; line-height: 1.3; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.decision-card small { font-size: var(--oa-font-size-xs); color: var(--oa-text-tertiary); line-height: 1.6; }
.decision-card > svg { color: var(--oa-text-tertiary); }
.decision-icon { width: 38px; height: 42px; display: grid; place-items: center; border-radius: 11px; color: var(--oa-primary); background: var(--oa-primary-soft); }
.decision-icon.approval, .decision-icon.warning { color: var(--oa-warning); background: var(--oa-warning-soft); }
.decision-icon.success { color: var(--oa-success); background: var(--oa-success-soft); }
.decision-evidence { padding: 20px 24px; background: var(--oa-bg-surface); border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); }
.decision-evidence-heading { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.decision-evidence h3 { margin: 0; font-size: var(--oa-font-size-body); }
.decision-evidence p, .decision-evidence footer { font-size: var(--oa-font-size-xs); line-height: 1.7; color: var(--oa-text-secondary); }
.decision-evidence-heading p { margin: 6px 0 0; }
.decision-evidence-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 28px; margin-top: 18px; }
.decision-evidence h4 { margin: 0 0 10px; font-size: var(--oa-font-size-sm); font-weight: 500; color: var(--oa-text-secondary); }
.decision-run { display: flex; align-items: center; gap: 10px; min-height: 36px; border-top: 1px solid var(--oa-border-subtle); font-size: var(--oa-font-size-sm); }
.decision-run > small { margin-left: auto; color: var(--oa-text-tertiary); font-size: var(--oa-font-size-xs); }
.decision-run:hover { color: var(--oa-primary); }
.decision-evidence footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px 16px; padding-top: 14px; margin-top: 16px; border-top: 1px solid var(--oa-border-subtle); color: var(--oa-text-tertiary); }
.decision-evidence .decision-read-error { color: var(--oa-warning); }
@media (max-width: 1350px) { .decision-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .decision-evidence-columns { grid-template-columns: minmax(0, 1fr); gap: 16px; } .decision-card { padding: 16px; gap: 9px; } .decision-evidence { padding: 18px; } .decision-card strong { font-size: 23px; } }
@media (max-width: 450px) { .decision-metrics { grid-template-columns: minmax(0, 1fr); } }
</style>
