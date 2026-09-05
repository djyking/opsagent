<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Activity, AlertTriangle, ExternalLink, Gauge, RefreshCw, Server, ShieldCheck } from "@lucide/vue";
import { request } from "@/api/http";
import type { MonitorSummary } from "@/types/api";
import PageHeader from "@/components/PageHeader.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import type { MetricStripItem } from "@/components/MetricStrip.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import TableSurface from "@/components/TableSurface.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import EmptyState from "@/components/EmptyState.vue";
import { usePageFeedback } from "@/composables/usePageFeedback";

const summary = ref<MonitorSummary>();
const loading = ref(false);
const error = ref("");
usePageFeedback(error, load);
const targetsHealthy = computed(() => Boolean(summary.value?.prometheus.healthy) && (summary.value?.prometheus.targetCount || 0) > 0 && summary.value?.prometheus.upCount === summary.value?.prometheus.targetCount);
const targetSummary = computed(() => !summary.value?.prometheus.healthy ? "采集状态暂不可用" : !summary.value.prometheus.targetCount ? "尚未发现采集目标" : targetsHealthy.value ? "已配置目标均可抓取" : "部分目标需要关注");
const serviceNames: Record<string, string> = { "opsagent-gateway": "统一网关", "opsagent-auth": "认证服务", "opsagent-ticket": "工单服务", "opsagent-knowledge": "知识服务", "opsagent-rag": "RAG 服务", "opsagent-platform": "平台服务" };
const metrics = computed<MetricStripItem[]>(() => summary.value ? [
  { key: "services", label: "可抓取目标", value: `${summary.value.prometheus.upCount}/${summary.value.prometheus.targetCount}`, meta: !summary.value.prometheus.healthy ? "采集器不可用" : !summary.value.prometheus.targetCount ? "暂无采集目标" : targetsHealthy.value ? "全部可抓取" : "存在异常目标", tone: targetsHealthy.value ? "default" : "warning", icon: Server },
  { key: "attention", label: "异常采集目标", value: summary.value.prometheus.healthy ? Math.max(0, summary.value.prometheus.targetCount - summary.value.prometheus.upCount) : "—", meta: summary.value.prometheus.healthy ? "最近一次抓取结果" : "采集器恢复后可确认", tone: targetsHealthy.value ? "default" : "warning", icon: AlertTriangle },
  { key: "components", label: "监控组件", value: `${Number(summary.value.prometheus.healthy) + Number(summary.value.grafana.healthy)}/2`, meta: "Prometheus 与 Grafana 可用", icon: Gauge },
  { key: "checked", label: "最近检查", value: new Date(summary.value.checkedAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }), meta: "手动刷新以获取新状态", icon: RefreshCw },
] : []);

async function load() {
  loading.value = true; error.value = "";
  try { summary.value = await request<MonitorSummary>({ url: "/api/platform/monitor/summary" }); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "监控数据加载失败"; }
  finally { loading.value = false; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page monitor-page" :data-refreshing="loading && !!summary">
    <PageHeader :icon="Activity" title="系统监控" description="确认服务采集状态，再进入指标与历史趋势分析">
      <template #actions>
        <button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" :class="{ 'motion-spin': loading }" />{{ loading ? '刷新中…' : '刷新状态' }}</button>
      </template>
    </PageHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <LoadingState v-if="loading && !summary" text="正在读取监控状态…" />
    <template v-else-if="summary">
      <section class="monitor-overview" :class="{ 'has-attention': !targetsHealthy }" aria-label="采集状态概况">
        <span class="monitor-overview-icon"><ShieldCheck v-if="targetsHealthy" :size="26" /><Activity v-else :size="26" /></span>
        <div><span class="monitor-eyebrow">服务可观测性</span><h3>{{ targetSummary }}</h3><p>这里显示指标端点的可抓取性；业务请求、资源用量与历史趋势在 Grafana 中查看。</p></div>
        <a class="button secondary" :href="summary.grafana.dashboardUrl" target="_blank" rel="noopener noreferrer">打开指标看板 <ExternalLink :size="14" /></a>
      </section>
      <MetricStrip :items="metrics" label="监控摘要" />
      <div class="monitor-workspace">
      <TableSurface class="monitor-service-surface">
        <template #header><div><h3>服务采集状态</h3><p>Prometheus 最近一次抓取结果 · {{ summary.services.length }} 个目标</p></div><a class="text-button" :href="summary.prometheus.targetsUrl" target="_blank" rel="noopener noreferrer">检查目标 <ExternalLink :size="13" /></a></template>
        <table v-if="summary.services.length" class="monitor-service-table"><thead><tr><th>服务</th><th>采集目标</th><th>状态</th><th>最近抓取</th></tr></thead><tbody>
          <tr v-for="item in summary.services" :key="item.job"><td><span class="service-primary"><Server :size="16" /><strong>{{ serviceNames[item.job] || item.job }}</strong></span><small v-if="item.lastError" class="service-error">{{ item.lastError }}</small></td><td><span class="table-title"><strong>{{ item.job }}</strong><code class="monitor-endpoint">{{ item.scrapeUrl || '未返回采集地址' }}</code></span></td><td><StatusBadge :value="item.health === 'up' ? 'ACTIVE' : 'WARNING'" /></td><td>{{ item.lastScrape ? new Date(item.lastScrape).toLocaleTimeString('zh-CN') : '—' }}</td></tr>
        </tbody></table>
        <EmptyState v-else :icon="Server" title="暂无采集目标" description="请在 Prometheus 中检查目标配置与服务发现状态。" />
        <template #footer><span class="monitor-scope-note">检查时间：{{ new Date(summary.checkedAt).toLocaleString('zh-CN') }} · 页面不会自动刷新</span></template>
      </TableSurface>
      <aside class="monitor-tools" aria-label="监控组件">
        <article class="panel monitor-tool-card"><header><span class="monitor-tool-icon"><Activity :size="21" /></span><div><h3>Prometheus</h3><small>目标发现与指标采集</small></div><StatusBadge :value="summary.prometheus.healthy ? 'ACTIVE' : 'WARNING'" /></header><p :class="{ 'service-error': summary.prometheus.error }">{{ summary.prometheus.error || '查看每个目标的抓取结果，定位连接与采集错误。' }}</p><code>{{ summary.prometheus.url }}</code><a class="button secondary" :href="summary.prometheus.targetsUrl" target="_blank" rel="noopener noreferrer">检查采集目标 <ExternalLink :size="14" /></a></article>
        <article class="panel monitor-tool-card"><header><span class="monitor-tool-icon grafana"><Gauge :size="21" /></span><div><h3>Grafana</h3><small>{{ summary.grafana.version ? `版本 ${summary.grafana.version}` : '指标与历史趋势' }}</small></div><StatusBadge :value="summary.grafana.healthy ? 'ACTIVE' : 'WARNING'" /></header><p :class="{ 'service-error': summary.grafana.error }">{{ summary.grafana.error || '关联请求、JVM 与资源使用变化，查看服务运行趋势。' }}</p><code>{{ summary.grafana.url }}</code><a class="button secondary" :href="summary.grafana.dashboardUrl" target="_blank" rel="noopener noreferrer">查看指标看板 <ExternalLink :size="14" /></a></article>
      </aside>
      </div>
    </template>
  </div>
</template>
