<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { AlertTriangle, CheckCircle2, Clock3, RefreshCw, Search } from "@lucide/vue";
import { useRoute } from "vue-router";
import { itsmApi } from "@/api/modules";
import MetricStrip from "@/components/MetricStrip.vue";
import type { MetricStripItem } from "@/components/MetricStrip.vue";
import PageHeader from "@/components/PageHeader.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";

type SlaRow = Record<string, unknown>;
type SlaView = "all" | "risk" | "breached";

const route = useRoute();
const rows = ref<SlaRow[]>([]);
const error = ref("");
const loading = ref(false);
const now = ref(Date.now());
const view = ref<SlaView>(route.query.view === "risk" ? "risk" : "all");
const priority = ref("");
const service = ref("");
const keyword = ref("");
let timer = 0;

const breached = computed(() => rows.value.filter((row) => row.resolutionStatus === "BREACHED").length);
const runningRows = computed(() => rows.value.filter((row) => row.resolutionStatus === "RUNNING"));
const riskRows = computed(() => runningRows.value.filter((row) => {
  const deadline = new Date(String(row.resolutionDeadline || "")).getTime();
  return deadline > now.value && deadline <= now.value + 2 * 60 * 60 * 1000;
}));
const completed = computed(() => rows.value.length - runningRows.value.length - breached.value);
const compliance = computed(() => {
  const finished = completed.value + breached.value;
  return finished ? `${Math.round((completed.value / finished) * 100)}%` : "—";
});
const services = computed(() => Array.from(new Set(rows.value.map((row) => String(row.affectedCiCode || "")).filter(Boolean))).sort());
const filteredRows = computed(() => rows.value.filter((row) => {
  const deadline = new Date(String(row.resolutionDeadline || "")).getTime();
  const isRisk = row.resolutionStatus === "RUNNING" && deadline > now.value && deadline <= now.value + 2 * 60 * 60 * 1000;
  const matchesView = view.value === "all" || (view.value === "risk" ? isRisk : row.resolutionStatus === "BREACHED");
  const haystack = `${row.title || ""} ${row.ticketNo || ""} ${row.affectedCiCode || ""}`.toLowerCase();
  return matchesView && (!priority.value || row.priority === priority.value) && (!service.value || row.affectedCiCode === service.value) && (!keyword.value || haystack.includes(keyword.value.toLowerCase()));
}));
const metrics = computed<MetricStripItem[]>(() => [
  { key: "running", label: "计时中", value: runningRows.value.length, meta: "响应或解决时钟运行中", icon: Clock3 },
  { key: "risk", label: "风险", value: riskRows.value.length, meta: "2 小时内接近解决时限", icon: AlertTriangle, tone: riskRows.value.length ? "warning" : "default" },
  { key: "breached", label: "已超时", value: breached.value, meta: "需要复盘或升级处理", icon: AlertTriangle, tone: breached.value ? "danger" : "default" },
  { key: "completed", label: "已完成", value: completed.value, meta: "当前数据中的已完成计时", icon: CheckCircle2, tone: "success" },
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

function statusLabel(value: unknown) {
  return ({ COMPLETED: "已完成", BREACHED: "已超时", RUNNING: "计时中", PENDING: "待开始" } as Record<string, string>)[String(value)] || String(value || "—");
}

function statusIcon(value: unknown) {
  if (value === "COMPLETED") return CheckCircle2;
  if (value === "BREACHED") return AlertTriangle;
  return Clock3;
}

async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await itsmApi.slaOverview();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "SLA 加载失败";
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  load();
  timer = window.setInterval(() => (now.value = Date.now()), 30_000);
});
onBeforeUnmount(() => window.clearInterval(timer));
</script>

<template>
  <div class="stack-page sla-page">
    <PageHeader title="SLA 看板" description="响应和解决计时、预警、超时以及升级">
      <template #actions>
        <button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="15" />{{ loading ? "刷新中…" : "刷新" }}</button>
      </template>
    </PageHeader>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <MetricStrip :items="metrics" label="SLA 核心指标" />

    <section class="sla-controls" aria-label="SLA 筛选">
      <div class="segmented-control">
        <button :class="{ active: view === 'all' }" @click="view = 'all'">全部</button>
        <button :class="{ active: view === 'risk' }" @click="view = 'risk'">风险</button>
        <button :class="{ active: view === 'breached' }" @click="view = 'breached'">已超时</button>
      </div>
      <select v-model="priority" aria-label="按优先级筛选"><option value="">全部优先级</option><option value="URGENT">紧急</option><option value="HIGH">高</option><option value="MEDIUM">中</option><option value="LOW">低</option></select>
      <select v-model="service" aria-label="按服务筛选"><option value="">全部服务</option><option v-for="item in services" :key="item" :value="item">{{ item }}</option></select>
      <label class="sla-search"><Search :size="15" /><input v-model.trim="keyword" placeholder="搜索工单或服务" /></label>
    </section>

    <section class="panel table-panel sla-table-panel">
      <div class="responsive-table">
        <table class="sla-table">
          <thead><tr><th>工单</th><th>优先级</th><th>受影响服务</th><th>响应 SLA</th><th>解决 SLA</th><th>时限</th><th>升级</th></tr></thead>
          <tbody>
            <tr v-for="row in filteredRows" :key="String(row.id)">
              <td><RouterLink class="table-title" :to="`/tickets/${row.ticketId}`"><strong>{{ row.title }}</strong><span>{{ row.ticketNo }}</span></RouterLink></td>
              <td><PriorityIndicator :value="String(row.priority)" /></td>
              <td>{{ row.affectedCiCode || "未关联" }}</td>
              <td><span class="sla-state" :class="`state-${String(row.responseStatus).toLowerCase()}`"><component :is="statusIcon(row.responseStatus)" :size="14" />{{ statusLabel(row.responseStatus) }}</span></td>
              <td><span class="sla-state" :class="`state-${String(row.resolutionStatus).toLowerCase()}`"><component :is="statusIcon(row.resolutionStatus)" :size="14" />{{ statusLabel(row.resolutionStatus) }}</span></td>
              <td><span class="sla-deadline" :class="{ overdue: new Date(String(row.resolutionDeadline)).getTime() < now }" :title="fullRemaining(row.resolutionDeadline)">{{ compactRemaining(row.resolutionDeadline) }}</span></td>
              <td><span class="escalation-level">L{{ row.escalationLevel }}</span></td>
            </tr>
          </tbody>
        </table>
        <div v-if="!filteredRows.length && !loading" class="inline-empty"><CheckCircle2 :size="17" />当前筛选下没有 SLA 记录</div>
      </div>
    </section>
  </div>
</template>
