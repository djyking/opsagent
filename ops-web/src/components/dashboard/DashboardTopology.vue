<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Activity, ArrowUpRight, RefreshCw, ShieldCheck } from '@lucide/vue';
import ServiceTopology from '@/components/observability/ServiceTopology.vue';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';
import { observabilityV3Api, type TopologyV3Snapshot } from '@/api/observabilityV3';
import { useAuthStore } from '@/stores/auth';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import { effectiveHealth, observationTime, serviceContext } from '@/utils/observability';
import { neighborhood } from '@/utils/topology-view';
import { environmentNames } from '@/components/cmdb/topology';
defineProps<{ priorityCount?: number; verificationCount?: number; eventsStale?: boolean }>();
const auth = useAuthStore(); const approvals = useApprovalInboxStore(); const route = useRoute(); const router = useRouter();
const snapshot = ref<TopologyV3Snapshot>(); const loading = ref(false); const error = ref('');
const environment = ref(String(route.query.environment || 'ALL')); const timeRange = ref(String(route.query.timeRange || '15m'));
let generation = 0; let timer: ReturnType<typeof setInterval> | undefined; let disposed = false;
const coverage = computed(() => snapshot.value?.coverage);
const abnormal = computed(() => snapshot.value?.nodes.filter(node => ['CRITICAL', 'DEGRADED'].includes(effectiveHealth(node))) || []);
const preview = computed(() => {
  const nodes = snapshot.value?.nodes || []; const edges = snapshot.value?.edges || [];
  const priorityCodes = new Set(abnormal.value.map(node => node.ciCode));
  for (const edge of edges) if (priorityCodes.has(edge.sourceCiCode) || priorityCodes.has(edge.targetCiCode)) { priorityCodes.add(edge.sourceCiCode); priorityCodes.add(edge.targetCiCode); }
  const sorted = [...nodes].sort((a, b) => Number(priorityCodes.has(b.ciCode)) - Number(priorityCodes.has(a.ciCode)));
  const scoped = sorted.slice(0, 12); const codes = new Set(scoped.map(node => node.ciCode));
  return neighborhood(scoped, edges.filter(edge => codes.has(edge.sourceCiCode) && codes.has(edge.targetCiCode)), '', 0, false);
});
const context = computed(() => serviceContext('', environment.value, timeRange.value));
const coverageText = computed(() => coverage.value?.ratio == null ? '未取得' : `${(coverage.value.ratio * 100).toFixed(0)}%`);
function openNode(code: string) { void router.push({ path: '/observability/topology', query: { ...context.value, ciCode: code } }); }
async function load() {
  const epoch = ++generation; const actor = auth.token; loading.value = true; error.value = '';
  try { const result = await observabilityV3Api.topology({ environment: environment.value, timeRange: timeRange.value, mode: 'HYBRID' });
    if (disposed || epoch !== generation || actor !== auth.token) return; snapshot.value = result;
  } catch (cause) { if (!disposed && epoch === generation) { snapshot.value = undefined; error.value = cause instanceof Error ? cause.message : '服务态势读取失败'; } }
  finally { if (epoch === generation) loading.value = false; }
}
watch([environment, timeRange, () => auth.token], () => { generation++; snapshot.value = undefined; if (auth.token) void load(); });
onMounted(() => { void load(); timer = setInterval(() => { if (!document.hidden && !loading.value) void load(); }, 15_000); });
onBeforeUnmount(() => { disposed = true; generation++; clearInterval(timer); });
</script>
<template>
  <section class="dashboard-service-situation" aria-label="服务态势与优先行动">
    <div class="dashboard-situation-metrics">
      <RouterLink :to="{ path: '/observability/topology', query: context }"><span>服务观测覆盖</span><strong>{{ coverageText }}</strong><small>{{ coverage ? `${coverage.ready + coverage.partial}/${coverage.eligible} 个应观测对象已接入；${coverage.partial} 个仅部分接入` : '覆盖分母未返回，不以健康节点代替' }}</small></RouterLink>
      <RouterLink :to="{ path: '/observability/topology', query: context }"><span>异常服务</span><strong>{{ snapshot ? abnormal.length : '未确认' }}</strong><small>健康依据各服务证据范围；未知不计正常</small></RouterLink>
      <RouterLink to="/tickets"><span>未处置 P1 / P2</span><strong>{{ priorityCount ?? '未确认' }}</strong><small>{{ eventsStale ? '上次事件数据 · 刷新后核对' : '当前账号可见的高优先级活跃事件' }}</small></RouterLink>
      <button type="button" @click="approvals.show()"><span><ShieldCheck :size="14" />待审批 / 待验证</span><strong>{{ approvals.error ? '未确认' : approvals.count }} <i>/</i> {{ verificationCount ?? '—' }}</strong><small>点击核对授权动作 · 待验证为待业务确认事件</small></button>
    </div>
    <article class="panel dashboard-topology-panel">
      <header class="panel-header"><div><h3><Activity :size="18" />服务运行态势</h3><p>异常及关联服务优先 · 摘要 {{ preview.nodes.length }}/{{ snapshot?.nodes.length ?? '—' }} 个节点</p></div><div class="dashboard-topology-actions"><label>环境<select v-model="environment"><option value="ALL">全部环境</option><option v-for="(label, key) in environmentNames" :key="key" :value="key">{{ label }}</option></select></label><button class="icon-button" type="button" :disabled="loading" aria-label="刷新服务态势" @click="load"><RefreshCw :size="15" /></button><RouterLink class="text-button" :to="{ path: '/observability/topology', query: context }">完整工作台<ArrowUpRight :size="15" /></RouterLink></div></header>
      <p v-if="error" class="inline-error" role="status">{{ error }}</p><LoadingState v-if="loading && !snapshot" text="读取真实服务状态与关系…" />
      <ServiceTopology v-else-if="preview.nodes.length" :nodes="preview.nodes" :edges="preview.edges" compact :storage-key="`${auth.user?.userId}:${environment}:dashboard:v3`" @select="openNode" @error="error = $event" />
      <EmptyState v-else title="服务观测数据尚未取得" description="请检查采集接入；下方真实事件和协作入口仍可使用。" />
      <footer><span>{{ !snapshot ? '无法确认当前服务状态' : abnormal.length ? `${abnormal.length} 个服务有异常证据，请结合具体范围诊断。` : coverage && coverage.ready === coverage.eligible && coverage.eligible ? '已观测范围内未发现异常，业务验证以独立探针为准。' : '已观测范围内未发现异常，仍有对象未完整接入。' }}</span><small>{{ observationTime(snapshot?.checkedAt) }} · 此摘要不捕获页面滚轮</small></footer>
    </article>
  </section>
