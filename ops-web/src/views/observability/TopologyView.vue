<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Expand, Link, Maximize, Minus, Network, Pencil, Plus, RefreshCw, Save, Search, Undo2, Monitor, Activity, LocateFixed, CircleHelp } from '@lucide/vue';
import ObservabilityWorkspaceView from './ObservabilityWorkspaceView.vue';
import TopologyEvidencePanel from '@/components/observability/TopologyEvidencePanel.vue';
import type { TopologyV3Snapshot } from '@/api/observabilityV3';
import ServiceTopology from '@/components/observability/ServiceTopology.vue';
import ServiceNodeDrawer from '@/components/observability/ServiceNodeDrawer.vue';
import ServiceEditor from '@/components/observability/ServiceEditor.vue';
import RelationEditor from '@/components/observability/RelationEditor.vue';
import ServiceHealthBadge from '@/components/observability/ServiceHealthBadge.vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import { useObservabilityStore } from '@/stores/observability';
import { useAuthStore } from '@/stores/auth';
import { observabilityApi, type ServiceNode, type ServiceRelation } from '@/api/observability';
import { environmentNames } from '@/components/cmdb/topology';
import { neighborhood, nodeGroup, nodeGroupLabels, type GraphInputMode } from '@/utils/topology-view';
import { healthLabels, observationTime, serviceContext, filterTopology } from '@/utils/observability';
const store = useObservabilityStore(); const auth = useAuthStore(); const route = useRoute(); const router = useRouter();
const graph = ref<InstanceType<typeof ServiceTopology>>(); const canvas = ref<HTMLElement>();
const drawer = ref(false); const drawerTab = ref('overview'); const targetCiCode = ref(''); const editing = ref(false); const dirty = ref(false); const showTraffic = ref(false); const editor = ref(false); const editingNode = ref<ServiceNode>(); const relations = ref(false); const saving = ref(false); const localError = ref(''); const savedMessage = ref('');
const historical = ref<TopologyV3Snapshot>(); const historyId = ref(''); const evidenceOpen = ref(false);
const activeSnapshot = computed(() => historical.value || store.topologyData);
const selectedNode = computed(() => activeSnapshot.value?.nodes.find(node => node.ciCode === store.selectedCiCode));
function showHistory(snapshot: TopologyV3Snapshot, id: string) { historical.value = snapshot; historyId.value = id; editing.value = false; drawer.value = false; }
async function returnLive() { historical.value = undefined; historyId.value = ''; drawer.value = false; await store.load(); }
const showMinimap = ref(false); const showGovernance = ref(false); const hops = ref(0); const group = ref('all'); const inputMode = ref<GraphInputMode>('mouse');
const viewKey = computed(() => `${auth.user?.userId || 'anonymous'}:${store.selectedEnvironment}:topology:${store.topologyMode}:${historyId.value || 'live'}:v3`);
const graphView = computed(() => {
  const base = filterTopology(activeSnapshot.value?.nodes || [], activeSnapshot.value?.edges || [], store.keyword, store.onlyUnhealthy); const scopedNodes = group.value === 'all' || editing.value ? base.nodes : base.nodes.filter(node => nodeGroup(node) === group.value);
  const codes = new Set(scopedNodes.map(node => node.ciCode));
  return neighborhood(scopedNodes, base.edges.filter(edge => codes.has(edge.sourceCiCode) && codes.has(edge.targetCiCode)), store.selectedCiCode, editing.value ? 0 : hops.value, editing.value || showGovernance.value);
});
watch(inputMode, value => { try { localStorage.setItem(`opsagent-graph-input:${auth.user?.userId}`, value); } catch {} });
let timer: ReturnType<typeof setInterval> | undefined;
const context = computed(() => serviceContext(store.selectedCiCode, store.selectedEnvironment, store.timeRange));
const degradedSources = computed(() => activeSnapshot.value?.dataSources.filter(source => !source.healthy) || []);
function readQuery() { const env = String(route.query.environment || 'ALL'); const time = String(route.query.timeRange || '15m'); store.selectedEnvironment = ['ALL', 'PROD', 'DEMO', 'DEV', 'TEST', 'STAGING'].includes(env) ? env : 'ALL'; store.timeRange = ['5m', '15m', '30m', '1h', '6h'].includes(time) ? time : '15m'; store.selectedCiCode = String(route.query.ciCode || ''); }
function syncQuery() { void router.replace({ query: { ...route.query, ...context.value } }); }
function select(code: string) { drawerTab.value = 'overview'; targetCiCode.value = ''; store.selectedCiCode = code; drawer.value = true; syncQuery(); }
function selectEdge(edge: ServiceRelation) { select(edge.sourceCiCode); drawerTab.value = selectedNode.value?.virtual ? 'overview' : historical.value ? 'relations' : 'runtime'; targetCiCode.value = edge.targetCiCode; }
function openEditor(node?: ServiceNode) { if (node?.virtual) return; editingNode.value = node; editor.value = true; }
async function saved() { editor.value = false; await store.load(); savedMessage.value = '服务台账已更新'; }
async function saveLayout() { if (!auth.isAdmin || !graph.value || saving.value) return; saving.value = true; localError.value = ''; try { await observabilityApi.saveLayout(store.selectedEnvironment, graph.value.positions()); dirty.value = false; editing.value = false; await store.load(); savedMessage.value = '拓扑布局已保存'; } catch (cause) { localError.value = cause instanceof Error ? cause.message : '布局保存失败'; } finally { saving.value = false; } }
async function cancelEdit() { editing.value = false; dirty.value = false; await graph.value?.reset(); }
async function fullscreen() { try { if (document.fullscreenElement) await document.exitFullscreen(); else await canvas.value?.requestFullscreen(); } catch { localError.value = '浏览器暂不支持全屏，请使用右侧大屏入口。'; } }
watch(() => route.query, readQuery);
watch(() => [store.selectedEnvironment, store.timeRange, store.topologyMode], () => { if (!editing.value) { historical.value = undefined; historyId.value = ''; drawer.value = false; syncQuery(); void store.load(); } });
watch(() => auth.token, () => { drawer.value = false; editing.value = false; editor.value = false; relations.value = false; historical.value = undefined; historyId.value = ''; if (auth.token) void store.load(); });
onMounted(() => { try { inputMode.value = localStorage.getItem(`opsagent-graph-input:${auth.user?.userId}`) === 'touchpad' ? 'touchpad' : 'mouse'; } catch {} readQuery(); void store.load(); timer = setInterval(() => { if (!document.hidden && !historical.value && !editing.value && !store.loading) void store.load(); }, store.refreshInterval); });
onBeforeUnmount(() => { clearInterval(timer); store.invalidate(); });
</script>
<template>
  <ObservabilityWorkspaceView description="以服务为上下文，查看运行状态、有向依赖与异常影响。">
    <template #actions><RouterLink class="button secondary" :to="{ path: '/observability/metrics', query: context }"><Activity :size="16" />指标与采集</RouterLink><RouterLink class="button secondary" :to="{ path: '/observability/wallboard', query: context }"><Monitor :size="16" />只读大屏</RouterLink><button class="button secondary" :disabled="store.loading || editing || !!historical" @click="store.load"><RefreshCw :size="16" :class="{ 'motion-spin': store.loading }" />刷新</button></template>
    <InlineError v-if="store.error || localError" :message="store.error || localError" /><div v-if="savedMessage" class="obs-success" role="status">{{ savedMessage }}<button class="text-button" @click="savedMessage = ''">关闭</button></div>
    <div class="obs-evidence-entry"><button class="button secondary" :aria-expanded="evidenceOpen" @click="evidenceOpen = !evidenceOpen">历史快照与依赖差异</button><span class="obs-muted">{{ activeSnapshot?.trace?.message }} {{ activeSnapshot?.trace?.samplingPolicy }}</span></div>
    <TopologyEvidencePanel v-if="evidenceOpen" :environment="store.selectedEnvironment" :time-range="store.timeRange" :history-id="historyId" @history="showHistory" @live="returnLive" />
    <div v-if="historical" class="obs-history-banner" role="status"><strong>正在查看历史快照 · {{ observationTime(historical.checkedAt) }}</strong><span>图版本 {{ historical.graphVersion }} · 实时轮询已暂停，详情仅显示保存时的证据。</span><button class="button secondary compact" @click="returnLive">返回实时</button></div>
    <section class="panel obs-topology-panel" ref="canvas">
      <div class="obs-toolbar"><label>环境<select v-model="store.selectedEnvironment" :disabled="editing"><option value="ALL">全部环境</option><option v-for="(label, key) in environmentNames" :key="key" :value="key">{{ label }}</option></select></label><label>时间窗口<select v-model="store.timeRange" :disabled="editing"><option value="5m">最近 5 分钟</option><option value="15m">最近 15 分钟</option><option value="30m">最近 30 分钟</option><option value="1h">最近 1 小时</option><option value="6h">最近 6 小时</option></select></label><label>关系来源<select v-model="store.topologyMode" :disabled="editing"><option value="CONFIGURED">登记拓扑</option><option value="HYBRID">混合视图</option><option value="OBSERVED">实际调用</option></select></label><div class="search-box obs-service-search"><Search :size="16" /><input v-model="store.keyword" :disabled="editing" placeholder="搜索服务、编码或负责人" aria-label="搜索拓扑服务" /></div><label class="obs-check"><input v-model="store.onlyUnhealthy" :disabled="editing" type="checkbox" />只看异常</label><label class="obs-check"><input v-model="showGovernance" :disabled="editing" type="checkbox" />治理关系</label><label class="obs-check"><input v-model="showTraffic" type="checkbox" />显示关系与流量</label></div>
      <div class="obs-canvas-controls"><div class="row-actions"><span class="obs-canvas-count"><Network :size="15" />{{ graphView.nodes.length }} 个节点 · {{ graphView.edges.length }} 条关系</span><button class="button secondary compact" @click="graph?.autoLayout()">整理布局</button><button class="icon-button" title="缩小" aria-label="缩小拓扑" @click="graph?.zoomBy(.8)"><Minus :size="16" /></button><span class="obs-zoom">{{ graph?.zoom || 100 }}%</span><button class="icon-button" title="放大" aria-label="放大拓扑" @click="graph?.zoomBy(1.25)"><Plus :size="16" /></button><button class="icon-button" title="适应画布" aria-label="适应画布" @click="graph?.fit()"><Expand :size="17" /></button><button class="icon-button" title="定位所选" aria-label="定位所选服务" :disabled="!store.selectedCiCode" @click="graph?.focusSelected()"><LocateFixed :size="17" /></button><button class="icon-button" title="全屏" aria-label="全屏拓扑" @click="fullscreen"><Maximize :size="16" /></button></div><button v-if="auth.isAdmin && !editing && !historical" class="button secondary" :disabled="!store.topologyData || store.topologyMode === 'OBSERVED'" @click="editing = true; store.keyword = ''; store.onlyUnhealthy = false; group = 'all'; hops = 0"><Pencil :size="15" />编辑拓扑</button><div v-if="editing" class="row-actions"><button class="button secondary" @click="openEditor()"><Plus :size="15" />新建节点</button><button class="button secondary" :disabled="!selectedNode || selectedNode.virtual" @click="openEditor(selectedNode)">编辑选中节点</button><button class="button secondary" @click="relations = true"><Link :size="15" />关系</button><button class="button secondary" :disabled="saving" @click="cancelEdit"><Undo2 :size="15" />撤销布局</button><button class="button primary" :disabled="saving" @click="saveLayout"><Save :size="15" />保存布局</button></div></div>
      <div class="obs-view-controls"><label>服务范围<select v-model="group" :disabled="editing"><option value="all">全部类型</option><option v-for="(label, key) in nodeGroupLabels" :key="key" :value="key">{{ label }}</option></select></label><label>邻域聚焦<select v-model.number="hops" :disabled="editing || !store.selectedCiCode"><option :value="0">全部节点</option><option :value="1">所选服务 · 一跳</option><option :value="2">所选服务 · 两跳</option></select></label><label>操作偏好<select v-model="inputMode"><option value="mouse">鼠标</option><option value="touchpad">触控板</option></select></label><label class="obs-check"><input v-model="showMinimap" type="checkbox" />缩略图</label><details class="obs-gesture-help"><summary><CircleHelp :size="15" />画布操作</summary><p>拖动空白或按住空格拖动以平移；节点仅在编辑模式下可拖动。{{ inputMode === 'mouse' ? '滚轮以指针为中心缩放；Ctrl/Cmd 滚轮保留浏览器缩放。' : '双指滚动平移，捏合或 Alt 滚动缩放。' }}画布聚焦时方向键平移、+ / − 缩放。详情面板和输入框独立滚动。</p></details><span v-if="graph?.layoutChanged" class="obs-muted">关系或范围有更新 · 可按需整理布局</span></div>
      <div v-if="editing" class="obs-edit-note">正在编辑 · 可拖动节点调整位置。{{ dirty ? '布局尚未保存。' : '' }} 节点和关系表单独立保存。</div>
      <LoadingState v-if="store.loading && !activeSnapshot" text="正在汇集服务与运行状态…" />
      <ServiceTopology v-else-if="graphView.nodes.length" ref="graph" :nodes="graphView.nodes" :edges="graphView.edges" :storage-key="viewKey" :input-mode="inputMode" :minimap="showMinimap" :selected="store.selectedCiCode" :editing="editing" :show-traffic="showTraffic" :layout="activeSnapshot?.layout" @select="select" @edge-select="selectEdge" @change="dirty = true" @error="localError = $event" />
      <EmptyState v-else :icon="Network" :title="store.topologyMode === 'OBSERVED' ? '此范围暂无实际调用关系' : store.error ? '暂时无法读取拓扑' : '没有符合筛选的服务'" :description="store.topologyMode === 'OBSERVED' ? activeSnapshot?.relationMessage || '当前窗口未取得可归属的调用证据；登记关系可在登记拓扑中查看。' : '调整环境、搜索或异常筛选，或刷新后重试。'" />
      <footer class="obs-topology-footer"><div class="obs-legend"><ServiceHealthBadge v-for="(_, status) in healthLabels" :key="status" :health="status" /></div><small>箭头表示登记的依赖方向 · {{ observationTime(activeSnapshot?.checkedAt) }} · {{ historical ? '历史快照，不自动刷新' : '每 15 秒刷新' }}</small></footer>
    </section>
    <div v-if="degradedSources.length" class="obs-source-note"><strong>部分数据源暂不可用</strong><span v-for="source in degradedSources" :key="source.name">{{ source.name }}：{{ source.message || '暂未获取有效数据' }}</span></div>
    <p class="obs-muted">{{ activeSnapshot?.relationMessage }} 健康结论以有效证据及其范围为准；接入缺失、抓取失败和过期样本在节点详情中分别说明。治理边默认隐藏，筛选不会删除台账关系。</p>
    <ServiceNodeDrawer v-if="drawer && selectedNode" :node="selectedNode" :snapshot="historical" :initial-tab="drawerTab" :target-ci-code="targetCiCode" :environment="store.selectedEnvironment" :time-range="store.timeRange" @close="drawer = false" />
    <ServiceEditor v-if="editor && auth.isAdmin" :node="editingNode" @close="editor = false" @saved="saved" />
    <RelationEditor v-if="relations && auth.isAdmin" :nodes="store.topologyData?.nodes || []" :edges="store.topologyData?.edges || []" :selected="store.selectedCiCode" @close="relations = false" @saved="store.load" />
  </ObservabilityWorkspaceView>
</template>
