<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { AlertTriangle, CheckCircle2, Clock3, RefreshCw, Search } from "@lucide/vue";
import { useRoute } from "vue-router";
import { slaApi, type SlaRow, type SlaSummary, type SlaViewFilter } from "@/api/sla";
import type { PageResponse } from "@/types/api";
import MetricStrip from "@/components/MetricStrip.vue";
import type { MetricStripItem } from "@/components/MetricStrip.vue";
import PageHeader from "@/components/PageHeader.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import EmptyState from "@/components/EmptyState.vue";
import ListSurface from "@/components/ListSurface.vue";
import PaginationBar from "@/components/PaginationBar.vue";
import { statusLabel } from "@/ui/status-map";
import { usePageFeedback } from "@/composables/usePageFeedback";

const route = useRoute();
const page = ref<PageResponse<SlaRow>>({ records: [], total: 0, pageNum: 1, pageSize: 10 });
const summary = ref<SlaSummary>();
const pageNum = ref(1);
const pageSize = ref(10);
const error = ref("");
const toast = usePageFeedback(error, load);
const checkedAt = ref('');
const loading = ref(false);
const now = ref(Date.now());
const view = ref<SlaViewFilter>(route.query.view === "risk" ? "risk" : route.query.view === "breached" ? "breached" : "all");
const priority = ref("");
const service = ref("");
const keyword = ref("");
let timer = 0;
let searchTimer: ReturnType<typeof setTimeout> | undefined;
let requestVersion = 0;
const counts = computed(() => summary.value?.counts);
const compliance = computed(() => {
  const finished = (counts.value?.completed || 0) + (counts.value?.breached || 0);
  return finished ? `${Math.round(((counts.value?.completed || 0) / finished) * 100)}%` : "—";
});
const metrics = computed<MetricStripItem[]>(() => [
  { key: "running", label: "计时中", value: counts.value?.running ?? "—", meta: "解决时钟运行中", icon: Clock3 },
  { key: "risk", label: "风险", value: counts.value?.risk ?? "—", meta: "2 小时内接近解决时限", icon: AlertTriangle, tone: counts.value?.risk ? "warning" : "default" },
  { key: "breached", label: "已超时", value: counts.value?.breached ?? "—", meta: "需要复盘或升级处理", icon: AlertTriangle, tone: counts.value?.breached ? "danger" : "default" },
  { key: "completed", label: "已完成", value: counts.value?.completed ?? "—", meta: "全部数据中的已完成计时", icon: CheckCircle2, tone: "success" },
  { key: "compliance", label: "合规率", value: compliance.value, meta: "已完成 / 已结束计时", icon: CheckCircle2 },
]);

function compactRemaining(value: unknown) {
  const milliseconds = new Date(String(value)).getTime() - now.value;
  if (!Number.isFinite(milliseconds)) return "—";
  const minutes = Math.max(0, Math.floor(Math.abs(milliseconds) / 60000));
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  const compact = hours ? `${hours}h${rest ? ` ${rest}m` : ""}` : `${rest}m`;
  return milliseconds < 0 ? `超时 +${compact}` : `剩余 ${compact}`;
}

