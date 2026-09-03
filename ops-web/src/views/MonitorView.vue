<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Activity, ExternalLink, Gauge, RefreshCw, Server } from "@lucide/vue";
import { request } from "@/api/http";
import type { MonitorSummary } from "@/types/api";

const summary = ref<MonitorSummary>();
const loading = ref(false);
const error = ref("");
const allHealthy = computed(
  () =>
    Boolean(summary.value) &&
    summary.value?.prometheus.healthy &&
    summary.value?.prometheus.upCount === summary.value?.prometheus.targetCount &&
    summary.value?.grafana.healthy,
);
const serviceNames: Record<string, string> = {
  "opsagent-gateway": "统一网关",
  "opsagent-auth": "认证服务",
  "opsagent-ticket": "工单服务",
  "opsagent-knowledge": "知识服务",
  "opsagent-rag": "RAG 服务",
  "opsagent-platform": "平台服务",
};

async function load() {
  loading.value = true;
  error.value = "";
  try {
    summary.value = await request<MonitorSummary>({
      url: "/api/platform/monitor/summary",
    });
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "监控数据加载失败";
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <div class="stack-page monitor-page">
    <section class="page-lead monitor-lead">
      <div>
        <span class="eyebrow">OBSERVABILITY</span>
        <h2>系统可观测性</h2>
        <p>实时读取 Prometheus 抓取状态，并提供预配置的 Grafana 仪表盘。</p>
      </div>
      <button class="button secondary" :disabled="loading" @click="load">
        <RefreshCw :size="16" />{{ loading ? "刷新中…" : "刷新状态" }}
      </button>
    </section>

    <p v-if="error" class="inline-error">{{ error }}</p>
    <section v-if="summary" class="monitor-summary-grid">
      <article class="monitor-summary-card">
        <span><Activity :size="21" /></span>
        <div>
          <small>总体状态</small>
          <strong>{{ allHealthy ? "全部组件正常" : "存在异常组件" }}</strong>
          <p>{{ summary.prometheus.upCount }}/{{ summary.prometheus.targetCount }} 个服务可抓取</p>
        </div>
      </article>
      <article class="monitor-summary-card">
        <span><Gauge :size="21" /></span>
        <div>
          <small>Prometheus</small>
          <strong>{{ summary.prometheus.healthy ? "已配置并运行" : "连接失败" }}</strong>
          <a :href="summary.prometheus.targetsUrl" target="_blank">查看 Targets <ExternalLink :size="13" /></a>
        </div>
      </article>
      <article class="monitor-summary-card">
        <span><Gauge :size="21" /></span>
        <div>
          <small>Grafana {{ summary.grafana.version }}</small>
          <strong>{{ summary.grafana.healthy ? "数据源及仪表盘已预置" : "连接失败" }}</strong>
          <a :href="summary.grafana.dashboardUrl" target="_blank">打开 OpsAgent 仪表盘 <ExternalLink :size="13" /></a>
        </div>
      </article>
    </section>

    <section class="panel monitor-services">
      <header class="panel-header">
        <div><span class="eyebrow">LIVE TARGETS</span><h3>服务抓取目标</h3></div>
        <span v-if="summary" class="panel-count">更新于 {{ new Date(summary.checkedAt).toLocaleTimeString("zh-CN") }}</span>
      </header>
      <div v-if="loading && !summary" class="loading-state">正在读取 Prometheus…</div>
      <div v-else class="service-health-grid">
        <article v-for="item in summary?.services" :key="item.job">
          <span class="service-health-icon"><Server :size="18" /></span>
          <div>
            <strong>{{ serviceNames[item.job] || item.job }}</strong>
            <small>{{ item.job }}</small>
            <p v-if="item.lastError">{{ item.lastError }}</p>
            <time v-else>最近抓取 {{ new Date(item.lastScrape).toLocaleTimeString("zh-CN") }}</time>
          </div>
          <i :class="item.health === 'up' ? 'health-up' : 'health-down'">{{ item.health === "up" ? "正常" : "异常" }}</i>
        </article>
      </div>
    </section>
  </div>
</template>
