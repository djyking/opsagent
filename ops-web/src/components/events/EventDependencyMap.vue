<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { observabilityApi, type TopologySnapshot } from '@/api/observability';
import { environmentNames } from '@/components/cmdb/topology';
import { Expand, RotateCcw } from '@lucide/vue';
import { relationLabels } from '@/utils/observability';
import ServiceTopology from '@/components/observability/ServiceTopology.vue';
const props = defineProps<{ code?: string; environment?: string }>();
const snapshot = ref<TopologySnapshot>(); const error = ref(''); const loading = ref(false);
let epoch = 0;
const environment = computed(() => (props.environment || 'ALL').toUpperCase());
const mappedEnvironment = computed(() => environment.value === 'ALL' || Object.hasOwn(environmentNames, environment.value));
const registeredEnvironments = computed(() => [...new Set((snapshot.value?.nodes || []).filter(node => node.ciCode === props.code).map(node => node.environment))]);
const scopeAmbiguous = computed(() => !mappedEnvironment.value && registeredEnvironments.value.length > 1);
const registeredEnvironment = computed(() => registeredEnvironments.value.length === 1 ? registeredEnvironments.value[0] : undefined);
const linkEnvironment = computed(() => mappedEnvironment.value ? environment.value : registeredEnvironment.value || 'ALL');
const edges = computed(() => (scopeAmbiguous.value ? [] : snapshot.value?.edges || []).filter(edge => edge.sourceCiCode === props.code || edge.targetCiCode === props.code));
const nodes = computed(() => { if (scopeAmbiguous.value) return []; const ids = new Set([props.code, ...edges.value.flatMap(edge => [edge.sourceCiCode, edge.targetCiCode])]); return (snapshot.value?.nodes || []).filter(node => ids.has(node.ciCode)); });
const selected = ref('');
const graph = ref<InstanceType<typeof ServiceTopology>>();
async function load() {
  const current = ++epoch; snapshot.value = undefined; error.value = ''; selected.value = props.code || '';
  loading.value = false; if (!props.code) return; loading.value = true;
  try { const result = await observabilityApi.topology({ environment: mappedEnvironment.value ? environment.value : 'ALL', timeRange: '15m', mode: mappedEnvironment.value ? 'HYBRID' : 'CONFIGURED' }); if (current === epoch) snapshot.value = result; }
  catch (cause) { if (current === epoch) error.value = cause instanceof Error ? cause.message : '局部依赖读取失败'; }
  finally { if (current === epoch) loading.value = false; }
}
onMounted(load); watch(() => [props.code, props.environment], load); onBeforeUnmount(() => { epoch++; });
</script>
<template><section class="event-dependency-map" aria-label="事件局部依赖链路"><header><strong>影响与关联链路</strong><RouterLink v-if="!scopeAmbiguous" :to="{ path: '/observability/topology', query: { ciCode: selected || code, environment: linkEnvironment } }">查看选中服务与完整拓扑 →</RouterLink></header><p v-if="!mappedEnvironment" class="event-topology-scope">事件环境 {{ environment }}；以下按服务登记{{ registeredEnvironment ? `（${environmentNames[registeredEnvironment] || registeredEnvironment}）` : '' }}展示依赖，恢复结论以本事件验证为准。</p><p v-if="error" class="inline-error">{{ error }}<button class="text-button" @click="load">重试</button></p><p v-else-if="loading">正在读取登记关系与调用证据…</p><div v-if="nodes.length && !loading" class="event-map-controls"><small>{{ graph?.interactionActive ? '滚轮缩放已启用 · 点击外部或 Esc 退出' : '点击画布启用滚轮缩放' }}</small><button class="button secondary compact" @click="graph?.fit()"><Expand :size="14" />适应画布</button><button class="button secondary compact" @click="graph?.restoreView()"><RotateCcw :size="14" />恢复视图</button></div><ServiceTopology v-if="nodes.length && !loading" ref="graph" :nodes="nodes" :edges="edges" :selected="selected" :scope-key="`event:${environment}:${code}`" compact @select="selected = $event" @error="error = $event" /><p v-else-if="!loading && !error">{{ scopeAmbiguous ? '该服务登记了多个环境，请先明确事件的观测环境。' : code ? '当前服务未取得可展示的拓扑证据。' : '事件尚未关联服务，暂不能确定影响链路。' }}</p><details v-if="edges.length"><summary>关系依据 · {{ edges.length }} 条</summary><div v-for="(edge, index) in edges" :key="edge.id || index" class="event-relation-evidence"><strong>{{ edge.sourceCiCode }} → {{ edge.targetCiCode }}</strong><span>{{ relationLabels[edge.relationType] || edge.relationType }} · {{ edge.relationSource || '关系来源未提供' }}</span><p v-if="edge.description">{{ edge.description }}</p></div></details><small v-if="snapshot">只展示与事件关联服务直接相关的已取得关系；登记依赖不等于已证实的故障传播。</small></section></template>
<style scoped>
.event-map-controls { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }.event-map-controls small { margin-right:auto; }.event-topology-scope { margin:0; font-size:12px; line-height:1.7; color:#7a8da7; }.event-dependency-map { display:grid; gap:12px; min-width:0; }.event-dependency-map > header { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:8px; }.event-dependency-map a { color:var(--oa-primary); font-size:12px; }.event-dependency-map :deep(.obs-topology-canvas) { height:310px; min-height:310px; border:1px solid #e4ecf8; border-radius:8px; }.event-dependency-map small,.event-relation-evidence span { font-size:12px; color:#788da9; }.event-relation-evidence { display:grid; gap:5px; padding:10px 0; border-bottom:1px solid #e6edf8; }.event-relation-evidence strong { font-size:12px; }.event-relation-evidence p { margin:0; }
</style>
