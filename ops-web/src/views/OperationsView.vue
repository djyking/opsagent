<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Activity, ArrowRight, ArrowUpRight, Clock3,
  Database, GitBranch, Info, Network, RefreshCw, SearchCheck,
  Server, ShieldCheck, SlidersHorizontal, Sparkles, TrendingUp, TriangleAlert } from "@lucide/vue";
import { operationsApi, type OperationsMetric, type OperationsOverview, type OperationsWorkflow } from "@/api/operations";
import PageHeader from "@/components/PageHeader.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import EmptyState from "@/components/EmptyState.vue";
import CmdbView from "@/views/CmdbView.vue";
import "@/styles/pages/operations.css";

const route = useRoute();
const router = useRouter();
const tabs = [
  { key: "overview", label: "运行态势", icon: Activity },
  { key: "topology", label: "服务与拓扑", icon: Network },
  { key: "governance", label: "注册与流控", icon: SlidersHorizontal },
];
const activeTab = computed(() => tabs.some(tab => tab.key === route.query.tab) ? String(route.query.tab) : "overview");
const snapshot = ref<OperationsOverview>();
const workflows = ref<OperationsWorkflow[]>([]);
const windowMinutes = ref(60);
const metricKind = ref("heap");
const serviceFilter = ref("");
const error = ref("");
const loading = ref(false);
const autoRefresh = ref(false);
let refreshTimer: number | undefined;
let disposed = false;
const serviceNames: Record<string, string> = {
  "opsagent-gateway": "统一网关", "opsagent-auth": "认证服务", "opsagent-ticket": "工单服务",
  "opsagent-knowledge": "知识服务", "opsagent-rag": "AI问答服务", "opsagent-platform": "平台服务",
  "opsagent-agent": "Agent 执行服务", "opsagent-demo-order": "隔离订单服务", all: "指标采集范围",
};
const statusNames: Record<string, string> = {
  HEALTHY: "观测正常", ATTENTION: "需要关注", UNKNOWN: "数据待确认", AVAILABLE: "已连接",
  PARTIAL: "部分可用", UNAVAILABLE: "暂不可用", DISABLED: "未启用", SUCCEEDED: "已完成",
  FAILED: "未完成", RUNNING: "执行中", OK: "观测正常", RISK: "风险提示",
};
const upCount = computed(() => snapshot.value?.targets.filter(target => target.health === "up").length ?? 0);
const riskCount = computed(() => snapshot.value?.risks.filter(risk => risk.severity !== "INFO").length ?? 0);
const sampleCount = computed(() => snapshot.value?.metrics.reduce((sum, metric) => sum + metric.sampleCount, 0) ?? 0);
const visibleMetrics = computed(() => snapshot.value?.metrics.filter(metric => metric.id === metricKind.value
  && (!serviceFilter.value || metric.job === serviceFilter.value || metric.job === "all")) ?? []);
const activeInspection = computed(() => workflows.value.find(workflow => workflow.code === "HEALTH_CHECK"));

function setTab(tab: string) { void router.replace({ query: { ...route.query, tab } }); }
function label(value: string) { return statusNames[value] || value; }
function serviceName(value: string) { return serviceNames[value] || value; }
function value(value: number | null | undefined, suffix = "") { return value == null ? "—" : `${value.toFixed(1)}${suffix}`; }
function count(value: number | null | undefined) { return value == null ? "未知" : value.toLocaleString("zh-CN"); }
function date(value: string | null | undefined, timeOnly = false) {
  if (!value) return "尚无记录";
  const numeric = /^\d{11,}$/.test(value) ? Number(value) : value;
  const parsed = new Date(numeric);
  if (!Number.isFinite(parsed.getTime())) return "未知";
  return timeOnly ? parsed.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" })
    : parsed.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" });
}
function tone(status: string) {
  return ["HEALTHY", "AVAILABLE", "SUCCEEDED", "OK", "up"].includes(status) ? "good"
    : ["ATTENTION", "RISK", "PARTIAL"].includes(status) ? "warning"
      : ["FAILED", "down"].includes(status) ? "danger" : "muted";
}
function chartMax(metric: OperationsMetric) {
  return metric.id === "heap" ? 100 : Math.max(5, Math.ceil(Math.max(...metric.points.map(point => point.value), 0) * 1.2));
}
function chartPoints(metric: OperationsMetric) {
  if (!metric.points.length) return "";
  const first = new Date(metric.points[0]!.timestamp).getTime();
  const last = new Date(metric.points[metric.points.length - 1]!.timestamp).getTime();
  return metric.points.map(point => `${8 + (new Date(point.timestamp).getTime() - first) / Math.max(1, last - first) * 344},${108 - Math.max(0, Math.min(1, point.value / chartMax(metric))) * 94}`).join(" ");
}
function thresholdY(metric: OperationsMetric) { return 108 - (metric.id === "heap" ? 85 : 5) / chartMax(metric) * 94; }

