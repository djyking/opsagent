<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  Bot,
  CheckCircle2,
  Clock3,
  Radio,
  RefreshCw,
  ShieldAlert,
  UserRoundCheck,
} from "@lucide/vue";
import PageHeader from "@/components/PageHeader.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import type { MetricStripItem } from "@/components/MetricStrip.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import { itsmApi, ticketApi } from "@/api/modules";
import { slaApi, type SlaSummary } from "@/api/sla";
import { request } from "@/api/http";
import type { CurrentOnCall, MonitorSummary, Ticket } from "@/types/api";
import { useAuthStore } from "@/stores/auth";
import DashboardFocusPanel from "@/components/experience/DashboardFocusPanel.vue";
import { usePageFeedback } from "@/composables/usePageFeedback";

const auth = useAuthStore();
const tickets = ref<Ticket[]>([]);
const slaSummary = ref<SlaSummary>();
const alerts = ref<Record<string, unknown>[]>([]);
const currentOnCall = ref<CurrentOnCall>();
const monitor = ref<MonitorSummary>();
const loading = ref(true);
const error = ref("");
const checkedAt = ref("");
const toast = usePageFeedback(error, load);
const focusCollapsed = ref(localStorage.getItem('opsagent-dashboard-focus-collapsed') === 'true');
function toggleFocus() { focusCollapsed.value = !focusCollapsed.value; localStorage.setItem('opsagent-dashboard-focus-collapsed', String(focusCollapsed.value)); }

const activeTickets = computed(() =>
  tickets.value
    .filter((ticket) => !["CLOSED", "REJECTED"].includes(ticket.status))
    .sort((left, right) => priorityWeight(right.priority) - priorityWeight(left.priority))
    .slice(0, 7),
);
const highPriority = computed(
  () => tickets.value.filter((ticket) => !["CLOSED", "REJECTED"].includes(ticket.status) && ["URGENT", "HIGH"].includes(ticket.priority)).length,
);
const processing = computed(
  () => tickets.value.filter((ticket) => ["ASSIGNED", "PROCESSING", "SUSPENDED"].includes(ticket.status)).length,
);
const slaRisk = computed(() => slaSummary.value?.counts.dashboardRisk || 0);
const firingAlerts = computed(
  () => alerts.value.filter((alert) => String(alert.currentStatus).toLowerCase() === "firing").length,
);
const healthyServices = computed(
  () => monitor.value?.services.filter((service) => service.health === "up").length || 0,
);
const statusMetrics = computed(() => [
  { label: "待接单", value: tickets.value.filter((ticket) => ticket.status === "CREATED").length },
  { label: "处理中", value: processing.value },
  {
    label: "待确认",
    value: tickets.value.filter((ticket) => ["WAITING_CONFIRM", "RESOLVED"].includes(ticket.status)).length,
  },
  { label: "已关闭", value: tickets.value.filter((ticket) => ticket.status === "CLOSED").length },
]);
const maxStatusMetric = computed(() => Math.max(...statusMetrics.value.map((item) => item.value), 1));
const onCallName = computed(() => {
  if (!currentOnCall.value) return "未获取";
  const members = currentOnCall.value.members;
  return members.find((member) => member.roleType === "PRIMARY")?.userName
    || members[0]?.userName || "未排班";
});
const overviewMetrics = computed<MetricStripItem[]>(() => [
  {
    key: "priority",
    label: "活跃 P1/P2",
    value: highPriority.value,
    meta: "高优先级未关闭工单",
    to: "/tickets?priority=HIGH",
    icon: ShieldAlert,
    tone: highPriority.value ? "warning" : "default",
  },
  {
    key: "sla",
    label: "SLA 风险",
    value: slaRisk.value,
    meta: "2 小时内可能超时",
    to: "/itsm/sla?view=risk",
    icon: Clock3,
    tone: slaRisk.value ? "danger" : "default",
  },
  ...(auth.isAdmin
    ? [{
        key: "alerts",
        label: "未恢复告警",
        value: firingAlerts.value,
        meta: "Alertmanager firing",
        to: "/itsm/alerts",
        icon: Radio,
        tone: firingAlerts.value ? "warning" as const : "default" as const,
      }]
    : []),
  {
    key: "processing",
    label: "处理中",
    value: processing.value,
    meta: "已接单及处理中",
    to: "/tickets?status=PROCESSING",
    icon: Activity,
  },
  {
    key: "oncall",
    label: "当前值班",
    value: onCallName.value,
    meta: "查看排班与升级策略",
    to: "/itsm/oncall",
    icon: UserRoundCheck,
  },
]);

