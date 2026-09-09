<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from 'vue-router';
import { ArrowLeft, ArrowRight, RefreshCw, Siren, X, CircleCheck, Link2 } from "@lucide/vue";
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
import '@/styles/pages/phase3-event-lists.css';

const route = useRoute(), router = useRouter();
const allAlerts = ref<Record<string, unknown>[]>([]);
const service = computed(() => typeof route.query.ciCode === 'string' ? route.query.ciCode : typeof route.query.service === 'string' ? route.query.service : '');
const alerts = computed(() => service.value ? allAlerts.value.filter(alert => alert.affectedCiCode === service.value || alert.serviceCode === service.value) : allAlerts.value);
const status = ref(['firing', 'resolved', ''].includes(String(route.query.status)) ? String(route.query.status) : '');
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
const commonTicket = computed(() => alerts.value.length && alerts.value.every(alert => alert.ticketId && alert.ticketId === alerts.value[0]?.ticketId) ? alerts.value[0]?.ticketId : undefined);
function chooseStatus(value: string) { status.value = value; changeStatus(); }
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
watch(() => route.query.status, value => { status.value = ['firing', 'resolved', ''].includes(String(value)) ? String(value) : ''; void load(); }, { immediate: true });
watch([alerts, () => route.query.alertId], () => { selected.value = alerts.value.find(alert => String(alert.id) === route.query.alertId); }, { immediate: true });
onBeforeUnmount(() => { requestVersion++; });
</script>

