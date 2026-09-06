<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, RefreshCw, Siren, X } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import PageHeader from "@/components/PageHeader.vue";
import FilterBar from "@/components/FilterBar.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import ListSurface from "@/components/ListSurface.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import { formatDateTime, formatRelativeTime } from "@/utils/datetime";
import { statusLabel } from "@/ui/status-map";
import GuidedEmptyState from "@/components/experience/GuidedEmptyState.vue";
import { usePageFeedback } from "@/composables/usePageFeedback";

const route = useRoute(), router = useRouter();
const allAlerts = ref<Record<string, unknown>[]>([]);
const service = computed(() => typeof route.query.ciCode === 'string' ? route.query.ciCode : typeof route.query.service === 'string' ? route.query.service : '');
const alerts = computed(() => service.value ? allAlerts.value.filter(alert => alert.affectedCiCode === service.value || alert.serviceCode === service.value) : allAlerts.value);
const status = ref(['firing', 'resolved', ''].includes(String(route.query.status)) ? String(route.query.status) : 'firing');
const serviceParent = computed(() => ({ path: '/observability/topology', query: { ciCode: service.value,
  ...(typeof route.query.environment === 'string' ? { environment: route.query.environment } : {}),
  ...(typeof route.query.timeRange === 'string' ? { timeRange: route.query.timeRange } : {}) } }));
const error = ref("");
const toast = usePageFeedback(error, load);
const checkedAt = ref('');
const loading = ref(false);
const selected = ref<Record<string, unknown>>();
const criticalCount = computed(() => alerts.value.filter((alert) => String(alert.severity).toUpperCase() === "CRITICAL").length);
const linkedCount = computed(() => alerts.value.filter((alert) => alert.ticketId).length);
let requestVersion = 0;
function selectAlert(alert?: Record<string, unknown>) {
  const { alertId: _alertId, alertService: _alertService, ...query } = route.query;
  void router.replace({ query: { ...query, ...(alert ? { alertId: String(alert.id), alertService: String(alert.affectedCiCode || alert.serviceCode || '') } : {}) } });
}
function clearService() { const { ciCode: _ciCode, service: _service, alertId: _alertId, alertService: _alertService, ...query } = route.query; void router.replace({ query }); }
function changeStatus() { void router.replace({ query: { ...route.query, status: status.value, alertId: undefined, alertService: undefined } }); }

function severityPriority(value: unknown) {
  return ({ CRITICAL: "URGENT", WARNING: "HIGH", INFO: "LOW" } as Record<string, string>)[String(value).toUpperCase()] || "MEDIUM";
}

async function load() {
  const version = ++requestVersion;
  loading.value = true;
  error.value = "";
  try { const result = await itsmApi.alerts(status.value); if (version === requestVersion) { allAlerts.value = result; checkedAt.value = new Date().toLocaleTimeString('zh-CN'); } }
  catch (cause) { if (version === requestVersion) error.value = cause instanceof Error ? cause.message : "告警加载失败"; }
  finally { if (version === requestVersion) loading.value = false; }
}
watch(() => route.query.status, value => { status.value = ['firing', 'resolved', ''].includes(String(value)) ? String(value) : 'firing'; void load(); }, { immediate: true });
watch([alerts, () => route.query.alertId], () => { selected.value = alerts.value.find(alert => String(alert.id) === route.query.alertId); }, { immediate: true });
onBeforeUnmount(() => { requestVersion++; });
</script>