function priorityWeight(priority: string) {
  return { URGENT: 4, HIGH: 3, MEDIUM: 2, LOW: 1 }[priority] || 0;
}

function formatTime(value: string) {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

async function load() {
  loading.value = true;
  error.value = "";
  const tasks: Promise<unknown>[] = [
    ticketApi.page({ pageNum: 1, pageSize: 100 }).then((page) => (tickets.value = page.records)),
    slaApi.summary().then((summary) => (slaSummary.value = summary)),
    itsmApi.currentOnCall().then((row) => (currentOnCall.value = row)),
    request<MonitorSummary>({ url: "/api/platform/monitor/summary" }).then(
      (summary) => (monitor.value = summary),
    ),
  ];
  if (auth.isAdmin) {
    tasks.push(itsmApi.alerts("firing").then((rows) => (alerts.value = rows)));
  }
  const results = await Promise.allSettled(tasks);
  if (results.every((result) => result.status === "rejected")) {
    error.value = "总览数据暂时无法获取，请检查网关和后端服务。";
  } else if (results.some((result) => result.status === "rejected")) {
    error.value = "部分总览数据刷新失败，已保留最近获取的数据，请重试。";
  }
  checkedAt.value = new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
  });
  loading.value = false;
}

onMounted(load);
</script>

