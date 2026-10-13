<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Activity, History, Play, RefreshCw, Search } from '@lucide/vue';
import ObservabilityWorkspaceView from './ObservabilityWorkspaceView.vue';
import ServiceHealthBadge from '@/components/observability/ServiceHealthBadge.vue';
import ObservationEvidence from '@/components/observability/ObservationEvidence.vue';
import HostResourcePanel from '@/components/observability/HostResourcePanel.vue';
import InspectionRuns from '@/components/automation/InspectionRuns.vue';
import DetailPanel from '@/components/DetailPanel.vue';
import InlineError from '@/components/InlineError.vue';
import EmptyState from '@/components/EmptyState.vue';
import LoadingState from '@/components/LoadingState.vue';
import PaginationBar from '@/components/PaginationBar.vue';
import { observabilityApi, type InspectionItem, type InspectionSnapshot } from '@/api/observability';
import { useAuthStore } from '@/stores/auth';
import { effectiveHealth, observationLabels, observationState, observationTime, serviceContext } from '@/utils/observability';
const route = useRoute(); const auth = useAuthStore(); const data = ref<InspectionSnapshot>(); const loading = ref(false); const error = ref(''); const historyError = ref(''); const busy = ref(''); const page = ref(1); const search = ref(''); const onlyFailed = ref(false); const selected = ref<InspectionItem>(); const history = ref<InspectionItem[]>([]); const historyLoading = ref(false);
let epoch = 0; let historyEpoch = 0; let disposed = false; let timer: ReturnType<typeof setInterval> | undefined;
const environment = computed(() => String(route.query.environment || 'PROD')); const timeRange = computed(() => String(route.query.timeRange || '15m'));
const filtered = computed(() => (data.value?.items || []).filter(item => (!route.query.ciCode || item.ciCode === route.query.ciCode) && (!search.value || `${item.name} ${item.ciCode}`.toLowerCase().includes(search.value.toLowerCase())) && (!onlyFailed.value || !['SUCCESS', 'HEALTHY', 'SUCCEEDED', 'OK'].includes(item.status) || currentHealth(item) !== 'HEALTHY')));
const rows = computed(() => filtered.value.slice((page.value - 1) * 10, page.value * 10));
const selectedCurrent = computed(() => data.value?.items.find(item => item.ciCode === selected.value?.ciCode)?.currentNode);
function currentHealth(item: InspectionItem) { return item.currentNode ? effectiveHealth(item.currentNode) : 'UNKNOWN'; }
function label(status: string) { return ({ NOT_RUN: '尚未执行', SUCCESS: '检查通过', WARNING: '需要关注', HEALTHY: '检查通过', SUCCEEDED: '检查通过', OK: '检查通过', CRITICAL: '检查异常', DEGRADED: '需要关注', FAILED: '检查异常', ATTENTION: '需要关注', UNKNOWN: '未能确认', NOT_CONFIGURED: '未配置检查', MAINTENANCE: '维护中', DRILLING: '演练中', SKIPPED: '已跳过', RUNNING: '执行中', TIMED_OUT: '执行超时' } as Record<string, string>)[status] || status; }
function executionLabel(status?: string) { return ({ COMPLETED: '执行完成', RUNNING: '执行中', SKIPPED: '已跳过', FAILED: '执行失败', TIMED_OUT: '执行超时', LEGACY_RECORDED: '历史记录', NOT_RUN: '未执行' } as Record<string, string>)[status || ''] || status || '未记录'; }
async function load() { const own = ++epoch; const token = auth.identity; loading.value = true; error.value = ''; try { const value = await observabilityApi.inspections({ environment: environment.value, timeRange: timeRange.value }); if (!disposed && own === epoch && token === auth.identity) data.value = value; } catch (cause) { if (!disposed && own === epoch && token === auth.identity) { if (data.value) data.value = { ...data.value }; error.value = cause instanceof Error ? cause.message : '巡检数据读取失败'; } } finally { if (own === epoch) loading.value = false; } }
async function showHistory(item: InspectionItem) { selected.value = item; history.value = []; historyError.value = ''; const own = ++historyEpoch; const token = auth.identity; historyLoading.value = true; try { const value = await observabilityApi.inspectionHistory(item.ciCode); if (!disposed && own === historyEpoch && token === auth.identity) history.value = value.items; } catch (cause) { if (!disposed && own === historyEpoch && token === auth.identity) historyError.value = cause instanceof Error ? cause.message : '历史读取失败'; } finally { if (own === historyEpoch) historyLoading.value = false; } }
async function run(item: InspectionItem) { if ((!auth.isAdmin && !auth.isOps && !auth.isDemo) || busy.value) return; const identity = auth.identity; const runScope = environment.value; busy.value = item.ciCode; try { const result = await observabilityApi.runInspection(item.ciCode); if (!disposed && identity === auth.identity && runScope === environment.value) { await load(); await showHistory({ ...result, name: item.name, ciCode: item.ciCode }); } } catch (cause) { if (!disposed && identity === auth.identity && runScope === environment.value) error.value = cause instanceof Error ? cause.message : '检查执行失败'; } finally { if (!disposed) busy.value = ''; } }
watch(() => [route.query, auth.identity], () => { epoch++; historyEpoch++; selected.value = undefined; history.value = []; data.value = undefined; if (auth.identity) void load(); });
watch(() => [search.value, onlyFailed.value, route.query.ciCode], () => { page.value = 1; });
watch(() => filtered.value.length, total => { page.value = Math.min(page.value, Math.max(1, Math.ceil(total / 10))); });
onMounted(() => { void load(); timer = setInterval(() => { if (!document.hidden && !loading.value && !busy.value) void load(); }, 15_000); });
onBeforeUnmount(() => { disposed = true; epoch++; historyEpoch++; clearInterval(timer); });
</script>
<template>
  <ObservabilityWorkspaceView description="查看当前采集与历史检查，优先处理尚未确认的服务。">
    <template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />刷新结果</button></template>
    <InlineError v-if="error" :message="error" />
    <HostResourcePanel :ci-code="route.query.ciCode ? String(route.query.ciCode) : undefined" />
    <section class="panel obs-inspections">
      <div class="obs-toolbar"><div class="search-box obs-service-search"><Search :size="16" /><input v-model="search" placeholder="搜索检查或服务" aria-label="搜索持续巡检" /></div><label class="obs-check"><input v-model="onlyFailed" type="checkbox" />只看需要核对</label><span class="obs-muted">{{ observationTime(data?.checkedAt) }}</span></div>
      <div v-if="data?.today" class="obs-inspection-counts"><span>当前环境今日检查 <strong>{{ data.today.total || 0 }}</strong> 次</span><span>通过 <strong>{{ data.today.SUCCESS || 0 }}</strong></span><span>需关注 <strong>{{ (data.today.FAILED || 0) + (data.today.WARNING || 0) }}</strong></span><span>未能确认 <strong>{{ data.today.UNKNOWN || 0 }}</strong></span><small>包含环境内全部服务 · 每 15 秒刷新</small></div>
      <p v-if="data" class="obs-inspection-scope">当前筛选 {{ filtered.length }} 条服务记录 · 每个服务展示上次检查</p>
      <div v-if="route.query.ciCode" class="obs-edit-note">当前服务：{{ route.query.ciCode }}<RouterLink class="text-button" :to="{ path: '/observability/inspections', query: { environment, timeRange } }">查看全部服务</RouterLink></div>
      <LoadingState v-if="loading && !data" text="读取持续巡检结果…" />
      <div v-else-if="rows.length" class="obs-table-scroll"><table class="obs-table"><thead><tr><th>服务</th><th>当前观测</th><th>上次检查结果</th><th>操作</th></tr></thead><tbody><tr v-for="item in rows" :key="item.id"><td><strong>{{ item.name }}</strong><small>{{ item.ciCode }}</small></td><td><ServiceHealthBadge :health="currentHealth(item)" :reason="item.currentNode?.statusReason" /><small>{{ observationLabels[observationState(item)] || '待观测' }} · {{ observationTime(item.observation?.lastSuccessfulScrapeAt) }}</small></td><td><span class="obs-inspection-status" :class="{ failed: ['FAILED', 'CRITICAL'].includes(item.status), healthy: ['HEALTHY', 'SUCCEEDED', 'OK', 'SUCCESS'].includes(item.status) }">{{ label(item.status) }}</span><small>{{ observationTime(item.lastCheckedAt) }}</small></td><td><div class="row-actions"><button class="text-button" @click="showHistory(item)"><History :size="15" />详情与历史</button><button v-if="auth.isAdmin || auth.isOps || auth.isDemo" class="text-button" :disabled="!!busy" @click="run(item)"><Play :size="15" />{{ busy === item.ciCode ? '检查中…' : '检查一次' }}</button></div></td></tr></tbody></table></div>
      <EmptyState v-else :icon="Activity" title="暂无匹配检查" description="请调整服务或筛选条件。没有有效数据时不会显示为检查通过。" />
      <PaginationBar v-if="filtered.length" :page="page" :page-size="10" :total="filtered.length" @change="page = $event" />
    </section>
    <details class="panel obs-inspection-platform"><summary>巡检计划与执行步骤</summary><p class="obs-muted">{{ data?.schedule?.enabled ? '持续巡检已开启' : '持续巡检未开启或尚未读取计划状态' }} · 下次执行 {{ observationTime(data?.schedule?.nextRunAt) }}。执行成功与检查通过分别记录。</p><InspectionRuns /></details>
    <DetailPanel v-if="selected" :title="`${selected.name} · 检查详情`" :subtitle="selected.ciCode" @close="selected = undefined; historyEpoch++">
      <details v-if="selectedCurrent" class="obs-disclosure"><summary>当前采集诊断</summary><div class="obs-disclosure-body"><ObservationEvidence :node="selectedCurrent" /><RouterLink class="text-button" :to="{ path: '/observability/topology', query: serviceContext(selected.ciCode, environment, timeRange) }">查看服务拓扑 →</RouterLink></div></details>
      <h3 class="obs-section-title">历史检查 <small>保留执行当时的结论</small></h3><InlineError v-if="historyError" :message="historyError" /><LoadingState v-if="historyLoading" text="读取历史检查…" />
      <div v-else-if="history.length" class="obs-inspection-history"><article v-for="(item, index) in history" :key="`${item.id}-${index}`"><span>{{ observationTime(item.lastCheckedAt) }}</span><strong>{{ label(item.status) }}</strong><p>{{ item.summary }}</p><small>{{ executionLabel(item.executionStatus) }} · 来源 {{ item.source || '历史检查' }} · {{ item.durationMs ?? '—' }} ms</small><details class="obs-disclosure"><summary>检查证据</summary><div class="obs-disclosure-body"><span>采样：{{ observationTime(item.evidence?.observedAt) }}</span><span>活动告警：{{ item.evidence?.activeAlertCount ?? '未获取' }}</span><span>{{ item.evidence?.coverage }}</span><span>{{ item.evidence?.observation?.message }}</span><small>{{ item.reasonCode }}</small></div></details></article></div>
      <EmptyState v-else-if="!historyError" title="暂无历史检查记录" description="可执行一次只读检查，取得真实证据。" />
    </DetailPanel>
  </ObservabilityWorkspaceView>
</template>