</template>
<style scoped>
.dashboard-service-situation { display: grid; gap: 16px; min-width: 0; }
.dashboard-situation-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.dashboard-situation-metrics > a, .dashboard-situation-metrics > button { display: grid; align-content: start; gap: 8px; padding: 18px 20px; border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); background: var(--oa-bg-surface); color: var(--oa-text-primary); text-align: left; font: inherit; }
.dashboard-situation-metrics span { display: flex; align-items: center; gap: 6px; font-size: var(--oa-font-size-sm); color: var(--oa-text-secondary); }
.dashboard-situation-metrics strong { font-size: 27px; font-weight: 600; font-variant-numeric: tabular-nums; }
.dashboard-situation-metrics strong i { font-style: normal; color: var(--oa-text-muted); font-size: 19px; }
.dashboard-situation-metrics small { font-size: var(--oa-font-size-xs); line-height: 1.6; color: var(--oa-text-tertiary); }
.dashboard-topology-panel { overflow: hidden; }
.dashboard-topology-panel h3 { display: flex; gap: 8px; align-items: center; }
.dashboard-topology-actions { display: flex; gap: 13px; align-items: center; flex-wrap: wrap; }
.dashboard-topology-actions label { display: flex; gap: 8px; align-items: center; color: var(--oa-text-secondary); font-size: var(--oa-font-size-xs); }
.dashboard-topology-actions select { width: auto; min-height: 32px; padding: 3px 26px 3px 9px; }
.dashboard-topology-panel footer { display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 12px 20px; border-top: 1px solid var(--oa-border-subtle); font-size: var(--oa-font-size-xs); color: var(--oa-text-secondary); line-height: 1.7; }
@media (max-width: 1100px) { .dashboard-situation-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 580px) { .dashboard-situation-metrics > a, .dashboard-situation-metrics > button { padding: 13px; } .dashboard-situation-metrics strong { font-size: 23px; } }
</style>
