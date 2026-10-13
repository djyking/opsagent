<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { RefreshCw, Server } from '@lucide/vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import MetricSparkline from './MetricSparkline.vue';
import { hostResourcesApi, type HostResourceSnapshot } from '@/api/host-resources';
import { hostMetricAvailable, hostMetricState, hostMetricValue, hostSummaryMetrics } from '@/utils/host-resources';
import { observationTime } from '@/utils/observability';
import { useAuthStore } from '@/stores/auth';

const props = defineProps<{ ciCode?: string }>();
const auth = useAuthStore();
const snapshot = ref<HostResourceSnapshot>();
const loading = ref(false); const error = ref(''); const now = ref(Date.now());
const detailsOpen = ref<Record<string, boolean>>({});
let epoch = 0; let disposed = false; let timer: ReturnType<typeof setInterval> | undefined;
async function load() {
  const own = ++epoch; const identity = auth.identity;
  if (auth.isDemo) { snapshot.value = undefined; loading.value = false; error.value = ''; return; }
  loading.value = true; error.value = '';
  try {
    const value = await hostResourcesApi.read(props.ciCode);
    if (!disposed && own === epoch && identity === auth.identity) { snapshot.value = value; now.value = Date.now(); }
  } catch (cause) {
    if (!disposed && own === epoch && identity === auth.identity) { snapshot.value = undefined; error.value = '主机资源暂时无法读取，请刷新后核对。'; }
  } finally { if (!disposed && own === epoch) loading.value = false; }
}
watch(() => [props.ciCode, auth.identity, auth.isDemo], () => { epoch++; snapshot.value = undefined; detailsOpen.value = {}; if (auth.identity) void load(); });
onMounted(() => { void load(); timer = setInterval(() => { now.value = Date.now(); if (!document.hidden && !loading.value && auth.identity) void load(); }, 15_000); });
onBeforeUnmount(() => { disposed = true; epoch++; clearInterval(timer); });
</script>

<template>
  <section v-if="!auth.isDemo" class="panel host-resource-panel" aria-label="主机资源">
    <header class="host-resource-header"><div><h3><Server :size="17" />主机资源</h3><p>{{ ciCode ? '当前服务关联的主机 · 主机资源由全部进程共用' : '宿主操作系统 · CPU、物理内存、磁盘与网卡' }}</p></div><button class="icon-button" aria-label="刷新主机资源" :disabled="loading" @click="load"><RefreshCw :size="16" /></button></header>
    <InlineError v-if="error" :message="error" />
    <LoadingState v-if="loading && !snapshot" text="读取主机资源…" />
    <p v-else-if="snapshot && !snapshot.hosts.length" class="host-resource-empty">{{ snapshot.message || '当前服务尚未关联已采集的主机。' }}</p>
    <article v-for="host in snapshot?.hosts || []" :key="host.ciCode" class="host-resource-host">
      <div class="host-resource-identity"><strong>{{ host.ciName }}</strong><span>{{ observationTime(host.observedAt || undefined) }}</span></div>
      <div class="host-resource-cards"><div v-for="card in hostSummaryMetrics(host, now)" :key="card.key" class="host-resource-card"><span>{{ card.label }}</span><template v-if="card.key === 'network'"><strong class="host-network-values"><span>↓ {{ hostMetricValue(card.metric, now) }}</span><span>↑ {{ hostMetricValue(card.transmit, now) }}</span></strong><small>接收 / 发送速率 · 下方为接收趋势</small></template><template v-else><strong>{{ hostMetricValue(card.metric, now) }}</strong><small>{{ card.key === 'disk' && hostMetricAvailable(card.metric, now) ? '已采集磁盘中使用率最高' : hostMetricState(card.metric, now) }}</small></template><MetricSparkline :points="card.metric?.points" :label="`${card.label}${card.key === 'network' ? '接收速率' : ''}`" /></div></div>
      <details class="host-resource-details" @toggle="detailsOpen[host.ciCode] = ($event.target as HTMLDetailsElement).open"><summary>查看近 60 分钟趋势、全部磁盘与网卡</summary><div v-if="detailsOpen[host.ciCode]" class="host-resource-detail-body"><p>曲线来自真实采样；空白表示历史不足或采集间断。没有链路速率依据时，不显示网络利用率。</p><div class="host-resource-metrics"><article v-for="metric in host.metrics" :key="`${metric.key}:${metric.dimension}`"><span>{{ metric.label }}{{ metric.dimension ? ` · ${metric.dimension}` : '' }}</span><strong>{{ hostMetricValue(metric, now) }}</strong><small>{{ hostMetricState(metric, now) }} · {{ observationTime(metric.sampledAt || undefined) }}</small><MetricSparkline v-if="metric.unit !== 'boolean'" :points="metric.points" :label="`${metric.label} ${metric.dimension}`" /></article></div><p>采集来源：{{ host.source }} · 主机范围：{{ host.ciCode }} / {{ host.environment }}</p><p v-if="host.services.length">关联服务：{{ host.services.map(service => service.ciName).join('、') }}</p></div></details>
    </article>
  </section>
