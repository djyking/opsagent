<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { Activity, AlertTriangle, ArrowUpRight, CheckCircle2, Clock3, Radio, RefreshCw, ShieldAlert, UserRoundCheck } from "@lucide/vue";
import PageHeader from "@/components/PageHeader.vue";
import MetricStrip, { type MetricStripItem } from "@/components/MetricStrip.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import LoadingState from "@/components/LoadingState.vue";
import EmptyState from "@/components/EmptyState.vue";
import WorkspaceLauncher from "@/components/dashboard/WorkspaceLauncher.vue";
import { itsmApi } from "@/api/modules";
import { slaApi, type SlaSummary } from "@/api/sla";
import { request } from "@/api/http";
import type { CurrentOnCall, MonitorSummary, Ticket } from "@/types/api";
import { useAuthStore } from "@/stores/auth";
import { usePageFeedback } from "@/composables/usePageFeedback";

const auth = useAuthStore();
const tickets = ref<Ticket[]>([]);
const slaSummary = ref<SlaSummary>();
const alerts = ref<Record<string, unknown>[]>([]);
const currentOnCall = ref<CurrentOnCall>();
const monitor = ref<MonitorSummary>();
const loading = ref(false);
const error = ref("");
const checkedAt = ref("");
const lastAttempt = ref("");
type DataKey = "tickets" | "sla" | "oncall" | "monitor" | "alerts";
const dataLabels: Record<DataKey, string> = { tickets: "工单", sla: "SLA", oncall: "当前值班", monitor: "服务监控", alerts: "活动告警" };
const loaded = reactive<Record<DataKey, boolean>>({ tickets: false, sla: false, oncall: false, monitor: false, alerts: false });
const sourceCheckedAt = reactive<Record<DataKey, string>>({ tickets: "", sla: "", oncall: "", monitor: "", alerts: "" });
const failedSources = ref<DataKey[]>([]);
let loadVersion = 0;
usePageFeedback(error, load);
const activeQueue = ref<"all" | "priority" | "confirm">("all");
const workPanel = ref<HTMLElement>();