function fullRemaining(value: unknown) {
  const milliseconds = new Date(String(value)).getTime() - now.value;
  const minutes = Math.max(0, Math.floor(Math.abs(milliseconds) / 60000));
  return `${milliseconds < 0 ? "已超时" : "剩余"} ${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}

function statusIcon(value: unknown) {
  if (value === "COMPLETED") return CheckCircle2;
  if (value === "BREACHED") return AlertTriangle;
  return Clock3;
}

async function load(refreshSummary = true) {
  clearTimeout(searchTimer);
  const version = ++requestVersion;
  loading.value = true;
  error.value = "";
  try {
    const [result, aggregate] = await Promise.all([
      slaApi.page({ pageNum: pageNum.value, pageSize: pageSize.value, view: view.value, priority: priority.value, service: service.value, keyword: keyword.value }),
      refreshSummary || !summary.value ? slaApi.summary() : Promise.resolve(summary.value),
    ]);
    if (version !== requestVersion) return;
    page.value = result;
    pageNum.value = result.pageNum;
    summary.value = aggregate;
    checkedAt.value = new Date().toLocaleTimeString('zh-CN');
  } catch (cause) {
    if (version !== requestVersion) return;
    error.value = cause instanceof Error ? cause.message : "SLA 加载失败";
  } finally {
    if (version === requestVersion) loading.value = false;
  }
}

function applyFilters() {
  pageNum.value = 1;
  load(false);
}
function changePage(value: number) {
  if (loading.value || value === page.value.pageNum) return;
  pageNum.value = value;
  load(false);
}
watch([view, priority, service, pageSize], applyFilters);
watch(keyword, () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(applyFilters, 300);
});
watch(() => route.query.view, (value) => {
  if (route.name === "sla") view.value = value === "risk" ? "risk" : value === "breached" ? "breached" : "all";
});

onMounted(() => {
  load();
  timer = window.setInterval(() => (now.value = Date.now()), 30_000);
});
onBeforeUnmount(() => { window.clearInterval(timer); clearTimeout(searchTimer); requestVersion += 1; });
</script>

<template>
  <div class="stack-page sla-page" :data-refreshing="loading && !!checkedAt">
    <PageHeader title="SLA 看板" description="响应和解决计时、预警、超时以及升级">
      <template #meta><span>指标更新于 {{ summary ? new Date(summary.checkedAt).toLocaleTimeString('zh-CN') : '尚未加载' }} · 按全部 SLA 记录统计</span></template>
      <template #actions>
        <button class="button secondary" :disabled="loading" @click="load()"><RefreshCw :size="15" :class="{ 'motion-spin': loading }" />{{ loading ? "刷新中…" : "刷新" }}</button>
      </template>
    </PageHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <MetricStrip :items="metrics" label="SLA 核心指标" />

    <ListSurface class="sla-data-surface">
      <template #header><div><h3>时限与风险</h3><p>从响应到解决，跟进当前工单时钟</p></div><span class="panel-count">{{ page.total }} 张工单</span></template>
      <template #toolbar>
        <section class="sla-controls" aria-label="SLA 筛选">
          <div class="segmented-control">
            <button :class="{ active: view === 'all' }" @click="view = 'all'">全部</button>
            <button :class="{ active: view === 'risk' }" @click="view = 'risk'">风险</button>
            <button :class="{ active: view === 'breached' }" @click="view = 'breached'">已超时</button>
          </div>
          <select v-model="priority" aria-label="按优先级筛选"><option value="">全部优先级</option><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select>
          <select v-model="service" aria-label="按服务筛选"><option value="">全部服务</option><option v-for="item in summary?.services || []" :key="item" :value="item">{{ item }}</option></select>
          <label class="sla-search"><Search :size="15" /><input v-model.trim="keyword" maxlength="200" placeholder="搜索工单或服务" @keyup.enter="applyFilters" /></label>
        </section>
      </template>
      <LoadingState v-if="loading && !page.records.length" text="正在加载 SLA 计时…" />
      <div v-else class="responsive-table" role="region" aria-label="SLA 工单列表" tabindex="0">
        <table class="sla-table">
          <thead><tr><th>工单</th><th>优先级</th><th>受影响服务</th><th>响应 SLA</th><th>解决 SLA</th><th>时限</th><th>升级</th></tr></thead>
          <tbody>
            <tr v-for="row in page.records" :key="String(row.id)">
              <td><RouterLink class="table-title" :to="'/tickets/' + row.ticketId"><strong>{{ row.title }}</strong><span>{{ row.ticketNo }}</span></RouterLink></td>
              <td><PriorityIndicator :value="String(row.priority)" /></td>
              <td>{{ row.affectedCiCode || "未关联" }}</td>
              <td><span class="sla-state" :class="'state-' + String(row.responseStatus).toLowerCase()"><component :is="statusIcon(row.responseStatus)" :size="14" />{{ statusLabel(row.responseStatus) }}</span></td>
              <td><span class="sla-state" :class="'state-' + String(row.resolutionStatus).toLowerCase()"><component :is="statusIcon(row.resolutionStatus)" :size="14" />{{ statusLabel(row.resolutionStatus) }}</span></td>
              <td><span class="sla-deadline" :class="{ overdue: new Date(String(row.resolutionDeadline)).getTime() < now }" :title="fullRemaining(row.resolutionDeadline)">{{ compactRemaining(row.resolutionDeadline) }}</span></td>
              <td><span class="escalation-level">L{{ row.escalationLevel }}</span></td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-if="!page.records.length" title="当前筛选下没有 SLA 记录" description="调整筛选条件后再查看" :icon="CheckCircle2" compact />
      </div>
      <template #footer><div class="sla-page-footer"><label>每页<select v-model.number="pageSize" :disabled="loading" aria-label="每页 SLA 工单数"><option :value="10">10 条</option><option :value="20">20 条</option><option :value="50">50 条</option></select></label><PaginationBar :page="page.pageNum" :page-size="page.pageSize" :total="page.total" @change="changePage" /></div></template>
    </ListSurface>
  </div>
</template>