</template>

<style scoped>
.host-resource-header { display:flex; justify-content:space-between; align-items:center; padding:16px 20px; gap:16px; }
.host-resource-header h3 { display:flex; gap:8px; align-items:center; margin:0; color:var(--oa-text-primary); font-size:var(--oa-font-size-section); line-height:var(--oa-line-height-section); font-weight:500; }
.host-resource-header h3 svg { color:var(--oa-primary); }
.host-resource-header p,.host-resource-empty { margin:4px 0 0; color:var(--oa-text-secondary); font-size:var(--oa-font-size-sm); line-height:var(--oa-line-height-sm); }
.host-resource-empty { padding:0 20px 20px; }
.host-resource-identity { display:flex; justify-content:space-between; gap:12px; padding:0 20px 12px; font-size:var(--oa-font-size-sm); color:var(--oa-text-tertiary); }
.host-resource-identity strong { color:var(--oa-text-secondary); font-weight:400; }
.host-resource-cards { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:12px; padding:0 20px 16px; }
.host-resource-card { min-width:0; padding:14px; background:var(--oa-bg-subtle); border-radius:var(--oa-radius-control); }
.host-resource-card > span,.host-resource-metrics article > span { display:block; font-size:var(--oa-font-size-sm); color:var(--oa-text-secondary); }
.host-resource-card > strong { display:block; margin:8px 0 6px; font-size:var(--oa-font-size-page); line-height:var(--oa-line-height-page); color:var(--oa-text-primary); font-weight:600; }
.host-resource-card small,.host-resource-metrics small { display:block; color:var(--oa-text-tertiary); font-size:var(--oa-font-size-xs); line-height:var(--oa-line-height-xs); }
.host-resource-card > .host-network-values { display:flex; flex-wrap:wrap; gap:4px 12px; font-size:var(--oa-font-size-section); }
.host-resource-details { padding:12px 20px; border-top:1px solid var(--oa-border-subtle); color:var(--oa-text-secondary); font-size:var(--oa-font-size-sm); line-height:var(--oa-line-height-body); }
.host-resource-details > summary { cursor:pointer; }
.host-resource-detail-body > p { margin:12px 0; }
.host-resource-metrics { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; }
.host-resource-metrics article { min-width:0; padding:12px; border:1px solid var(--oa-border-subtle); border-radius:var(--oa-radius-control); }
.host-resource-metrics strong { display:block; padding:8px 0; color:var(--oa-text-primary); font-size:var(--oa-font-size-section); font-weight:500; }
@media(max-width:1100px) { .host-resource-cards,.host-resource-metrics { grid-template-columns:repeat(2,minmax(0,1fr)); } }
@media(max-width:560px) { .host-resource-cards,.host-resource-metrics { grid-template-columns:1fr; }.host-resource-identity { flex-wrap:wrap; } }
</style>
