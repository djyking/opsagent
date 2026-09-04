<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  Activity,
  AlertTriangle,
  ArrowUpRight,
  Bot,
  CheckCircle2,
  Clock3,
  Plus,
  Radio,
  RefreshCw,
  ShieldAlert,
  UserRoundCheck,
} from "@lucide/vue";
import PageHeader from "@/components/PageHeader.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import { itsmApi, ticketApi } from "@/api/modules";
import { request } from "@/api/http";
import type { MonitorSummary, Ticket } from "@/types/api";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const tickets = ref<Ticket[]>([]);
const slaRows = ref<Record<string, unknown>[]>([]);
const alerts = ref<Record<string, unknown>[]>([]);
const currentOnCall = ref<Record<string, unknown>>();
const monitor = ref<MonitorSummary>();
const loading = ref(true);
const error = ref("");
const checkedAt = ref("");

const activeTickets = computed(() =>
  tickets.value
    .filter((ticket) => !["CLOSED", "REJECTED"].includes(ticket.status))
    .sort((left, right) => priorityWeight(right.priority) - priorityWeight(left.priority))
    .slice(0, 7),
);
const highPriority = computed(
  () => activeTickets.value.filter((ticket) => ["URGENT", "HIGH"].includes(ticket.priority)).length,
);
const processing = computed(
  () => tickets.value.filter((ticket) => ["ASSIGNED", "PROCESSING", "SUSPENDED"].includes(ticket.status)).length,
);
const slaRisk = computed(() => {
  const threshold = Date.now() + 2 * 60 * 60 * 1000;
  return slaRows.value.filter((row) => {
    const deadline = new Date(String(row.resolutionDeadline || "")).getTime();
    return row.resolutionStatus === "RUNNING" && deadline > 0 && deadline <= threshold;
  }).length;
});
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
const onCallName = computed(
  () =>
    String(
      currentOnCall.value?.displayName ||
        currentOnCall.value?.username ||
        currentOnCall.value?.userName ||
        "未排班",
    ),
);

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
    itsmApi.slaOverview().then((rows) => (slaRows.value = rows)),
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
  <div class="dashboard-page oa-dashboard">
    <PageHeader
      title="运行总览"
      :description="`系统运行${monitor?.prometheus.healthy ? '正常' : '状态待确认'} · 数据更新于 ${checkedAt || '--:--'}`"
    >
      <template #actions>
        <button class="button secondary" :disabled="loading" @click="load">
          <RefreshCw :size="15" />{{ loading ? "刷新中…" : "刷新" }}
        </button>
        <RouterLink class="button primary" to="/tickets?create=1">
          <Plus :size="16" />新建工单
        </RouterLink>
      </template>
    </PageHeader>

    <p v-if="error" class="inline-error">{{ error }}</p>

    <section class="oa-kpi-strip" aria-label="核心运维指标">
      <RouterLink to="/tickets?priority=HIGH" class="oa-kpi">
        <span><ShieldAlert :size="17" />活跃 P1/P2</span>
        <strong>{{ highPriority }}</strong>
        <small>高优先级未关闭工单</small>
      </RouterLink>
      <RouterLink to="/itsm/sla" class="oa-kpi" :class="{ risk: slaRisk > 0 }">
        <span><Clock3 :size="17" />SLA Risk</span>
        <strong>{{ slaRisk }}</strong>
        <small>2 小时内可能超时</small>
      </RouterLink>
      <RouterLink v-if="auth.isAdmin" to="/itsm/alerts" class="oa-kpi">
        <span><Radio :size="17" />未恢复告警</span>
        <strong>{{ firingAlerts }}</strong>
        <small>Alertmanager firing</small>
      </RouterLink>
      <RouterLink to="/tickets?status=PROCESSING" class="oa-kpi">
        <span><Activity :size="17" />处理中</span>
        <strong>{{ processing }}</strong>
        <small>已接单及处理中</small>
      </RouterLink>
      <RouterLink to="/itsm/oncall" class="oa-kpi oa-kpi-oncall">
        <span><UserRoundCheck :size="17" />当前值班</span>
        <strong>{{ onCallName }}</strong>
        <small>查看排班与升级策略</small>
      </RouterLink>
    </section>

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
        <div v-else class="responsive-table">
          <table class="oa-compact-table">
            <thead><tr><th>级别</th><th>工单</th><th>服务</th><th>状态</th><th>负责人</th><th>更新</th></tr></thead>
            <tbody>
              <tr v-for="ticket in activeTickets" :key="ticket.id">
                <td><StatusBadge :value="ticket.priority" /></td>
                <td><RouterLink class="table-title" :to="`/tickets/${ticket.id}`"><strong>{{ ticket.title }}</strong><span>{{ ticket.ticketNo }}</span></RouterLink></td>
                <td>{{ ticket.affectedCiCode || "未关联" }}</td>
                <td><StatusBadge :value="ticket.status" /></td>
                <td>{{ ticket.assigneeId ? `#${ticket.assigneeId}` : "待分配" }}</td>
                <td>{{ formatTime(ticket.updateTime) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </article>

      <article class="panel oa-brief">
        <header class="panel-header">
          <div><span class="oa-ai-label"><Bot :size="14" />智能运维摘要</span><h3>实时关注</h3></div>
          <span class="oa-fact-label">事实聚合</span>
        </header>
        <div class="oa-brief-body">
          <p class="oa-brief-note">当前内容由实时业务数据和确定性规则生成，未调用模型，也不会自动执行变更。</p>
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