async function loadSnapshot() {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  try { const result = await operationsApi.overview(windowMinutes.value); if (!disposed) snapshot.value = result; }
  catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : "运行数据暂时无法读取"; }
  finally { loading.value = false; }
}
async function loadWorkflows() {
  try {
    const result = await operationsApi.workflows();
    if (!disposed) workflows.value = result.filter(item => item.code === "HEALTH_CHECK");
  } catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : "巡检配置读取失败"; }
}
watch(() => route.query.tab, (tab) => {
  if (tab === "workflows") void router.replace({
    path: "/automation", query: { ...route.query, tab: "inspection" }, hash: route.hash,
  });
}, { immediate: true });
watch(windowMinutes, () => { void loadSnapshot(); });
watch(autoRefresh, (enabled) => {
  if (refreshTimer != null) window.clearInterval(refreshTimer);
  refreshTimer = enabled ? window.setInterval(() => { if (!document.hidden) void loadSnapshot(); }, 60_000) : undefined;
});
onMounted(() => { if (route.query.tab !== "workflows") void Promise.all([loadSnapshot(), loadWorkflows()]); });
onBeforeUnmount(() => { disposed = true; if (refreshTimer != null) window.clearInterval(refreshTimer); });
</script>

<template>
  <div class="stack-page operations-page">
    <PageHeader :icon="Activity" title="服务与观测" description="查看服务运行证据、依赖关系与治理状态，连接告警和配置">
      <template #actions>
        <label class="operations-auto"><input v-model="autoRefresh" type="checkbox" />每分钟刷新</label>
        <button class="button secondary" :disabled="loading" @click="loadSnapshot"><RefreshCw :size="15" :class="{ 'motion-spin': loading }" />{{ loading ? '读取中…' : '刷新状态' }}</button>
      </template>
    </PageHeader>
    <nav class="operations-tabs" aria-label="服务与观测视图">
      <button v-for="tab in tabs" :key="tab.key" :class="{ active: activeTab === tab.key }" :aria-current="activeTab === tab.key ? 'page' : undefined" @click="setTab(tab.key)"><component :is="tab.icon" :size="17" />{{ tab.label }}</button>
      <RouterLink to="/automation?tab=inspection"><GitBranch :size="17" />持续巡检<ArrowUpRight :size="13" /></RouterLink>
      <RouterLink to="/itsm/alerts"><TriangleAlert :size="17" />活动告警<ArrowUpRight :size="13" /></RouterLink>
      <RouterLink to="/configuration"><SlidersHorizontal :size="17" />配置中心<ArrowUpRight :size="13" /></RouterLink>
      <span v-if="snapshot" class="operations-snapshot-time"><Clock3 :size="13" />快照 {{ date(snapshot.capturedAt, true) }}</span>
    </nav>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />

    <template v-if="activeTab === 'overview'">
      <LoadingState v-if="loading && !snapshot" text="正在读取真实指标、注册信息与治理状态…" />
      <template v-else-if="snapshot">
        <section class="operations-intro" :data-tone="tone(snapshot.status)">
          <div class="operations-intro-copy"><span class="operations-kicker"><i />运行证据快照</span><h3>{{ riskCount ? `${riskCount} 项运行风险值得关注` : snapshot.status === 'HEALTHY' ? `${upCount} 个服务可观测，未发现越限` : '运行证据尚不完整，需要核实' }}</h3><p>{{ snapshot.summary }}</p></div>
          <RouterLink to="/rag/chat" class="button secondary"><Sparkles :size="16" />结合证据问 AI <ArrowUpRight :size="14" /></RouterLink>
        </section>
        <div class="operations-summary-grid">
          <article><span><Server :size="16" />可抓取服务</span><strong>{{ upCount }}<small> / {{ snapshot.targets.length || '—' }}</small></strong><p>Prometheus最近一次抓取</p></article>
          <article><span><TrendingUp :size="16" />窗口内样本</span><strong>{{ sampleCount.toLocaleString('zh-CN') }}</strong><p>真实样本 · 最近{{ snapshot.windowMinutes }}分钟</p></article>
          <article><span><TriangleAlert :size="16" />待确认风险</span><strong>{{ riskCount }}</strong><p>阈值与线性趋势规则</p></article>
          <article><span><SearchCheck :size="16" />持续巡检</span><strong class="operations-text-value">{{ activeInspection?.scheduleEnabled ? `每${activeInspection.intervalMinutes}分钟` : '手动执行' }}</strong><p>{{ activeInspection?.scheduleEnabled ? '只读采集，不调用付费AI' : '在自动化中心查看执行能力' }}</p></article>
        </div>
        <section class="operations-section panel">
          <header class="operations-section-header"><div><h3>服务运行切面</h3><p>采集状态与CMDB归属关联，点击查看完整服务依赖。</p></div><button class="text-button" @click="setTab('topology')">查看依赖 <ArrowRight :size="14" /></button></header>
          <div v-if="snapshot.targets.length" class="operations-target-grid">
            <button v-for="target in snapshot.targets" :key="target.service" class="operations-target" @click="setTab('topology')"><span class="operations-target-icon" :data-tone="tone(target.health)"><Server :size="18" /></span><span><strong>{{ serviceName(target.service) }}</strong><small>{{ target.ciCode ? '已关联服务目录' : '尚未关联配置项' }}</small></span><i class="operations-dot" :data-tone="tone(target.health)" :title="target.health === 'up' ? '可抓取' : target.health === 'down' ? '抓取异常' : '状态未知'" /><span class="sr-only">{{ target.health }}</span></button>
          </div>
          <EmptyState v-else compact :icon="Server" title="尚未取得服务采集结果" description="请检查Prometheus连接和目标配置；这里没有使用演示数据替代。" />
        </section>
        <div class="operations-evidence-grid">
          <section class="operations-trends">
            <header class="operations-section-header"><div><h3>趋势与风险预估</h3><p>15分钟线性外推，保留采样时间和不确定性。</p></div><label class="operations-select-label"><span>观察窗口</span><select v-model.number="windowMinutes" :disabled="loading"><option :value="30">30分钟</option><option :value="60">1小时</option><option :value="180">3小时</option><option :value="360">6小时</option></select></label></header>
            <div class="operations-chart-controls"><div class="operations-segment"><button :class="{ active: metricKind === 'heap' }" @click="metricKind = 'heap'">JVM堆</button><button :class="{ active: metricKind === 'http5xx' }" @click="metricKind = 'http5xx'">HTTP错误</button></div><select v-model="serviceFilter" aria-label="按服务筛选指标"><option value="">全部服务</option><option v-for="target in snapshot.targets" :key="target.service" :value="target.service">{{ serviceName(target.service) }}</option></select></div>
            <div class="operations-chart-grid">
              <article v-for="metric in visibleMetrics" :key="`${metric.id}-${metric.job}`" class="operations-chart-card" :data-tone="tone(metric.status)">
                <header><div><strong>{{ serviceName(metric.job) }}</strong><small>{{ metric.label }}</small></div><span class="operations-pill" :data-tone="tone(metric.status)">{{ label(metric.status) }}</span></header>
                <div class="operations-chart-numbers"><strong>{{ value(metric.currentValue, '%') }}</strong><span>15分钟预估 <b>{{ value(metric.forecastValue, '%') }}</b></span></div>
                <div v-if="metric.points.length > 1" class="operations-chart" :aria-label="`${metric.label}，${metric.sampleCount}个样本，当前${value(metric.currentValue, '%')}`" role="img">
                  <span class="operations-chart-axis">{{ chartMax(metric) }}%</span>
                  <svg viewBox="0 0 360 120" preserveAspectRatio="none" aria-hidden="true"><line x1="8" y1="108" x2="352" y2="108" class="operations-chart-base" /><line x1="8" :y1="thresholdY(metric)" x2="352" :y2="thresholdY(metric)" class="operations-chart-threshold" /><polyline :points="chartPoints(metric)" class="operations-chart-line" /></svg>
                  <span class="operations-chart-zero">0</span>
                </div>
                <div v-else class="operations-chart-empty"><Activity :size="22" /><span>等待足够的真实样本</span></div>
                <footer><span>{{ metric.sampleCount }}个样本</span><span>{{ metric.observedAt ? `采样 ${date(metric.observedAt, true)}` : '暂无采样时间' }}</span></footer>
                <p class="operations-chart-reason">{{ metric.reason }}</p>
                <details class="operations-method"><summary>分析方法与边界</summary><p>{{ metric.method }}</p><p>虚线为{{ metric.id === 'heap' ? '85%堆使用率' : '5%错误比例' }}关注阈值。{{ metric.id === 'heap' ? 'JVM堆不等于容器或宿主机内存。' : '有效请求与正常抓取下，没有5xx计数代表0%；没有请求基数时显示未知。' }}</p></details>
              </article>
            </div>
            <EmptyState v-if="!visibleMetrics.length" compact :icon="Activity" title="该服务没有当前指标" description="可切换服务或检查指标采集范围。" />
          </section>
          <aside class="operations-risk-panel panel"><header><span class="operations-kicker">风险证据</span><h3>把原因放在结论旁边</h3><p>规则提示提供排查起点，AI可以结合实时上下文进一步解释。</p></header><article v-for="risk in snapshot.risks" :key="risk.key" class="operations-risk" :data-tone="risk.severity === 'CRITICAL' ? 'danger' : risk.severity === 'WARNING' ? 'warning' : 'muted'"><span class="operations-risk-symbol"><TriangleAlert v-if="risk.severity !== 'INFO'" :size="17" /><Info v-else :size="17" /></span><div><h4>{{ risk.title }}</h4><p>{{ risk.detail }}</p><small>{{ risk.evidence }}</small><details><summary>建议下一步</summary><p>{{ risk.recommendation }}</p></details></div></article></aside>
        </div>
      </template>
      <EmptyState v-else :icon="Activity" title="运行数据尚未就绪" description="可以刷新状态重试；无法采集时不会生成替代数字。" />
    </template>

    <CmdbView v-else-if="activeTab === 'topology'" embedded />

    <template v-else-if="activeTab === 'governance'">
      <LoadingState v-if="loading && !snapshot" text="正在读取治理元数据…" />
      <div v-else-if="snapshot" class="operations-governance-grid">
        <section class="panel operations-governance-card"><header><span class="operations-feature-icon"><Database :size="22" /></span><div><h3>Nacos注册与配置</h3><p>只读元数据 · 配置内容留在服务端</p></div><span class="operations-pill" :data-tone="tone(snapshot.nacos.status)">{{ label(snapshot.nacos.status) }}</span></header><p class="operations-governance-description">{{ snapshot.nacos.message }}</p><div class="operations-mini-metrics"><div><span>注册服务</span><strong>{{ count(snapshot.nacos.serviceCount) }}</strong></div><div><span>健康实例</span><strong>{{ count(snapshot.nacos.healthyInstanceCount) }}</strong></div><div><span>配置记录</span><strong>{{ count(snapshot.nacos.configurationCount) }}</strong></div></div>
          <div class="operations-inner-heading"><h4>注册服务</h4><span>来自Nacos目录接口</span></div><div v-if="snapshot.nacos.services.length" class="operations-governance-rows"><article v-for="service in snapshot.nacos.services" :key="service.name"><span><Server :size="16" /><strong>{{ service.name }}</strong></span><small>{{ count(service.healthyInstanceCount) }} / {{ count(service.instanceCount) }} 健康实例</small></article></div><EmptyState v-else compact :icon="Server" title="暂无可确认的注册信息" />
          <div class="operations-inner-heading"><h4>配置元信息</h4><span>名称与分组</span></div><div v-if="snapshot.nacos.configurations.length" class="operations-config-list"><article v-for="config in snapshot.nacos.configurations" :key="`${config.group}-${config.dataId}`"><strong>{{ config.dataId }}</strong><span>{{ config.group }}</span><small v-if="config.modifiedAt">{{ date(config.modifiedAt) }}</small></article></div><EmptyState v-else compact :icon="Database" title="暂无可读取的配置元信息" description="可能没有匹配配置，或只读访问尚未就绪。" />
        </section>
        <section class="panel operations-governance-card"><header><span class="operations-feature-icon purple"><ShieldCheck :size="22" /></span><div><h3>Sentinel流量治理</h3><p>读取RAG入口当前生效规则</p></div><span class="operations-pill" :data-tone="tone(snapshot.sentinel.status)">{{ label(snapshot.sentinel.status) }}</span></header><p class="operations-governance-description">{{ snapshot.sentinel.message }}</p><div class="operations-mini-metrics"><div><span>已通过请求</span><strong>{{ count(snapshot.sentinel.passedTotal) }}</strong></div><div><span>已拦截请求</span><strong>{{ count(snapshot.sentinel.blockedTotal) }}</strong></div><div><span>生效规则</span><strong>{{ snapshot.sentinel.status === 'AVAILABLE' ? snapshot.sentinel.rules.length : '未知' }}</strong></div></div>
          <article v-for="rule in snapshot.sentinel.rules" :key="rule.resource" class="operations-flow-rule"><span class="operations-kicker">当前运行时规则</span><h4>{{ rule.resource }}</h4><div><strong>{{ rule.count }}</strong><span>{{ rule.grade === 'QPS' ? '请求 / 秒' : '并发线程' }}</span></div><p>控制方式：{{ rule.controlBehavior || '未返回' }}</p><small>{{ snapshot.sentinel.ruleSource }}</small></article>
          <EmptyState v-if="!snapshot.sentinel.rules.length" compact :icon="SlidersHorizontal" :title="snapshot.sentinel.status === 'AVAILABLE' ? '运行时没有加载入口规则' : '规则状态暂不可确认'" description="此处不以Nacos里保存的配置代替真正生效的规则。" />
          <div class="operations-governance-note"><Info :size="17" /><p>计数随RAG进程重启归零，只统计Sentinel入口结果。AI每日预算、并发配额与此独立。<small>最近读取：{{ date(snapshot.sentinel.metricsObservedAt) }}</small></p></div>
        </section>
      </div>
    </template>


  </div>
</template>