<template>
  <div class="stack-page alert-page">
    <PageHeader title="活动告警" description="查看当前告警、受影响服务及关联工单">
      <template #meta><span>最近刷新 {{ checkedAt || '尚未加载' }}</span></template>
      <template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />{{ loading ? "刷新中…" : "刷新" }}</button></template>
    </PageHeader>
    <div v-if="service" class="alert-service-context"><span>当前服务 <strong>{{ service }}</strong></span><RouterLink :to="serviceParent"><ArrowLeft :size="14" />返回服务拓扑</RouterLink><button type="button" class="button text" @click="clearService"><X :size="14" />查看全部告警</button></div>
    <section v-if="checkedAt" class="alert-summary" aria-label="当前筛选结果摘要">
      <article><span>当前结果</span><strong>{{ alerts.length }}</strong><small>{{ status === 'firing' ? '告警中的信号' : status === 'resolved' ? '已恢复的信号' : '全部状态的信号' }}</small></article>
      <article :class="{ critical: criticalCount > 0 }"><span>紧急告警</span><strong>{{ criticalCount }}</strong><small>当前结果中的最高严重度</small></article>
      <article><span>已关联工单</span><strong>{{ linkedCount }}</strong><small>可以进入工单继续跟进</small></article>
    </section>
    <ListSurface class="alert-list-surface">
      <template #toolbar><FilterBar>
      <label class="filter-field"><span>状态</span><select v-model="status" @change="changeStatus"><option value="">全部状态</option><option value="firing">告警中</option><option value="resolved">已恢复</option></select></label>
      <span class="filter-result">{{ alerts.length }} 条告警 · 最近 200 条可见记录{{ service ? '中匹配当前服务' : '' }}</span>
      </FilterBar></template>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />

      <LoadingState v-if="loading && !alerts.length" text="正在读取告警…" />
      <GuidedEmptyState v-else-if="!alerts.length && !error" kind="alerts" :title="status === 'firing' ? '当前没有活动告警' : '当前没有匹配告警'" :description="`告警接入后会聚合并关联工单。最近一次刷新 ${checkedAt || '尚未成功'}`" action="刷新告警" @action="load" />
      <div v-else class="responsive-table" role="region" aria-label="告警列表" tabindex="0"><table class="alert-table">
        <thead><tr><th>严重度</th><th>告警</th><th>服务</th><th>状态</th><th>次数</th><th>关联工单</th><th>最近发生</th></tr></thead>
        <tbody><tr v-for="alert in alerts" :key="String(alert.id)" tabindex="0" @click="selectAlert(alert)" @keydown.enter="selectAlert(alert)">
          <td><PriorityIndicator :value="severityPriority(alert.severity)" /></td>
          <td><button class="table-title table-title-button" @click.stop="selectAlert(alert)"><strong>{{ alert.alertName }}</strong><span :title="String(alert.fingerprint)">{{ alert.fingerprint }}</span></button></td>
          <td><code>{{ alert.serviceCode || "未映射" }}</code></td>
          <td><StatusBadge :value="String(alert.currentStatus)" /></td>
          <td>{{ alert.occurrenceCount }}</td>
          <td><RouterLink v-if="alert.ticketId" :to="`/tickets/${alert.ticketId}`" @click.stop>{{ alert.ticketNo || `#${alert.ticketId}` }}</RouterLink><span v-else>无</span></td>
          <td><time :title="formatDateTime(String(alert.lastSeenTime))">{{ formatRelativeTime(String(alert.lastSeenTime)) }}</time></td>
        </tr></tbody>
      </table></div>
    </ListSurface>
    <DetailPanel v-if="selected" title="告警详情" :subtitle="String(selected.alertName)" @close="selectAlert()">
      <dl class="oa-definition-list">
        <div><dt>告警状态</dt><dd><StatusBadge :value="String(selected.currentStatus)" /></dd></div>
        <div><dt>严重度</dt><dd :title="String(selected.severity)">{{ statusLabel(selected.severity) }}</dd></div>
        <div><dt>服务</dt><dd><code>{{ selected.serviceCode || "未映射" }}</code></dd></div>
        <div><dt>发生次数</dt><dd>{{ selected.occurrenceCount }}</dd></div>
        <div><dt>最近发生</dt><dd>{{ formatDateTime(String(selected.lastSeenTime)) }}</dd></div>
        <div><dt>Fingerprint</dt><dd><code>{{ selected.fingerprint }}</code></dd></div>
      </dl>
      <template #footer><RouterLink v-if="selected.ticketId" class="button primary" :to="`/tickets/${selected.ticketId}`">打开关联工单</RouterLink></template>
    </DetailPanel>
  </div>
</template>
<style scoped>
.alert-service-context { display: flex; flex-wrap: wrap; align-items: center; gap: 14px; border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); padding: 12px 16px; background: var(--oa-bg-surface); color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.alert-service-context strong { margin-left: 6px; color: var(--oa-text-primary); font-weight: 500; }
.alert-service-context a { display: inline-flex; align-items: center; gap: 6px; color: var(--oa-primary); }
</style>