function timeValue(value: string) { const time = new Date(value).getTime(); return Number.isFinite(time) ? time : 0; }
function priorityWeight(priority: string) { return ({ URGENT: 4, HIGH: 3, MEDIUM: 2, LOW: 1 } as Record<string, number>)[priority] || 0; }
function sortActive(left: Ticket, right: Ticket) { return priorityWeight(right.priority) - priorityWeight(left.priority) || timeValue(right.updateTime) - timeValue(left.updateTime) || right.id - left.id; }
const activeTickets = computed(() => tickets.value.filter(ticket => !["CLOSED", "REJECTED"].includes(ticket.status)).sort(sortActive));
const priorityTickets = computed(() => activeTickets.value.filter(ticket => ["URGENT", "HIGH"].includes(ticket.priority)));
const confirmTickets = computed(() => activeTickets.value.filter(ticket => ticket.status === "WAITING_CONFIRM"));
const queueTabs = computed(() => [
  { key: "all" as const, label: "全部活跃", count: activeTickets.value.length },
  { key: "priority" as const, label: "高优先级", count: priorityTickets.value.length },
  { key: "confirm" as const, label: "待确认", count: confirmTickets.value.length },
]);
const queueTickets = computed(() => activeQueue.value === "priority" ? priorityTickets.value : activeQueue.value === "confirm" ? confirmTickets.value : activeTickets.value);
const visibleTickets = computed(() => queueTickets.value.slice(0, 7));
const recentTickets = computed(() => [...tickets.value].sort((left, right) => timeValue(right.updateTime) - timeValue(left.updateTime) || right.id - left.id).slice(0, 4));
const processing = computed(() => tickets.value.filter(ticket => ticket.status === "PROCESSING").length);
const slaRisk = computed(() => slaSummary.value?.counts.risk ?? 0);
const slaBreached = computed(() => slaSummary.value?.counts.breached ?? 0);
const firingAlerts = computed(() => alerts.value.filter(alert => String(alert.currentStatus).toLowerCase() === "firing").length);
const healthyServices = computed(() => monitor.value?.services.filter(service => service.health === "up").length ?? 0);
const statusMetrics = computed(() => [
  { label: "待接单", value: tickets.value.filter(ticket => ticket.status === "CREATED").length },
  { label: "已接单", value: tickets.value.filter(ticket => ticket.status === "ASSIGNED").length },
  { label: "处理中", value: processing.value },
  { label: "已挂起", value: tickets.value.filter(ticket => ticket.status === "SUSPENDED").length },
  { label: "待确认", value: confirmTickets.value.length },
  { label: "已解决", value: tickets.value.filter(ticket => ticket.status === "RESOLVED").length },
  { label: "已关闭", value: tickets.value.filter(ticket => ticket.status === "CLOSED").length },
  { label: "已驳回", value: tickets.value.filter(ticket => ticket.status === "REJECTED").length },
]);
const maxStatusMetric = computed(() => Math.max(...statusMetrics.value.map(item => item.value), 1));
const onCallMembers = computed(() => currentOnCall.value?.members || []);
const onCallPeople = computed(() => new Set(onCallMembers.value.map(member => member.userId)).size);
const onCallName = computed(() => {
  if (!loaded.oncall) return loading.value ? "获取中" : "未获取";
  const members = onCallMembers.value;
  const name = members.find(member => member.roleType === "PRIMARY")?.userName || members[0]?.userName;
  if (!name) return "未排班";
  return name;
});
const riskKeys = computed<DataKey[]>(() => auth.isAdmin ? ["tickets", "sla", "alerts"] : ["tickets", "sla"]);
const incompleteRiskSources = computed(() => riskKeys.value.filter(key => !loaded[key] || failedSources.value.includes(key)));
const noPriorityRisk = computed(() => !incompleteRiskSources.value.length && !priorityTickets.value.length && !slaRisk.value && !slaBreached.value && (!auth.isAdmin || !firingAlerts.value));
const syncDescription = computed(() => loading.value ? "正在同步工单、风险与当班信息" : failedSources.value.length ? `最近检查 ${lastAttempt.value} · ${failedSources.value.length} 项数据未更新` : checkedAt.value ? `当前工作与运维态势 · 最近同步 ${checkedAt.value}` : "尚未完成数据同步");
const emptyQueueTitle = computed(() => activeQueue.value === "priority" ? "暂无高优先级活跃工单" : activeQueue.value === "confirm" ? "暂无待业务确认工单" : "当前没有活跃工单");
function metricValue(key: DataKey, value: string | number) { return loaded[key] ? value : loading.value ? "获取中" : "未获取"; }
function metricMeta(key: DataKey, description: string) { return !loaded[key] ? loading.value ? "正在读取业务数据" : "数据暂未获取，请刷新" : failedSources.value.includes(key) ? "刷新失败 · 显示上次数据" : description; }
function freshness(key: DataKey) { return !loaded[key] ? loading.value ? "正在获取" : "数据未获取" : failedSources.value.includes(key) ? `刷新失败 · 上次 ${sourceCheckedAt[key]}` : `同步于 ${sourceCheckedAt[key]}`; }
const overviewMetrics = computed<MetricStripItem[]>(() => [
  { key: "priority", label: "活跃 P1/P2", value: metricValue("tickets", priorityTickets.value.length), meta: metricMeta("tickets", "下方高优先级队列"), icon: ShieldAlert, tone: loaded.tickets && priorityTickets.value.length ? "warning" : "default" },
  { key: "sla", label: "SLA 风险", value: metricValue("sla", slaRisk.value), meta: metricMeta("sla", "未来 2 小时接近解决时限"), to: "/itsm/sla?view=risk", icon: Clock3, tone: loaded.sla && slaRisk.value ? "warning" : "default" },
  ...(auth.isAdmin ? [{ key: "alerts", label: "未恢复告警", value: metricValue("alerts", firingAlerts.value), meta: metricMeta("alerts", "Alertmanager firing"), to: "/itsm/alerts", icon: Radio, tone: loaded.alerts && firingAlerts.value ? "warning" as const : "default" as const }] : []),
  { key: "processing", label: "处理中", value: metricValue("tickets", processing.value), meta: metricMeta("tickets", "状态为处理中的工单"), to: "/tickets?status=PROCESSING", icon: Activity },
  { key: "oncall", label: "当前值班", value: onCallName.value, meta: metricMeta("oncall", onCallPeople.value > 1 ? `${onCallPeople.value} 人当班 · 查看有效排班` : "查看当前人员与排班"), to: "/itsm/oncall", icon: UserRoundCheck },
]);

