<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Clock3, GitCompareArrows, RefreshCw } from '@lucide/vue';
import EmptyState from '@/components/EmptyState.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import PaginationBar from '@/components/PaginationBar.vue';
import { observabilityV3Api, type TopologyHistory, type DependencyDifferences, type DependencyDifference, type TopologyV3Snapshot } from '@/api/observabilityV3';
import { useAuthStore } from '@/stores/auth';
import { observationTime, relationLabels } from '@/utils/observability';
const props = defineProps<{ environment: string; timeRange: string; historyId?: string }>();
const emit = defineEmits<{ history: [snapshot: TopologyV3Snapshot, id: string]; live: [] }>();
const auth = useAuthStore(); const tab = ref('history'); const loading = ref(false); const error = ref(''); const page = ref(1);
const history = ref<TopologyHistory>(); const differences = ref<DependencyDifferences>(); const selectedDifference = ref<DependencyDifference>(); const note = ref(''); const decision = ref<'ACKNOWLEDGED' | 'IGNORE'>('ACKNOWLEDGED'); const busy = ref(false);
function localDate(value: Date) { return new Date(value.getTime() - value.getTimezoneOffset() * 60_000).toISOString().slice(0, 16); }
const from = ref(localDate(new Date(Date.now() - 24 * 3600_000))); const to = ref(localDate(new Date()));
let epoch = 0; let snapshotEpoch = 0; let disposed = false;
const historyRows = computed(() => (history.value?.items || []).slice((page.value - 1) * 10, page.value * 10));
const differenceRows = computed(() => (differences.value?.items || []).slice((page.value - 1) * 10, page.value * 10));
const categories: Record<string, string> = { OBSERVED_ONLY: '发现未登记依赖', CONFIGURED_NOT_OBSERVED: '登记关系未观测到流量', INDETERMINATE: '证据不足，尚不可判断', MATCHED: '登记与观测匹配', UNRESOLVED_IDENTITY: '身份待映射', IDENTITY_UNRESOLVED: '身份待映射' };
async function load() {
  const own = ++epoch; snapshotEpoch++; busy.value = false; loading.value = true; error.value = ''; page.value = 1; selectedDifference.value = undefined;
  const actor = auth.token;
  try {
    if (tab.value === 'history') {
      history.value = undefined;
      const start = new Date(from.value), end = new Date(to.value);
      if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end) throw new Error('请选择有效时间范围，开始时间应早于结束时间');
      const result = await observabilityV3Api.history({ environment: props.environment, from: start.toISOString(), to: end.toISOString() });
      if (!disposed && own === epoch && actor === auth.token) history.value = result;
    } else {
      differences.value = undefined;
      const result = await observabilityV3Api.differences({ environment: props.environment, timeRange: props.timeRange });
      if (!disposed && own === epoch && actor === auth.token) differences.value = result;
    }
  } catch (cause) { if (!disposed && own === epoch) error.value = cause instanceof Error ? cause.message : '证据读取失败'; }
  finally { if (own === epoch) loading.value = false; }
}
async function showSnapshot(id: string | number) {
  const own = ++snapshotEpoch; const actor = auth.token; error.value = ''; busy.value = true;
  try { const result = await observabilityV3Api.historySnapshot(id); if (!disposed && own === snapshotEpoch && actor === auth.token) {
    if (!result.history) throw new Error('返回数据未标识为历史快照，已拒绝与实时图混用'); emit('history', result, String(id));
  } } catch (cause) { if (!disposed && own === snapshotEpoch) error.value = cause instanceof Error ? cause.message : '快照读取失败'; }
  finally { if (own === snapshotEpoch) busy.value = false; }
}
async function saveDecision() {
  if (!auth.isAdmin || !selectedDifference.value || !note.value.trim() || busy.value) return;
  busy.value = true; error.value = '';
  try { await observabilityV3Api.decideDifference(selectedDifference.value.id, { decision: decision.value, note: note.value.trim(), ...(decision.value === 'IGNORE' ? { ignoreUntil: new Date(Date.now() + 24 * 3600_000).toISOString() } : {}) }); selectedDifference.value = undefined; note.value = ''; await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '差异处理记录保存失败'; } finally { busy.value = false; }
}
watch(() => [props.environment, props.timeRange, tab.value, auth.token], load, { flush: 'sync' });
onMounted(load); onBeforeUnmount(() => { disposed = true; epoch++; snapshotEpoch++; });
</script>
<template>
  <section class="obs-topology-evidence panel"><div class="obs-evidence-tabs"><button :class="{ active: tab === 'history' }" @click="tab = 'history'"><Clock3 :size="16" />历史快照</button><button :class="{ active: tab === 'differences' }" @click="tab = 'differences'"><GitCompareArrows :size="16" />登记与观测差异</button><button class="text-button" :disabled="loading || busy" @click="load"><RefreshCw :size="14" />刷新</button></div>
    <div v-if="tab === 'history'" class="obs-evidence-body"><form class="obs-history-filter" @submit.prevent="load"><label>开始时间<input v-model="from" type="datetime-local" required /></label><label>结束时间<input v-model="to" type="datetime-local" required /></label><button class="button secondary compact" :disabled="loading">查询历史</button><button v-if="historyId" type="button" class="button primary compact" @click="emit('live')">返回实时</button></form><p class="obs-muted">{{ history ? `保留 ${history.retentionHours} 小时；最早可用 ${observationTime(history.oldestAt)}` : '按保存时的节点、关系和状态读取。' }}缺失窗口不使用当前状态补齐。</p>
      <button v-for="row in historyRows" :key="row.id" class="obs-history-row" :class="{ selected: historyId === String(row.id) }" :disabled="busy" @click="showSnapshot(row.id)"><span><strong>{{ observationTime(row.generatedAt) }}</strong><small>{{ row.environment }} · 图版本 {{ row.graphVersion }}</small></span><span>{{ row.dataQuality }}<small>{{ observationTime(row.windowStart) }} — {{ observationTime(row.windowEnd) }}</small></span></button><EmptyState v-if="history && !history.items.length && !loading" title="此时间范围没有保存的拓扑快照" description="可能早于接入时间、处于采集断档或超出保留期；不会用实时拓扑替代。" /><PaginationBar v-if="history?.items.length" :page="page" :page-size="10" :total="history.items.length" @change="page = $event" />
    </div>
    <div v-else class="obs-evidence-body"><p class="obs-muted">差异是核对线索。没有流量、没有样本或采集失联，不代表依赖已消失；确认不会自动修改服务台账。</p><article v-for="item in differenceRows" :key="item.id" class="obs-difference-card"><header><strong>{{ categories[item.category] || item.category }}</strong><span>{{ relationLabels[item.relationType] || item.relationType }}</span></header><p>{{ item.sourceCiCode }} → {{ item.targetCiCode }}</p><p class="obs-muted">{{ item.reason }}</p><details v-if="item.evidenceRefs?.length"><summary>关联证据 {{ item.evidenceRefs.length }} 项</summary><code v-for="evidence in item.evidenceRefs" :key="evidence">{{ evidence }}</code></details><p v-if="item.handling?.decision" class="obs-muted">处理：{{ item.handling.decision === 'IGNORE' ? '临时忽略' : item.handling.decision === 'ACKNOWLEDGED' ? '已核对' : '待核对' }} · {{ item.handling.note }}{{ item.handling.expiresAt ? ` · 有效至 ${observationTime(item.handling.expiresAt)}` : '' }}</p><button v-if="auth.isAdmin" class="text-button" @click="selectedDifference = item; note = ''; decision = 'ACKNOWLEDGED'">记录核对结果</button></article><EmptyState v-if="differences && !differences.items.length && !loading" title="此范围没有返回依赖差异" description="请同时核对调用采集状态；没有差异不等于所有依赖均已验证。" /><PaginationBar v-if="differences?.items.length" :page="page" :page-size="10" :total="differences.items.length" @change="page = $event" />
      <form v-if="selectedDifference && auth.isAdmin" class="obs-difference-form" @submit.prevent="saveDecision"><strong>{{ selectedDifference.sourceCiCode }} → {{ selectedDifference.targetCiCode }}</strong><label>处理方式<select v-model="decision"><option value="ACKNOWLEDGED">已核对，保留记录</option><option value="IGNORE">临时忽略 24 小时</option></select></label><label>核对说明<textarea v-model="note" rows="2" maxlength="500" required placeholder="说明核对依据与后续处理，不会自动修改登记关系。" /></label><div class="row-actions"><button type="button" class="button secondary compact" :disabled="busy" @click="selectedDifference = undefined">取消</button><button class="button primary compact" :disabled="busy || !note.trim()">保存处理记录</button></div></form>
    </div>
    <LoadingState v-if="loading || busy" :text="busy ? '读取或保存证据…' : '读取所选范围证据…'" /><InlineError v-if="error" :message="error" />
  </section>
</template>
