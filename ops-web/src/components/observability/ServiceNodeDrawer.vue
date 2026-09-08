<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Bot, Activity, Bell, FileCog, Gauge, Workflow, ExternalLink, Server, Zap, Timer, Database, Users, Boxes } from '@lucide/vue';
import { useRoute, useRouter } from 'vue-router';
import ServiceDetailShell from './ServiceDetailShell.vue';
import ObservationEvidence from './ObservationEvidence.vue';
import MetricSparkline from './MetricSparkline.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import ServiceHealthBadge from './ServiceHealthBadge.vue';
import { observabilityApi, type ServiceDetail, type ServiceNode, type ConfigSummary, type TrafficSummary, type ServiceMetricHistory } from '@/api/observability';
import { useAuthStore } from '@/stores/auth';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { ciType, environmentNames } from '@/components/cmdb/topology';
import { effectiveHealth, evidenceMetric, healthScopeLabels, metric, metricLabels, observationLabels, observationReason, observationState, observationTime, relationLabels, safeMetricUrl, serviceContext } from '@/utils/observability';
import { displayEndpoint } from '@/utils/service-endpoint';
import { serviceIcon } from '@/utils/observability-icons';
const props = defineProps<{ node: ServiceNode; environment: string; timeRange: string; inline?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const auth = useAuthStore(); const ai = useAiAssistantStore(); const route = useRoute(); const router = useRouter();
const detail = ref<ServiceDetail>(); const configSummary = ref<ConfigSummary>(); const trafficSummary = ref<TrafficSummary>(); const loading = ref(false); const error = ref(''); const tab = ref('overview');
const history = ref<ServiceMetricHistory>(); const historyError = ref('');
let epoch = 0; let timer: ReturnType<typeof setInterval> | undefined; let disposed = false;
const current = computed(() => detail.value?.node || props.node);
const isHost = computed(() => current.value.ciType?.toUpperCase() === 'HOST');
const hostMetrics = computed(() => ['hostCpuUsage', 'hostMemoryUsage', 'hostDiskUsage', 'hostNetworkReceiveRate', 'hostNetworkTransmitRate']
  .filter(key => Object.prototype.hasOwnProperty.call(current.value.metricEvidence || {}, key)));
const resourceScope = computed(() => isHost.value || hostMetrics.value.length ? '宿主操作系统资源 · 包含主机上的其他进程'
  : current.value.hostCiCode ? '当前 CPU 与内存指标为进程 / JVM；可在持续巡检查看关联主机资源'
    : '进程 CPU 与 JVM 堆内存独立于物理主机资源');
const context = computed(() => serviceContext(props.node.ciCode, props.environment, props.timeRange));
const tabs = [{ key: 'overview', label: '概览' }, { key: 'metrics', label: '指标' }, { key: 'alerts', label: '告警' }];
const nativeKeys = computed(() => {
  if (hostMetrics.value.length) return hostMetrics.value;
  const code = current.value.ciCode.toLowerCase();
  const preferred = code.includes('redis') ? ['redisClients', 'redisUsedMemory', 'redisPingSuccess']
    : code.includes('rabbit') ? ['rabbitmqQueueMessages', 'rabbitmqQueueConsumers', 'connections', 'consumers', 'messagesReady', 'messagesUnacked', 'rabbitmqQueueReadSuccess']
    : code.includes('mysql') ? ['mysqlConnections', 'mysqlMaxConnections', 'mysqlQuerySuccess']
    : code.includes('nacos') ? ['registeredServices', 'registeredInstances', 'configurationCount']
    : code.includes('elasticsearch') ? ['elasticNodes', 'elasticClusterStatus', 'elasticSearchSuccess']
    : code.includes('qdrant') ? ['collections', 'vectors', 'qdrantCollections', 'qdrantReady'] : [];
  const keys = Object.keys(current.value.metricEvidence || {}).filter(key => !['rps', 'errorRate', 'p95Ms', 'cpuUsage', 'memoryUsage'].includes(key));
  return preferred.length ? preferred.filter(key => keys.includes(key)) : keys;
});
const cardIcon = (key: string) => /rps|Qps/.test(key) ? Zap : /p95|latency/i.test(key) ? Timer : /clients|consumers|connections/i.test(key) ? Users : /memory|vectors|collections/i.test(key) ? Database : key === 'instances' ? Server : Activity;
const instances = computed(() => current.value.observation?.instances || []);
function instanceState(instance: NonNullable<ServiceNode['observation']>['instances'] extends (infer T)[] | undefined ? T : never) {
  const at = Date.parse(instance.sampledAt || '');
  if (!Number.isFinite(at) || at - Date.now() > 5000 || Date.now() - at > (current.value.observation?.maximumSampleAgeSeconds || 90) * 1000 || instance.up == null) return 'unknown';
  return instance.up === 1 ? 'up' : 'down';
}
const cards = computed(() => {
  const n = current.value; const m = n.metrics;
  if (isHost.value && !hostMetrics.value.length) return ['hostCpuUsage', 'hostMemoryUsage', 'hostDiskUsage', 'hostNetworkReceiveRate'].map(key => ({ key, label: metricLabels[key] || key, value: '—' }));
  if (nativeKeys.value.length) return nativeKeys.value.slice(0, 4).map(key => ({ key, label: metricLabels[key] || key, value: evidenceMetric(n, key) }));
  const value = (key: string, fallback: string) => n.metricEvidence ? evidenceMetric(n, key) : effectiveHealth(n) === 'UNKNOWN' ? '—' : fallback;
  return [{ key: 'rps', label: '请求速率', value: value('rps', metric(m?.rps, ' /s')) }, { key: 'errorRate', label: '错误率', value: value('errorRate', metric(m?.errorRate, '%', 2)) }, { key: 'p95Ms', label: 'P95 延迟', value: value('p95Ms', metric(m?.p95Ms, ' ms')) }, { key: 'instances', label: '可抓取实例', value: observationState(n) === 'STALE' || m?.totalInstances == null ? '—' : `${m.healthyInstances ?? '—'} / ${m.totalInstances}` }];
});
const metricLink = computed(() => safeMetricUrl(detail.value?.metricsUrl));
async function load() {
  const own = ++epoch; const code = props.node.ciCode; const token = auth.identity; loading.value = true; error.value = '';
  const result = await Promise.allSettled([observabilityApi.service(code, { environment: props.environment, timeRange: props.timeRange }), observabilityApi.configSummary(code), observabilityApi.trafficSummary(code), observabilityApi.metricHistory(code, { environment: props.environment, timeRange: props.timeRange })]);
  if (disposed || own !== epoch || token !== auth.identity) return;
  if (result[0].status === 'fulfilled' && result[0].value.node.ciCode === code) detail.value = result[0].value;
  else { detail.value = undefined; error.value = result[0].status === 'rejected' && result[0].reason instanceof Error ? result[0].reason.message : '服务详情与当前选择不匹配'; }
  configSummary.value = result[1].status === 'fulfilled' ? result[1].value : { status: 'UNAVAILABLE', message: '配置摘要暂不可用，请在配置中心重试' };
  trafficSummary.value = result[2].status === 'fulfilled' ? result[2].value : { status: 'UNAVAILABLE', message: '流量摘要暂不可用，请在流量治理重试' };
  history.value = result[3].status === 'fulfilled' ? result[3].value : undefined;
  historyError.value = result[3].status === 'rejected' ? '历史样本暂不可用' : '';
  loading.value = false;
}
function go(path: string, extra: Record<string, string> = {}) { emit('close'); void router.push({ path, query: { ...context.value, ...extra, from: route.path } }); }
function analyze() { emit('close'); ai.show({ service: props.node.ciCode, environment: props.environment, timeRange: props.timeRange }); if (!ai.question) ai.question = '请分析当前服务的异常现象、证据和可能原因，并说明还需要核对哪些信息。'; }
function title(row: Record<string, unknown>) { return String(row.title || row.summary || row.alertName || row.name || row.id || '关联记录'); }
function openRun(run: Record<string, unknown>) { if (run.source === 'DEMO_INCIDENT') go('/automation', { tab: 'experience', target: props.node.ciCode, incidentId: String(run.id) }); else go('/automation', { run: String(run.id), tab: 'runs' }); }
watch(() => [props.node.ciCode, props.environment, props.timeRange, auth.identity], () => { epoch++; detail.value = undefined; configSummary.value = undefined; trafficSummary.value = undefined; history.value = undefined; historyError.value = ''; tab.value = 'overview'; if (auth.identity) void load(); });
onMounted(() => { void load(); timer = setInterval(() => { if (!document.hidden && !loading.value) void load(); }, 15_000); });
onBeforeUnmount(() => { disposed = true; epoch++; clearInterval(timer); });
</script>
<template>
  <ServiceDetailShell :title="node.ciName" :subtitle="node.ciCode" :inline="inline" @close="emit('close')">
    <div class="obs-drawer-identity"><span class="obs-type-icon"><component :is="serviceIcon(node)" :size="24" /></span><div><strong>{{ ciType(node.ciType).label }} · {{ environmentNames[node.environment] || node.environment }}</strong><p>{{ current.statusReason || '等待现场状态' }}</p></div><ServiceHealthBadge :health="error ? 'UNKNOWN' : effectiveHealth(current)" /></div>
    <nav class="obs-detail-tabs" aria-label="服务详情"><button v-for="item in tabs" :key="item.key" :class="{ active: tab === item.key }" @click="tab = item.key">{{ item.label }}<span v-if="item.key === 'alerts'">{{ detail?.alertsAvailable === false ? '—' : detail?.node.activeAlertCount ?? '—' }}</span></button><button @click="analyze">AI 分析</button><select :value="['observation', 'traffic', 'config', 'relations'].includes(tab) ? tab : ''" aria-label="更多服务详情" @change="tab = ($event.target as HTMLSelectElement).value"><option value="" disabled>更多</option><option value="observation">采集诊断</option><option value="traffic">流量治理</option><option value="config">配置与变更</option><option value="relations">依赖关系</option></select></nav>
    <InlineError v-if="error" :message="error" /><LoadingState v-if="loading && !detail" text="读取服务现场证据…" />
    <div v-if="tab === 'overview'" class="obs-drawer-section">
      <dl class="oa-definition-list"><div v-if="current.systemName"><dt>所属系统</dt><dd>{{ current.systemName }}</dd></div><div><dt>负责人</dt><dd>{{ current.ownerName || '未登记' }}</dd></div></dl>
      <div><h3 class="obs-section-title">核心指标 <small>{{ timeRange }} · {{ healthScopeLabels[current.healthScope || ''] || '等待证据' }}</small></h3><div class="obs-metric-grid obs-visual-metrics"><article v-for="card in cards" :key="card.key"><span class="obs-metric-label"><component :is="cardIcon(card.key)" :size="14" />{{ card.label }}</span><strong>{{ card.value }}</strong><div v-if="card.key === 'instances'" class="obs-instance-bars" aria-label="各采集实例状态"><i v-for="instance in instances" :key="instance.instance" :data-state="instanceState(instance)" :title="`${instance.instance} · ${instanceState(instance) === 'up' ? '可抓取' : instanceState(instance) === 'down' ? '抓取失败' : '样本未知或过期'}`"></i><small v-if="!instances.length">实例样本未取得</small></div><MetricSparkline v-else :points="history?.series[card.key]" :from="history?.from" :to="history?.to" :label="card.label" /></article></div><small v-if="isHost || hostMetrics.length || current.metricEvidence?.cpuUsage || current.metricEvidence?.memoryUsage" class="obs-history-caption">{{ resourceScope }}</small><small class="obs-history-caption">{{ historyError || '趋势来自真实采集快照 · 空白表示缺少历史样本' }}</small><small v-if="nativeKeys.some(key => key.startsWith('rabbitmqQueue'))" class="obs-history-caption">仅代表已绑定队列的只读检查与消息状态。</small></div>
      <button class="obs-observation-summary" @click="tab = 'observation'"><strong>{{ observationLabels[observationState(current)] || '待观测' }} · 查看诊断 →</strong><p>{{ observationReason(current) }}</p><small>最近成功：{{ observationTime(current.observation?.lastSuccessfulScrapeAt) }}</small></button>
      <button v-if="current.activeAlertCount" class="obs-alert-short" @click="tab = 'alerts'"><Bell :size="16" />当前活动告警 {{ current.activeAlertCount }}<span>查看全部 →</span></button>
      <details class="obs-disclosure"><summary>实例、服务信息与关联记录</summary><div class="obs-disclosure-body"><div v-if="instances.length" class="obs-instance-list"><article v-for="instance in instances" :key="instance.instance" :data-state="instanceState(instance)"><Server :size="18" /><div><strong>{{ instance.instance }}</strong><small>{{ instanceState(instance) === 'up' ? '采集正常' : instanceState(instance) === 'down' ? '抓取失败' : '待观测' }} · {{ observationTime(instance.sampledAt) }}</small></div></article></div><dl class="oa-definition-list"><div><dt>地址</dt><dd><code>{{ displayEndpoint(current.endpoint) }}</code></dd></div><div><dt>服务说明</dt><dd>{{ current.description || '暂无说明' }}</dd></div><div><dt>生命周期</dt><dd>{{ current.lifecycle || '未登记' }}{{ current.overlays?.maintenance ? ' · 维护中' : '' }}{{ current.drilling ? ' · 演练中' : '' }}</dd></div></dl><button class="text-button" @click="tab = 'config'">配置与近期变更</button><button class="text-button" @click="tab = 'relations'">依赖关系</button><p v-if="!detail?.recentRuns.length" class="obs-muted">当前权限范围内暂无关联演练或运行。</p><button v-for="run in detail?.recentRuns" :key="String(run.id)" class="obs-record" @click="openRun(run)"><strong>{{ title(run) }}</strong><span>{{ run.status }}</span></button></div></details>
    </div>
    <div v-else-if="tab === 'metrics'" class="obs-drawer-section"><p v-if="isHost || hostMetrics.length || current.metricEvidence?.cpuUsage || current.metricEvidence?.memoryUsage" class="obs-muted">{{ resourceScope }}</p><div class="obs-metric-grid"><article v-for="(evidence, key) in current.metricEvidence || {}" :key="key"><span>{{ metricLabels[key] || key }}</span><strong>{{ evidenceMetric(current, key) }}</strong><small>{{ observationTime(evidence.sampledAt) }}<br />{{ evidence.value == null ? evidence.reasonCode : healthScopeLabels[evidence.scope] || evidence.scope }}</small></article></div><p class="obs-muted">{{ current.runtimeScopeMessage || '缺少有效样本显示“—”；采集成功与业务验证分别判断。' }}</p><a v-if="metricLink" class="button secondary" :href="metricLink" target="_blank" rel="noopener noreferrer">查看指标趋势<ExternalLink :size="15" /></a><button v-else class="button secondary" @click="go('/observability/metrics')">查看指标趋势与采集</button></div>
    <div v-else-if="tab === 'observation'" class="obs-drawer-section"><ObservationEvidence :node="current" /><button class="button secondary" @click="go('/observability/metrics')">核对指标与采集目标</button></div>
    <div v-else-if="tab === 'traffic'" class="obs-drawer-section"><div class="obs-metric-grid"><article><span>Sentinel 通过 QPS</span><strong>{{ metric(trafficSummary?.passQps, ' /s') }}</strong></article><article><span>Sentinel 阻断</span><strong>{{ metric(trafficSummary?.blockQps, ' /s') }}</strong></article><article><span>已观测资源</span><strong>{{ trafficSummary?.resourceCount ?? '—' }}</strong></article><article><span>生效规则</span><strong>{{ trafficSummary?.ruleCount ?? '—' }}</strong></article></div><p class="obs-muted">{{ trafficSummary?.message }} 规则由流量治理统一管理。当前绑定：{{ current.bindings?.sentinelApp || '未登记 Sentinel App' }}</p><button class="button primary" @click="go('/observability/traffic')"><Gauge :size="16" />查看流量与规则</button></div>
    <div v-else-if="tab === 'config'" class="obs-drawer-section"><div class="obs-metric-grid"><article><span>配置项</span><strong>{{ configSummary?.configurationCount ?? '—' }}</strong></article><article><span>受控可编辑项</span><strong>{{ configSummary?.editableCount ?? '—' }}</strong></article></div><div class="obs-source-note"><span v-for="source in configSummary?.sources" :key="source.source">{{ source.source }} · {{ source.count }} 项</span><span>{{ configSummary?.message }}</span></div><p class="obs-muted">配置绑定：{{ current.bindings?.nacosDataId || '未登记 Nacos Data ID' }}</p><div v-for="change in detail?.recentChanges" :key="String(change.id || change.revision)" class="obs-record"><strong>{{ title(change) }}</strong><small>{{ observationTime(String(change.occurredAt || change.createdAt || change.changedAt || '')) }} · {{ change.actor }} · {{ change.revision }}</small></div><EmptyState v-if="!detail?.recentChanges.length" title="暂无可见变更记录" description="在配置中心核对来源、版本和发布记录。" /><button class="button primary" @click="go('/observability/config')"><FileCog :size="16" />进入配置中心</button></div>
    <div v-else-if="tab === 'alerts'" class="obs-drawer-section"><p class="obs-muted">仅显示 {{ node.ciName }} 的当前上下文摘要，最多展示 5 条。</p><InlineError v-if="detail?.alertsAvailable === false" message="告警数据源暂不可用，无法确认当前活动告警。" /><article v-for="alert in detail?.alerts" :key="String(alert.id || alert.fingerprint)" class="obs-alert-record"><strong>{{ title(alert) }}</strong><p>{{ alert.summary || alert.description || alert.message || '' }}</p><div><span>{{ alert.severity || alert.status }}</span><button v-if="alert.ticketId" class="text-button" @click="go(`/tickets/${alert.ticketId}`)">查看关联事件</button></div></article><EmptyState v-if="detail && detail.alertsAvailable !== false && !detail.alerts.length" title="没有匹配的活动告警" description="告警来源的可用性请结合下方数据源状态确认。" /><button class="button secondary" @click="go('/itsm/alerts')"><Bell :size="16" />查看全部告警（保留服务筛选）</button></div>
    <div v-else class="obs-drawer-section"><article v-for="(edge, index) in detail?.relations" :key="edge.id || index" class="obs-relation-record"><strong>{{ edge.sourceCiCode }} → {{ edge.targetCiCode }}</strong><small>{{ relationLabels[edge.relationType] || edge.relationType }} · {{ edge.relationSource || '关系来源未提供' }}</small></article><EmptyState v-if="detail && !detail.relations.length" title="尚未登记依赖关系" description="管理员可以在编辑拓扑中维护有向依赖。" /></div>
    <div v-if="detail?.dataSources?.some(source => !source.healthy)" class="obs-source-note"><p v-for="source in detail.dataSources.filter(item => !item.healthy)" :key="source.name">{{ source.name }}：{{ source.message || '数据暂不可用' }}</p></div>
    <template #footer><div class="obs-drawer-actions"><button class="button primary" @click="analyze"><Bot :size="16" />AI 分析当前服务</button><button class="button secondary" @click="go('/automation')"><Workflow :size="16" />进入自动化</button><button class="button secondary" @click="go('/observability/inspections')"><Activity :size="16" />持续巡检</button></div></template>
  </ServiceDetailShell>
</template>