function formatTime(value: string) { return timeValue(value) ? new Date(value).toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }) : "—"; }
function formatClock() { return new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }); }
async function focusQueue(queue: typeof activeQueue.value) { activeQueue.value = queue; await nextTick(); workPanel.value?.querySelector<HTMLButtonElement>(`button[data-queue="${queue}"]`)?.focus(); workPanel.value?.scrollIntoView({ block: "nearest" }); }
function onQueueKeydown(event: KeyboardEvent) {
  if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
  event.preventDefault();
  const current = queueTabs.value.findIndex(tab => tab.key === activeQueue.value);
  const next = event.key === "Home" ? 0 : event.key === "End" ? queueTabs.value.length - 1 : (current + (event.key === "ArrowRight" ? 1 : -1) + queueTabs.value.length) % queueTabs.value.length;
  activeQueue.value = queueTabs.value[next]!.key;
  (event.currentTarget as HTMLElement).querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
}
async function load() {
  if (loading.value) return;
  const version = ++loadVersion;
  loading.value = true;
  error.value = "";
  const jobs: Array<{ key: DataKey; promise: Promise<unknown> }> = [];
  function collect<T>(key: DataKey, promise: Promise<T>, assign: (value: T) => void) {
    jobs.push({ key, promise: promise.then(value => { if (version !== loadVersion) return; assign(value); loaded[key] = true; sourceCheckedAt[key] = formatClock(); }) });
  }
  // The list endpoint already returns every ticket visible to the authenticated user.
  collect("tickets", request<Ticket[]>({ url: "/api/tickets" }), value => { tickets.value = value; });
  collect("sla", slaApi.summary(), value => { slaSummary.value = value; });
  collect("oncall", itsmApi.currentOnCall(), value => { currentOnCall.value = value; });
  collect("monitor", request<MonitorSummary>({ url: "/api/platform/monitor/summary" }), value => { monitor.value = value; });
  if (auth.isAdmin) collect("alerts", itsmApi.alerts("firing"), value => { alerts.value = value; });
  const results = await Promise.allSettled(jobs.map(job => job.promise));
  if (version !== loadVersion) return;
  failedSources.value = jobs.filter((_, index) => results[index]?.status === "rejected").map(job => job.key);
  lastAttempt.value = formatClock();
  if (results.some(result => result.status === "fulfilled")) checkedAt.value = lastAttempt.value;
  if (failedSources.value.length) error.value = `${failedSources.value.map(key => dataLabels[key]).join("、")}数据未更新。已获取的数据保留上次结果，未获取的数据标记为未知。`;
  loading.value = false;
}
onMounted(load);
onBeforeUnmount(() => { loadVersion++; });
</script>

