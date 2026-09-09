<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Expand, Link, Maximize, Minus, Network, Plus, RefreshCw, Save, Search, Activity, MoreHorizontal } from '@lucide/vue';
import ObservabilityWorkspaceView from './ObservabilityWorkspaceView.vue';
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
import { observabilityApi, type ServiceNode } from '@/api/observability';
import { environmentNames } from '@/components/cmdb/topology';
import { effectiveHealth, observationTime, serviceContext } from '@/utils/observability';
const store = useObservabilityStore(); const auth = useAuthStore(); const route = useRoute(); const router = useRouter();
const graph = ref<InstanceType<typeof ServiceTopology>>(); const canvas = ref<HTMLElement>();
const drawer = ref(false); const dirty = ref(false); const editor = ref(false); const editingNode = ref<ServiceNode>(); const relations = ref(false); const saving = ref(false); const localError = ref(''); const savedMessage = ref(''); const saveScope = ref('PERSONAL');
const draftLayout = ref<Record<string, { x: number; y: number }>>({});
const canvasLayout = computed(() => ({ ...store.topologyData?.layout, ...draftLayout.value }));
let timer: ReturnType<typeof setInterval> | undefined;
const context = computed(() => serviceContext(store.selectedCiCode, store.selectedEnvironment, store.timeRange));
const degradedSources = computed(() => store.topologyData?.dataSources.filter(source => !source.healthy) || []);
const counts = computed(() => (store.topologyData?.nodes || []).reduce((all, node) => { const key = effectiveHealth(node, store.observationClock); all[key] = (all[key] || 0) + 1; return all; }, {} as Record<string, number>));
const layoutSource = computed(() => ({ PERSONAL: '我的布局', TEAM: '团队默认', AUTO: '自动生成' }[store.layoutData?.source || 'AUTO']));
function readQuery() { const env = String(route.query.environment || 'PROD'); const time = String(route.query.timeRange || '15m'); store.selectedEnvironment = ['ALL', 'PROD', 'DEMO', 'DEV', 'TEST', 'STAGING'].includes(env) ? env : 'PROD'; store.timeRange = ['5m', '15m', '30m', '1h', '6h'].includes(time) ? time : '15m'; store.selectedCiCode = String(route.query.ciCode || ''); }
function syncQuery() { void router.replace({ query: { ...route.query, ...context.value } }); }
function select(code: string) { store.selectedCiCode = code; drawer.value = true; syncQuery(); }
function openEditor(node?: ServiceNode) { editingNode.value = node; editor.value = true; }
function layoutChanged() { dirty.value = true; draftLayout.value = Object.fromEntries((graph.value?.positions() || []).map(position => [position.ciCode, { x: position.x, y: position.y }])); }
async function saved() { editor.value = false; await store.load(); savedMessage.value = '服务台账已更新'; }
async function saveLayout() {
  if (auth.isDemo || !graph.value || saving.value || store.layoutError || saveScope.value === 'TEAM' && !auth.isAdmin) return;
  saving.value = true; localError.value = '';
  const environment = store.selectedEnvironment; const token = auth.identity;
  try {
    const eligible = new Set((store.topologyData?.nodes || []).filter(node => !node.virtual && (store.selectedEnvironment === 'ALL' || node.environment === store.selectedEnvironment)).map(node => node.ciCode));
    const merged = { ...canvasLayout.value, ...Object.fromEntries(graph.value.positions().map(position => [position.ciCode, { x: position.x, y: position.y }])) };
    const positions = Object.entries(merged).filter(([ciCode]) => eligible.has(ciCode)).map(([ciCode, position]) => ({ ciCode, ...position }));
    await (saveScope.value === 'TEAM' ? observabilityApi.saveLayout : observabilityApi.savePersonalLayout)(environment, positions);
    if (environment !== store.selectedEnvironment || token !== auth.identity) return;
    dirty.value = false; draftLayout.value = {}; await Promise.all([store.load(), store.refreshLayout()]); savedMessage.value = saveScope.value === 'TEAM' ? '团队默认布局已保存；已有个人布局保持独立' : '我的布局已保存';
  } catch (cause) { if (environment === store.selectedEnvironment && token === auth.identity) localError.value = cause instanceof Error ? cause.message : '布局保存失败'; } finally { saving.value = false; }
}
async function restoreLayout() { await Promise.all([store.load(), store.refreshLayout()]); if (!store.layoutError && !store.error) { draftLayout.value = {}; await nextTick(); await graph.value?.restoreLayout(); dirty.value = false; } }
async function useTeamLayout() { if (auth.isDemo || saving.value) return; saving.value = true; try { await observabilityApi.resetPersonalLayout(store.selectedEnvironment); await restoreLayout(); savedMessage.value = '已使用团队默认布局'; } catch (cause) { localError.value = cause instanceof Error ? cause.message : '恢复布局失败'; } finally { saving.value = false; } }
async function fullscreen() { try { if (document.fullscreenElement) await document.exitFullscreen(); else await canvas.value?.requestFullscreen(); } catch { localError.value = '浏览器暂未允许全屏，可继续在当前画布查看。'; } }
watch(() => route.query, readQuery);
watch(() => store.selectedEnvironment, () => { draftLayout.value = {}; dirty.value = false; });
watch(() => [store.selectedEnvironment, store.timeRange, store.topologyMode], () => { syncQuery(); void store.load(); });
watch(() => auth.identity, () => { drawer.value = false; editor.value = false; relations.value = false; dirty.value = false; draftLayout.value = {}; saveScope.value = 'PERSONAL'; if (auth.identity) void store.load(); });
function visibleRefresh() {
  if (document.hidden) return;
  store.observationClock = Date.now(); void store.load();
}
onMounted(() => {
  readQuery(); drawer.value = !!store.selectedCiCode; void store.load();
  timer = setInterval(visibleRefresh, store.refreshInterval);
  document.addEventListener('visibilitychange', visibleRefresh);
});
onBeforeUnmount(() => { clearInterval(timer); document.removeEventListener('visibilitychange', visibleRefresh); store.invalidate(); });
</script>
<template>
  <ObservabilityWorkspaceView description="统一服务关系，定位异常并查看影响范围。">
    <template #actions><span class="obs-muted">更新于 {{ observationTime(store.topologyData?.checkedAt) }}</span><button class="button secondary" :disabled="store.loading" @click="store.load"><RefreshCw :size="16" :class="{ 'motion-spin': store.loading }" />刷新</button></template>
    <InlineError v-if="store.error || localError || store.layoutError" :message="store.error || localError || store.layoutError" /><div v-if="savedMessage" class="obs-success" role="status">{{ savedMessage }}<button class="text-button" @click="savedMessage = ''">关闭</button></div>
    <div class="obs-workbench" :class="{ 'has-drawer': drawer && store.selectedNode }">
      <section class="panel obs-topology-panel" ref="canvas">
        <div class="obs-toolbar">
          <nav class="obs-view-switch" aria-label="服务视图"><RouterLink class="active" :to="{ path: '/observability/topology', query: context }">拓扑</RouterLink><RouterLink :to="{ path: '/observability/catalog', query: context }">列表</RouterLink></nav>
          <label><select v-model="store.selectedEnvironment" aria-label="环境"><option value="ALL">全部环境</option><option v-for="(label, key) in environmentNames" :key="key" :value="key">{{ label }}环境</option></select></label>
          <label><select v-model="store.timeRange" aria-label="观测时间窗口"><option value="5m">最近 5 分钟</option><option value="15m">最近 15 分钟</option><option value="30m">最近 30 分钟</option><option value="1h">最近 1 小时</option><option value="6h">最近 6 小时</option></select></label>
          <div class="search-box obs-service-search"><Search :size="16" /><input v-model="store.keyword" placeholder="搜索当前拓扑…" aria-label="搜索拓扑服务" /></div>
          <label class="obs-check"><input v-model="store.onlyUnhealthy" type="checkbox" />只看异常</label>
          <details class="obs-layout-menu"><summary class="button secondary compact"><Network :size="15" />布局</summary><div><button class="button secondary compact" :disabled="!graph" @click="graph?.autoLayout()">使用新版默认布局</button><button class="button secondary compact" @click="graph?.undoLayout()">撤回上次自动布局</button><div v-if="!auth.isDemo" class="obs-save-layout"><button class="button secondary compact" :disabled="saving || !graph || !!store.layoutError" @click="saveLayout"><Save :size="15" />保存布局</button><select v-model="saveScope" aria-label="布局保存范围"><option value="PERSONAL">我的布局</option><option v-if="auth.isAdmin" value="TEAM">团队默认</option></select></div></div></details>
        </div>
        <div class="obs-canvas-controls"><div class="obs-state-counts"><span>全部节点 {{ store.topologyData?.nodes.length || 0 }}</span><span v-for="status in (['HEALTHY', 'CRITICAL', 'DEGRADED', 'UNKNOWN'] as const)" :key="status"><ServiceHealthBadge :health="status" />{{ counts[status] || 0 }}</span></div><div class="row-actions"><button class="icon-button" aria-label="缩小拓扑" @click="graph?.zoomBy(.8)"><Minus :size="16" /></button><span class="obs-zoom">{{ graph?.zoom || 100 }}%</span><button class="icon-button" aria-label="放大拓扑" @click="graph?.zoomBy(1.25)"><Plus :size="16" /></button><button class="button secondary compact" @click="graph?.fit()"><Expand :size="16" />适应画布</button><button class="button secondary compact" @click="graph?.restoreView()">恢复视图</button><button class="icon-button" aria-label="全屏拓扑" @click="fullscreen"><Maximize :size="16" /></button></div></div>
        <LoadingState v-if="store.loading && !store.topologyData" text="正在汇集服务与运行状态…" />
        <ServiceTopology v-else-if="store.visible.nodes.length" ref="graph" :nodes="store.visible.nodes" :edges="store.visible.edges" :selected="store.selectedCiCode" editing :scope-key="`${auth.user?.userId || ''}:${store.selectedEnvironment}`" :layout="canvasLayout" @select="select" @change="layoutChanged" @error="localError = $event" />
        <EmptyState v-else :icon="Network" :title="store.error ? '暂时无法读取拓扑' : '没有符合筛选的服务'" description="调整环境、搜索或异常筛选，或刷新后重试。实际调用为空时请核对采样窗口与 Trace 接入。" />
        <footer class="obs-topology-footer"><div class="obs-legend"><span class="obs-relation-legend">→ 调用 / 依赖</span><span class="obs-relation-legend">⇢ 异步消息</span><small>{{ store.topologyMode === 'CONFIGURED' ? 'CMDB 登记关系' : '按所选窗口采样的调用关系' }}</small></div><small>{{ dirty ? auth.isDemo ? '临时布局' : '布局尚未保存' : layoutSource }} · {{ graph?.interactionActive ? '滚轮缩放已启用 · 点击外部或 Esc 退出' : '点击画布后可滚轮缩放 · 拖动浏览' }}</small></footer>
      </section>
      <ServiceNodeDrawer v-if="drawer && store.selectedNode" :node="store.selectedNode" :environment="store.selectedEnvironment" :time-range="store.timeRange" inline @close="drawer = false" />
    </div>
    <details class="panel obs-disclosure obs-tools"><summary><MoreHorizontal :size="16" />观测来源与拓扑管理 <span v-if="degradedSources.length">{{ degradedSources.length }} 个数据源需核对</span></summary><div class="obs-disclosure-body"><div class="obs-toolbar"><label>关系来源<select v-model="store.topologyMode"><option value="CONFIGURED">登记拓扑</option><option value="HYBRID">登记与实际调用</option><option value="OBSERVED">实际调用</option></select></label><button class="button secondary" :disabled="saving" @click="restoreLayout">撤销未保存布局</button><button v-if="!auth.isDemo" class="button secondary" :disabled="saving" @click="useTeamLayout">使用团队默认</button><RouterLink class="button secondary" :to="{ path: '/observability/metrics', query: context }"><Activity :size="16" />指标与采集</RouterLink><RouterLink class="button secondary" :to="{ path: '/observability/wallboard', query: context }">只读大屏</RouterLink></div><div v-if="auth.isAdmin" class="row-actions"><button class="button secondary" @click="openEditor()"><Plus :size="15" />登记节点</button><button class="button secondary" :disabled="!store.selectedNode || store.selectedNode.virtual" @click="openEditor(store.selectedNode)">编辑选中节点</button><button class="button secondary" @click="relations = true"><Link :size="15" />维护关系</button></div><p class="obs-muted">{{ store.topologyData?.relationMessage }}</p><div v-if="degradedSources.length" class="obs-source-note"><span v-for="source in degradedSources" :key="source.name">{{ source.name }}：{{ source.message || '暂未获取有效数据' }}</span></div></div></details>
    <ServiceEditor v-if="editor && auth.isAdmin" :node="editingNode" @close="editor = false" @saved="saved" /><RelationEditor v-if="relations && auth.isAdmin" :nodes="store.topologyData?.nodes || []" :edges="store.topologyData?.edges || []" @close="relations = false" @saved="store.load" />
  </ObservabilityWorkspaceView>
</template>