<template>
  <div class="stack-page alert-page">
    <PageHeader title="原始告警" description="先确认信号，再进入事件处置"><template #meta><span>最近刷新 {{ checkedAt || '尚未加载' }}</span></template></PageHeader>
    <div v-if="service" class="alert-service-context"><span>当前服务 <strong>{{ service }}</strong></span><RouterLink :to="serviceParent"><ArrowLeft :size="14" />返回服务拓扑</RouterLink><button type="button" class="button text" @click="clearService"><X :size="14" />查看全部告警</button></div>
    <ListSurface class="alert-list-surface">
      <template #toolbar><FilterBar><div class="segmented-control" aria-label="告警状态"><button :class="{ active: status === 'firing' }" :aria-pressed="status === 'firing'" @click="chooseStatus('firing')">活动</button><button :class="{ active: status === 'resolved' }" :aria-pressed="status === 'resolved'" @click="chooseStatus('resolved')">已恢复</button><button :class="{ active: status === '' }" :aria-pressed="status === ''" @click="chooseStatus('')">全部</button></div><span class="filter-result">{{ alerts.length }} 条 · 最近 200 条可见记录{{ service ? '中匹配当前服务' : '' }}</span><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />{{ loading ? '刷新中…' : '刷新' }}</button></FilterBar></template>
      <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
      <div v-if="commonTicket" class="alert-associated-event"><span>当前 {{ alerts.length }} 条告警关联同一事件</span><RouterLink class="button primary" :to="`/tickets/${commonTicket}`">进入关联事件 →</RouterLink></div>
      <LoadingState v-if="loading && !alerts.length" text="正在读取告警…" />
      <GuidedEmptyState v-else-if="!alerts.length && !error" kind="alerts" :title="status === 'firing' ? '当前没有活动告警' : '当前没有匹配告警'" :description="`最近一次刷新 ${checkedAt || '尚未成功'}`" action="刷新告警" @action="load" />
      <div v-else class="responsive-table" role="region" aria-label="告警列表" tabindex="0"><table class="alert-table">
        <thead><tr><th>告警名称</th><th>严重程度</th><th>最近发生</th><th>关联事件</th><th>操作</th></tr></thead>
        <tbody><tr v-for="alert in alerts" :key="String(alert.id)" tabindex="0" @click="selectAlert(alert)" @keydown.enter.self="selectAlert(alert)">
          <td><button class="table-title table-title-button" @click.stop="selectAlert(alert)"><strong>{{ alert.alertName }}</strong><span>ALR-{{ alert.id }} · {{ alert.serviceCode || alert.affectedCiCode || '未映射服务' }}<template v-if="!status"> · {{ alert.currentStatus === 'resolved' ? '已恢复' : '活动' }}</template></span></button></td>
          <td><span class="alert-severity-chip" :data-severity="String(alert.severity).toUpperCase()"><Siren :size="13" />{{ ({ CRITICAL: '严重', WARNING: '警告', INFO: '提示' } as Record<string, string>)[String(alert.severity).toUpperCase()] || String(alert.severity || '未标注') }}</span></td>
          <td><time :title="formatDateTime(String(alert.lastSeenTime))">{{ formatRelativeTime(String(alert.lastSeenTime)) }}</time></td>
          <td><RouterLink v-if="alert.ticketId" class="alert-event-link" :to="`/tickets/${alert.ticketId}`" @click.stop><Link2 :size="13" />EVT-{{ alert.ticketId }} ↗</RouterLink><span v-else>未关联</span></td>
          <td><button class="text-button alert-detail-action" @click.stop="selectAlert(alert)">查看详情 <ArrowRight :size="14" /></button></td>
        </tr></tbody>
      </table></div>
    </ListSurface>
    <p class="alert-page-note">告警是信号，处理与恢复确认在关联事件中继续。</p>
    <DetailPanel v-if="selected" title="告警详情" :subtitle="String(selected.alertName)" @close="selectAlert()">
      <dl class="oa-definition-list">
        <div><dt>告警状态</dt><dd><StatusBadge :value="String(selected.currentStatus)" /></dd></div><div><dt>严重度</dt><dd>{{ statusLabel(selected.severity) }}</dd></div><div><dt>服务</dt><dd>{{ selected.serviceCode || selected.affectedCiCode || '未映射' }}</dd></div><div><dt>首次发生</dt><dd>{{ selected.firstSeenTime ? formatDateTime(String(selected.firstSeenTime)) : '未提供' }}</dd></div><div><dt>最近发生</dt><dd>{{ formatDateTime(String(selected.lastSeenTime)) }}</dd></div><div><dt>重复次数</dt><dd>{{ selected.occurrenceCount }}</dd></div><div><dt>关联主工单</dt><dd>{{ selected.ticketNo || (selected.ticketId ? `#${selected.ticketId}` : '尚未关联') }}</dd></div>
      </dl><details class="alert-raw-evidence"><summary>原始身份与恢复记录</summary><dl class="oa-definition-list"><div><dt>Fingerprint</dt><dd><code>{{ selected.fingerprint }}</code></dd></div><div><dt>恢复时间</dt><dd>{{ selected.resolvedTime ? formatDateTime(String(selected.resolvedTime)) : selected.currentStatus === 'resolved' ? '已记录恢复，源未提供具体时间' : '尚未恢复' }}</dd></div><div><dt>告警周期</dt><dd>{{ selected.episodeId || '未提供' }}</dd></div></dl></details>
      <p v-if="!selected.ticketId" class="alert-page-note">此信号尚未关联主工单；需要时可从事件队列报告问题，并保留此告警标识。</p>
      <template #footer><RouterLink v-if="selected.ticketId" class="button primary" :to="`/tickets/${selected.ticketId}`">进入关联事件</RouterLink><RouterLink v-else class="button secondary" :to="{ path: '/tickets', query: { create: '1' } }">报告关联问题</RouterLink></template>
    </DetailPanel>
  </div>
</template>
<style scoped>
.alert-service-context { display:flex;flex-wrap:wrap;align-items:center;gap:14px;border:1px solid var(--oa-border-subtle);border-radius:var(--oa-radius-panel);padding:12px 16px;background:#fff;color:var(--oa-text-secondary);font-size:13px; }
.alert-service-context a { display:inline-flex;align-items:center;gap:6px;color:var(--oa-primary); }
.alert-associated-event { margin:18px 20px;display:flex;justify-content:space-between;align-items:center;gap:18px;padding:14px 18px;border:1px solid #dce7fb;border-radius:9px;background:#f8faff; }
.alert-page-note { margin:4px 0;color:var(--oa-text-secondary);font-size:13px;line-height:1.8; }
.alert-raw-evidence { margin-top:24px; }.alert-raw-evidence summary { cursor:pointer;color:var(--oa-primary); }
@media(max-width:700px) { .alert-associated-event { align-items:flex-start;flex-direction:column; } }
</style>
