<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Activity, ExternalLink, Gauge, RefreshCw, Server } from "@lucide/vue";
import { request } from "@/api/http";
import type { MonitorSummary } from "@/types/api";
import PageHeader from "@/components/PageHeader.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import type { MetricStripItem } from "@/components/MetricStrip.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import TableSurface from "@/components/TableSurface.vue";
import StatusBadge from "@/components/StatusBadge.vue";

const summary = ref<MonitorSummary>();
const loading = ref(false);
const error = ref("");
const allHealthy = computed(() => Boolean(summary.value) && summary.value?.prometheus.healthy && summary.value?.prometheus.upCount === summary.value?.prometheus.targetCount && summary.value?.grafana.healthy);
const serviceNames: Record<string, string> = { "opsagent-gateway": "统一网关", "opsagent-auth": "认证服务", "opsagent-ticket": "工单服务", "opsagent-knowledge": "知识服务", "opsagent-rag": "RAG 服务", "opsagent-platform": "平台服务" };
const metrics = computed<MetricStripItem[]>(() => summary.value ? [
  { key: "services", label: "服务正常", value: `${summary.value.prometheus.upCount}/${summary.value.prometheus.targetCount}`, meta: allHealthy.value ? "全部可抓取" : "存在异常服务", tone: allHealthy.value ? "default" : "danger", icon: Server },
  { key: "prometheus", label: "Prometheus", value: summary.value.prometheus.healthy ? "正常" : "异常", meta: "监控目标", tone: summary.value.prometheus.healthy ? "default" : "danger", icon: Activity },
  { key: "grafana", label: "Grafana", value: summary.value.grafana.healthy ? "正常" : "异常", meta: summary.value.grafana.version || "仪表盘", tone: summary.value.grafana.healthy ? "default" : "danger", icon: Gauge },
  { key: "checked", label: "最近检查", value: new Date(summary.value.checkedAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }), meta: "实时健康状态", icon: RefreshCw },
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
  <div class="stack-page monitor-page">
    <PageHeader title="系统监控" description="查看 OpsAgent 服务健康状态与监控入口">
      <template #actions>
        <a v-if="summary" class="button secondary" :href="summary.prometheus.targetsUrl" target="_blank">Prometheus <ExternalLink :size="13" /></a>
        <a v-if="summary" class="button secondary" :href="summary.grafana.dashboardUrl" target="_blank">Grafana <ExternalLink :size="13" /></a>
        <button class="icon-button" :disabled="loading" :title="loading ? '刷新中…' : '刷新监控状态'" @click="load"><RefreshCw :size="16" /></button>
      </template>
    </PageHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <LoadingState v-if="loading && !summary" text="正在读取监控状态…" />
    <template v-else-if="summary">
      <MetricStrip :items="metrics" label="监控摘要" />
      <TableSurface>
        <template #header><div><h3>服务健康</h3><p>Prometheus 最近一次抓取结果</p></div><StatusBadge :value="allHealthy ? 'ACTIVE' : 'WARNING'" /></template>
        <table class="monitor-service-table"><thead><tr><th>服务</th><th>Job</th><th>状态</th><th>最近抓取</th></tr></thead><tbody>
          <tr v-for="item in summary.services" :key="item.job"><td><span class="service-primary"><Server :size="16" /><strong>{{ serviceNames[item.job] || item.job }}</strong></span><small v-if="item.lastError" class="service-error">{{ item.lastError }}</small></td><td><code>{{ item.job }}</code></td><td><StatusBadge :value="item.health === 'up' ? 'ACTIVE' : 'WARNING'" /></td><td>{{ item.lastScrape ? new Date(item.lastScrape).toLocaleTimeString('zh-CN') : '—' }}</td></tr>
        </tbody></table>
      </TableSurface>
    </template>
  </div>
</template>