<template>
  <div class="dashboard-page oa-dashboard" :data-refreshing="loading && !!checkedAt">
    <PageHeader title="运行总览" :description="syncDescription"><template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="15" :class="{ 'motion-spin': loading }" />{{ loading ? "刷新中…" : "刷新状态" }}</button></template></PageHeader>
    <WorkspaceLauncher />
    <p v-if="error" class="inline-error dashboard-sync-error" role="status">{{ error }}</p>
    <MetricStrip class="oa-dashboard-metrics" :items="overviewMetrics" label="核心运维指标" />

    <section class="oa-dashboard-grid">
      <article id="dashboard-active-work" ref="workPanel" class="panel oa-active-work">
        <header class="panel-header"><div><h3>活跃工单</h3><p>按优先级与更新时间排列</p></div><RouterLink class="text-button" to="/tickets">进入工单中心 <ArrowUpRight :size="15" /></RouterLink></header>
        <div class="dashboard-queue-tabs" role="tablist" aria-label="活跃工单范围" @keydown="onQueueKeydown"><button v-for="tab in queueTabs" :id="`dashboard-queue-${tab.key}`" :key="tab.key" type="button" role="tab" :data-queue="tab.key" :aria-selected="activeQueue === tab.key" aria-controls="dashboard-queue-content" :tabindex="activeQueue === tab.key ? 0 : -1" @click="activeQueue = tab.key">{{ tab.label }}<span>{{ loaded.tickets ? tab.count : '—' }}</span></button></div>
        <div id="dashboard-queue-content" role="tabpanel" :aria-labelledby="`dashboard-queue-${activeQueue}`" :aria-busy="loading">
          <LoadingState v-if="loading && !loaded.tickets" text="正在读取当前可见工单…" />
          <EmptyState v-else-if="!loaded.tickets" :icon="AlertTriangle" title="工单数据未获取" description="请刷新后再确认活跃工单与优先级。" />
          <EmptyState v-else-if="!visibleTickets.length" :icon="CheckCircle2" :title="emptyQueueTitle" :description="activeQueue === 'all' ? '当前可见范围内暂无需要跟进的活跃工单。' : '可切换全部活跃，继续查看其他工单。'" />
          <div v-else class="dashboard-table-wrap responsive-table" tabindex="0" aria-label="活跃工单表格">
            <table class="oa-compact-table"><thead><tr><th>级别</th><th>工单</th><th class="dashboard-service-column">服务</th><th>状态</th><th>负责人</th><th>更新</th></tr></thead><tbody><tr v-for="ticket in visibleTickets" :key="ticket.id"><td><PriorityIndicator :value="ticket.priority" /></td><td><RouterLink class="table-title" :to="`/tickets/${ticket.id}`"><strong>{{ ticket.title }}</strong><span>{{ ticket.ticketNo }} · {{ ticket.affectedCiCode || "未关联服务" }}</span></RouterLink></td><td class="dashboard-service-column">{{ ticket.affectedCiCode || "未关联" }}</td><td><StatusBadge :value="ticket.status" /></td><td class="dashboard-ticket-owner">{{ ticket.assigneeId ? `#${ticket.assigneeId}` : "待分配" }}</td><td class="dashboard-ticket-updated">{{ formatTime(ticket.updateTime) }}</td></tr></tbody></table>
          </div>
        </div>
        <footer class="dashboard-work-footer"><span>{{ loaded.tickets ? `当前范围 ${queueTickets.length} 项 · 展示前 ${visibleTickets.length} 项` : '当前范围数量未知' }}</span><small :class="{ 'dashboard-stale': failedSources.includes('tickets') }">{{ freshness('tickets') }}</small></footer>
      </article>

      <aside class="dashboard-side-stack">
        <article class="panel oa-brief"><header class="panel-header"><div><ShieldAlert :size="17" /><h3>需要关注</h3></div><span class="panel-count">风险摘要</span></header><div class="oa-brief-body">
          <button v-if="loaded.tickets && priorityTickets.length" type="button" class="oa-brief-item" @click="focusQueue('priority')"><span class="dashboard-risk-icon"><ShieldAlert :size="17" /></span><span><strong>{{ priorityTickets.length }} 个高优先级工单仍活跃</strong><small>{{ failedSources.includes('tickets') ? '上次数据 · 刷新后确认当前情况' : '查看本页高优先级队列' }}</small></span><ArrowUpRight :size="14" /></button>
          <RouterLink v-if="loaded.sla && slaRisk" to="/itsm/sla?view=risk" class="oa-brief-item"><span class="dashboard-risk-icon"><Clock3 :size="17" /></span><span><strong>{{ slaRisk }} 个工单接近解决时限</strong><small>{{ failedSources.includes('sla') ? '上次数据 · SLA 刷新失败' : '未来 2 小时内到期' }}</small></span><ArrowUpRight :size="14" /></RouterLink>
          <RouterLink v-if="loaded.sla && slaBreached" to="/itsm/sla?view=breached" class="oa-brief-item"><span class="dashboard-risk-icon danger"><AlertTriangle :size="17" /></span><span><strong>{{ slaBreached }} 个 SLA 已超时</strong><small>{{ failedSources.includes('sla') ? '上次数据 · SLA 刷新失败' : '查看超时记录，跟进处理与复盘' }}</small></span><ArrowUpRight :size="14" /></RouterLink>
          <RouterLink v-if="auth.isAdmin && loaded.alerts && firingAlerts" to="/itsm/alerts" class="oa-brief-item"><span class="dashboard-risk-icon"><Radio :size="17" /></span><span><strong>{{ firingAlerts }} 个告警尚未恢复</strong><small>{{ failedSources.includes('alerts') ? '上次数据 · 告警刷新失败' : '查看告警与关联工单' }}</small></span><ArrowUpRight :size="14" /></RouterLink>
          <div v-if="incompleteRiskSources.length" class="dashboard-data-note"><AlertTriangle :size="18" /><span><strong>{{ loading ? '正在确认风险数据' : '部分风险数据未确认' }}</strong><small>{{ incompleteRiskSources.map(key => dataLabels[key]).join('、') }}{{ loading ? '获取中' : '未获取或刷新失败，不能据此判断无风险。' }}</small></span></div>
          <div v-else-if="noPriorityRisk" class="dashboard-data-note calm"><CheckCircle2 :size="21" /><span><strong>当前未发现以上风险事项</strong><small>根据本次已获取的工单、SLA{{ auth.isAdmin ? '与告警' : '' }}数据。</small></span></div>
        </div></article>
        <article class="panel dashboard-oncall"><header class="panel-header"><div><UserRoundCheck :size="17" /><h3>当班人员</h3></div><RouterLink class="text-button" to="/itsm/oncall">排班 <ArrowUpRight :size="14" /></RouterLink></header><div class="dashboard-oncall-body">
          <div v-if="!loaded.oncall" class="dashboard-data-note"><Clock3 :size="19" /><span><strong>{{ loading ? '正在读取当班人员' : '当前值班未获取' }}</strong><small>获取成功后展示有效排班。</small></span></div>
          <template v-else-if="onCallMembers.length"><div v-for="member in onCallMembers.slice(0, 3)" :key="`${member.scheduleCode}-${member.userId}-${member.roleType}-${member.startTime}`" class="dashboard-oncall-person"><span class="dashboard-oncall-avatar">{{ member.userName?.slice(0, 1) || '#' }}</span><span><strong>{{ member.userName || `#${member.userId}` }}<small>{{ member.roleType === 'PRIMARY' ? '主值班' : '备值班' }}</small></strong><small>{{ member.scheduleName }} · 至 {{ formatTime(member.endTime) }}</small></span></div><RouterLink v-if="onCallMembers.length > 3" class="text-button dashboard-oncall-more" to="/itsm/oncall">还有 {{ onCallMembers.length - 3 }} 条有效值班记录 <ArrowUpRight :size="13" /></RouterLink></template>
          <div v-else class="dashboard-data-note"><UserRoundCheck :size="20" /><span><strong>当前没有有效排班</strong><small>{{ currentOnCall?.message || '请联系管理员安排值班人员。' }}</small></span></div>
          <p class="dashboard-source-note" :class="{ 'dashboard-stale': failedSources.includes('oncall') }">{{ freshness('oncall') }}</p>
        </div></article>
      </aside>
    </section>

    <section class="oa-dashboard-lower">
      <article class="panel oa-health-card"><header class="panel-header"><div><h3>服务健康</h3><p>最近一次 Prometheus 抓取</p></div><RouterLink class="text-button" to="/system/monitor">监控 <ArrowUpRight :size="14" /></RouterLink></header>
        <div v-if="!loaded.monitor" class="dashboard-data-note"><Activity :size="22" /><span><strong>{{ loading ? '正在读取监控状态' : '服务监控未获取' }}</strong><small>尚不能确认指标端点的可抓取状态。</small></span></div>
        <template v-else><div class="oa-health-summary"><span class="dashboard-health-icon"><Activity :size="25" /></span><div><strong>{{ !monitor?.prometheus.healthy ? '采集异常' : !monitor.services.length ? '暂无目标' : `${healthyServices}/${monitor.services.length}` }}</strong><span>{{ monitor?.prometheus.healthy ? '指标端点可抓取' : 'Prometheus 暂不可用' }}</span></div></div><div v-if="monitor?.prometheus.healthy && monitor.services.length" class="oa-health-list"><span v-for="service in monitor.services" :key="service.job" :title="`${service.job} · ${service.health === 'up' ? '可抓取' : '抓取异常'}`"><i :class="service.health" />{{ service.job }}</span></div><p v-else class="dashboard-source-note">{{ monitor?.prometheus.error || '请检查采集目标配置与服务发现状态。' }}</p></template>
        <p class="dashboard-source-note" :class="{ 'dashboard-stale': failedSources.includes('monitor') }">{{ freshness('monitor') }} · 不代表全部业务功能健康</p>
      </article>
      <article class="panel oa-status-card"><header class="panel-header"><div><h3>工单状态分布</h3><p>{{ loaded.tickets ? `当前可见的 ${tickets.length} 张工单` : '当前可见范围的数据待获取' }}</p></div></header><div v-if="loaded.tickets" class="oa-bars"><div v-for="item in statusMetrics" :key="item.label"><span>{{ item.label }}</span><i><b :style="{ width: `${item.value / maxStatusMetric * 100}%` }" /></i><strong>{{ item.value }}</strong></div></div><div v-else class="dashboard-data-note"><Clock3 :size="21" /><span><strong>{{ loading ? '正在读取分布' : '工单分布未获取' }}</strong><small>数据获取后显示各状态工单数量。</small></span></div><p class="dashboard-source-note" :class="{ 'dashboard-stale': failedSources.includes('tickets') }">{{ freshness('tickets') }}</p></article>
      <article class="panel oa-recent-card"><header class="panel-header"><div><h3>最近活动</h3><p>按工单更新时间排序</p></div></header><div v-if="!loaded.tickets" class="dashboard-data-note"><Clock3 :size="21" /><span><strong>{{ loading ? '正在读取最近更新' : '最近活动未获取' }}</strong><small>请刷新后再查看更新记录。</small></span></div><EmptyState v-else-if="!recentTickets.length" :icon="Activity" title="暂无工单更新" description="当前可见范围内尚无工单记录。" /><RouterLink v-for="ticket in recentTickets" :key="ticket.id" :to="`/tickets/${ticket.id}`" class="oa-activity-row"><span class="dashboard-activity-icon"><Activity :size="14" /></span><span><strong>{{ ticket.title }}</strong><small>{{ ticket.ticketNo }} · {{ formatTime(ticket.updateTime) }}</small></span><ArrowUpRight :size="13" /></RouterLink><p class="dashboard-source-note" :class="{ 'dashboard-stale': failedSources.includes('tickets') }">{{ freshness('tickets') }}</p></article>
    </section>
  </div>
</template>