<template>
  <div class="dashboard-page oa-dashboard" :data-refreshing="loading && !!checkedAt">
    <PageHeader
      title="运行总览"
      :description="`把需要关注的工作放在一起 · 最近同步 ${checkedAt || '--:--'}`"
    >
      <template #actions>
        <button class="button secondary" :aria-expanded="!focusCollapsed" @click="toggleFocus">{{ focusCollapsed ? '展开当前重点' : '暂时收起重点' }}</button>
        <button class="button secondary" :disabled="loading" @click="load">
          <RefreshCw :size="15" :class="{ 'motion-spin': loading }" />{{ loading ? "刷新中…" : "刷新" }}
        </button>
      </template>
    </PageHeader>

    <p v-if="error" class="inline-error">{{ error }}</p>
    <DashboardFocusPanel v-if="!focusCollapsed" :priority="highPriority" :sla-risk="slaRisk" :alerts="firingAlerts" :on-call-name="onCallName" :ready="!!checkedAt && !error" />

    <MetricStrip class="oa-dashboard-metrics" :items="overviewMetrics" label="核心运维指标" />

    <section class="oa-dashboard-grid">
      <article class="panel oa-active-work">
        <header class="panel-header">
          <div><h3>活跃工单</h3><span class="panel-count">按优先级排序</span></div>
          <RouterLink class="text-button" to="/tickets">查看全部 <ArrowUpRight :size="15" /></RouterLink>
        </header>
        <div v-if="loading && !tickets.length" class="loading-state">正在加载工单…</div>
        <div v-else-if="!activeTickets.length" class="empty-state small-empty">
          <CheckCircle2 :size="28" /><strong>当前没有活跃工单</strong><span>所有问题均已闭环。</span>
        </div>
        <div v-else class="dashboard-table-wrap responsive-table" tabindex="0" aria-label="活跃工单表格">
          <table class="oa-compact-table">
            <thead><tr><th>级别</th><th>工单</th><th class="dashboard-service-column">服务</th><th>状态</th><th>负责人</th><th>更新</th></tr></thead>
            <tbody>
              <tr v-for="ticket in activeTickets" :key="ticket.id">
                <td><PriorityIndicator :value="ticket.priority" /></td>
                <td><RouterLink class="table-title" :to="`/tickets/${ticket.id}`"><strong>{{ ticket.title }}</strong><span>{{ ticket.ticketNo }} · {{ ticket.affectedCiCode || "未关联服务" }}</span></RouterLink></td>
                <td class="dashboard-service-column">{{ ticket.affectedCiCode || "未关联" }}</td>
                <td><StatusBadge :value="ticket.status" /></td>
                <td class="dashboard-ticket-owner">{{ ticket.assigneeId ? `#${ticket.assigneeId}` : "待分配" }}</td>
                <td class="dashboard-ticket-updated">{{ formatTime(ticket.updateTime) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <article class="panel oa-brief">
        <header class="panel-header">
          <div><Bot :size="15" /><h3>实时关注</h3></div>
          <span class="panel-count">规则汇总</span>
        </header>
        <div class="oa-brief-body">
          <p class="oa-brief-note">基于实时业务数据 · 不自动执行变更</p>
          <RouterLink v-if="highPriority" to="/tickets" class="oa-brief-item">
            <ShieldAlert :size="17" /><span><strong>{{ highPriority }} 个高优先级工单仍活跃</strong><small>建议优先确认负责人和处理进展</small></span><ArrowUpRight :size="14" />
          </RouterLink>
          <RouterLink v-if="slaRisk" to="/itsm/sla" class="oa-brief-item">
            <AlertTriangle :size="17" /><span><strong>{{ slaRisk }} 个工单接近解决时限</strong><small>阈值：未来 2 小时</small></span><ArrowUpRight :size="14" />
          </RouterLink>
          <RouterLink v-if="auth.isAdmin && firingAlerts" to="/itsm/alerts" class="oa-brief-item">
            <Radio :size="17" /><span><strong>{{ firingAlerts }} 个告警尚未恢复</strong><small>打开告警中心查看聚合与关联工单</small></span><ArrowUpRight :size="14" />
          </RouterLink>
          <div v-if="!highPriority && !slaRisk && !firingAlerts" class="empty-state small-empty">
            <CheckCircle2 :size="26" /><strong>暂未发现需优先关注的事项</strong>
          </div>
        </div>
      </article>
    </section>

    <section class="oa-dashboard-lower">
      <article class="panel oa-health-card">
        <header class="panel-header"><div><h3>服务健康</h3><span class="panel-count">Prometheus 实时抓取</span></div><RouterLink class="text-button" to="/system/monitor">深度监控 <ArrowUpRight :size="15" /></RouterLink></header>
        <div class="oa-health-summary"><strong>{{ healthyServices }}/{{ monitor?.services.length || 0 }}</strong><span>服务正常</span></div>
        <div class="oa-health-list">
          <span v-for="service in monitor?.services" :key="service.job"><i :class="service.health" />{{ service.job }}</span>
          <span v-if="!monitor?.services.length" class="muted">暂无监控目标数据</span>
        </div>
      </article>
      <article class="panel oa-status-card">
        <header class="panel-header"><div><h3>工单状态分布</h3><span class="panel-count">真实工单数据</span></div></header>
        <div class="oa-bars">
          <div v-for="item in statusMetrics" :key="item.label"><span>{{ item.label }}</span><i><b :style="{ width: `${(item.value / maxStatusMetric) * 100}%` }" /></i><strong>{{ item.value }}</strong></div>
        </div>
      </article>
      <article class="panel oa-recent-card">
        <header class="panel-header"><div><h3>最近活动</h3><span class="panel-count">按工单更新时间</span></div></header>
        <div v-if="!tickets.length" class="empty-state small-empty">暂无活动记录</div>
        <RouterLink v-for="ticket in tickets.slice(0, 4)" :key="ticket.id" :to="`/tickets/${ticket.id}`" class="oa-activity-row"><i /><span><strong>{{ ticket.title }}</strong><small>{{ ticket.ticketNo }} · {{ formatTime(ticket.updateTime) }}</small></span></RouterLink>
      </article>
    </section>
  </div>
</template>
