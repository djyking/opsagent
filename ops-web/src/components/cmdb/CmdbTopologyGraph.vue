<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from "vue";
import { ArrowRight, Expand, List, Minus, Move, Network, Plus } from "@lucide/vue";
import LoadingState from "@/components/LoadingState.vue";
import EmptyState from "@/components/EmptyState.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import { ciType, edgeGeometry, environmentNames, layoutTopology, NODE_HEIGHT, NODE_WIDTH, normalizeEdges, normalizeNodes, relationNames, type CiRecord } from "./topology";

const props = defineProps<{ root?: CiRecord; nodes: CiRecord[]; edges: CiRecord[]; loading: boolean; error?: string }>();
const emit = defineEmits<{ select: [code: string] }>();
const rootCode = computed(() => String(props.root?.ciCode || ""));
const nodes = computed(() => normalizeNodes(props.nodes, props.root));
const edges = computed(() => normalizeEdges(props.edges));
const layout = computed(() => layoutTopology(nodes.value, edges.value, rootCode.value));
const paths = computed(() => edges.value.map(edge => {
  const peers = edges.value.filter(item => [item.sourceCiCode, item.targetCiCode].sort().join('|') === [edge.sourceCiCode, edge.targetCiCode].sort().join('|'));
  return { edge, geometry: edgeGeometry(edge, peers.findIndex(item => item.key === edge.key), layout.value.nodes, peers.length) };
}).filter(item => item.geometry));
const typeLegend = computed(() => [...new Map(nodes.value.map(node => { const type = ciType(node.ciType); return [type.label, type] as const; })).values()]);
const nodeNames = computed(() => new Map(nodes.value.map(node => [node.ciCode, node.ciName])));
const focusedCode = ref('');
const relationListOpen = ref(false);
const viewport = ref<HTMLElement>();
const scale = ref(1);
const offset = ref({ x: 0, y: 0 });
const dragging = ref(false);
const minScale = .2, maxScale = 1.6;
const arrowId = `topology-arrow-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`;
const activeArrowId = `${arrowId}-active`;
let observer: ResizeObserver | undefined;
let pointer: { id: number; x: number; y: number; offsetX: number; offsetY: number } | undefined;

function fit(overview = false) {
  const element = viewport.value;
  if (!element) return;
  // Keep labels readable; larger graphs remain pannable instead of becoming tiny dots.
  const minimum = overview ? minScale : element.clientWidth < 640 ? .85 : .7;
  scale.value = Math.max(minimum, Math.min(1, (element.clientWidth - 24) / layout.value.width, (element.clientHeight - 24) / layout.value.height));
  const root = layout.value.nodes.find(node => node.node.ciCode === rootCode.value);
  offset.value = {
    x: layout.value.width * scale.value <= element.clientWidth ? (element.clientWidth - layout.value.width * scale.value) / 2 : element.clientWidth / 2 - (root ? root.x + NODE_WIDTH / 2 : layout.value.width / 2) * scale.value,
    y: layout.value.height * scale.value <= element.clientHeight ? (element.clientHeight - layout.value.height * scale.value) / 2 : 12,
  };
}
function zoom(factor: number, origin?: { x: number; y: number }) {
  const element = viewport.value;
  if (!element) return;
  const next = Math.max(minScale, Math.min(maxScale, scale.value * factor));
  const point = origin || { x: element.clientWidth / 2, y: element.clientHeight / 2 };
  const ratio = next / scale.value;
  offset.value = { x: point.x - (point.x - offset.value.x) * ratio, y: point.y - (point.y - offset.value.y) * ratio };
  scale.value = next;
}
function onWheel(event: WheelEvent) {
  const bounds = viewport.value?.getBoundingClientRect();
  if (bounds) zoom(event.deltaY < 0 ? 1.1 : 1 / 1.1, { x: event.clientX - bounds.left, y: event.clientY - bounds.top });
}
function startPan(event: PointerEvent) {
  if (event.button !== 0 || (event.target as HTMLElement).closest('button')) return;
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, offsetX: offset.value.x, offsetY: offset.value.y };
  viewport.value?.setPointerCapture(event.pointerId);
  dragging.value = true;
}
function movePan(event: PointerEvent) {
  if (pointer?.id !== event.pointerId) return;
  offset.value = { x: pointer.offsetX + event.clientX - pointer.x, y: pointer.offsetY + event.clientY - pointer.y };
}
function endPan(event: PointerEvent) {
  if (pointer?.id !== event.pointerId) return;
  if (viewport.value?.hasPointerCapture(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId);
  pointer = undefined;
  dragging.value = false;
}
function onKeydown(event: KeyboardEvent) {
  if (event.target !== event.currentTarget) return;
  if (['+', '=', '-', 'Home', 'ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) event.preventDefault();
  if (event.key === '+' || event.key === '=') zoom(1.2);
  else if (event.key === '-') zoom(1 / 1.2);
  else if (event.key === 'Home') fit();
  else if (event.key === 'ArrowLeft') offset.value.x += 48;
  else if (event.key === 'ArrowRight') offset.value.x -= 48;
  else if (event.key === 'ArrowUp') offset.value.y += 48;
  else if (event.key === 'ArrowDown') offset.value.y -= 48;
}
function edgeActive(source: string, target: string) { return !!focusedCode.value && [source, target].includes(focusedCode.value); }
watch(layout, async () => { await nextTick(); fit(); }, { immediate: true });
onMounted(() => { observer = new ResizeObserver(() => fit()); if (viewport.value) observer.observe(viewport.value); fit(); });
onBeforeUnmount(() => { observer?.disconnect(); pointer = undefined; });
</script>

<template>
  <div class="cmdb-topology" :aria-busy="loading">
    <div class="topology-toolbar">
      <div class="topology-context"><Network :size="15" /><span>直接关联</span><strong>{{ nodes.length }}</strong><span>项</span><i /><strong>{{ error ? '—' : edges.length }}</strong><span>条关系</span></div>
      <div class="topology-view-actions"><button type="button" class="icon-button" aria-label="缩小拓扑" :disabled="scale <= minScale" @click="zoom(1 / 1.2)"><Minus :size="15" /></button><span class="topology-scale">{{ Math.round(scale * 100) }}%</span><button type="button" class="icon-button" aria-label="放大拓扑" :disabled="scale >= maxScale" @click="zoom(1.2)"><Plus :size="15" /></button><button type="button" class="button secondary small" @click="fit(true)"><Expand :size="14" />适应画布</button></div>
    </div>
    <div ref="viewport" class="topology-viewport" :class="{ dragging }" :style="{ '--topology-canvas-height': `${Math.min(620, Math.max(380, layout.height + 24))}px` }" :data-scale="scale" tabindex="0" role="region" aria-label="依赖拓扑画布，可拖动、缩放，按 Tab 选择配置项，方向键平移，Home 恢复视图" @pointerdown="startPan" @pointermove="movePan" @pointerup="endPan" @pointercancel="endPan" @lostpointercapture="endPan" @wheel.prevent="onWheel" @keydown="onKeydown">
      <div v-if="nodes.length" class="topology-scene" :style="{ width: `${layout.width}px`, height: `${layout.height}px`, transform: `translate(${offset.x}px, ${offset.y}px) scale(${scale})` }">
        <div v-for="column in layout.columns" :key="column.key" class="topology-column-label" :class="{ selected: column.key === 'root' }" :style="{ left: `${column.x}px`, width: `${column.width}px` }"><strong>{{ column.title }}</strong><span>{{ column.detail }}</span></div>
        <svg class="topology-edges" :width="layout.width" :height="layout.height" aria-hidden="true">
          <defs><marker :id="arrowId" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="userSpaceOnUse"><path d="M1 1L7 4L1 7" fill="none" stroke="var(--oa-text-tertiary)" stroke-width="1.5" /></marker><marker :id="activeArrowId" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto" markerUnits="userSpaceOnUse"><path d="M1 1L7 4L1 7" fill="none" stroke="var(--oa-primary)" stroke-width="1.8" /></marker></defs>
          <g v-for="item in paths" :key="item.edge.key" class="topology-edge" :class="{ highlighted: edgeActive(item.edge.sourceCiCode, item.edge.targetCiCode) }"><path :d="item.geometry!.path" fill="none" :marker-end="`url(#${edgeActive(item.edge.sourceCiCode, item.edge.targetCiCode) ? activeArrowId : arrowId})`" /><g :transform="`translate(${item.geometry!.x}, ${item.geometry!.y})`"><rect :x="-(relationNames[item.edge.relationType] || item.edge.relationType || '关联').length * 6 - 9" y="-10" :width="(relationNames[item.edge.relationType] || item.edge.relationType || '关联').length * 12 + 18" height="20" rx="6" /><text text-anchor="middle" dominant-baseline="central">{{ relationNames[item.edge.relationType] || item.edge.relationType || '关联' }}</text></g></g>
        </svg>
        <button v-for="item in layout.nodes" :key="item.node.ciCode" type="button" class="topology-node" :class="[`ci-tone-${ciType(item.node.ciType).tone}`, { 'is-root': item.node.ciCode === rootCode, 'is-related': focusedCode && item.node.ciCode === focusedCode }]" :style="{ left: `${item.x}px`, top: `${item.y}px`, width: `${NODE_WIDTH}px`, height: `${NODE_HEIGHT}px` }" :aria-pressed="item.node.ciCode === rootCode" :aria-label="`查看${item.node.ciName}，${ciType(item.node.ciType).label}，${item.node.ciCode}`" :title="`${item.node.ciName}\n${item.node.ciCode}${item.node.endpoint ? `\n${item.node.endpoint}` : ''}`" @click="emit('select', item.node.ciCode)" @mouseenter="focusedCode = item.node.ciCode" @mouseleave="focusedCode = ''" @focus="focusedCode = item.node.ciCode" @blur="focusedCode = ''">
          <span class="topology-node-header"><span class="topology-node-icon"><component :is="ciType(item.node.ciType).icon" :size="21" :stroke-width="1.75" /></span><span class="topology-node-heading"><strong>{{ item.node.ciName }}</strong><small>{{ ciType(item.node.ciType).label }}<span v-if="item.role === '双向关联'"> · 双向关联</span></small></span><span v-if="item.node.ciCode === rootCode" class="topology-current-dot" aria-hidden="true" /></span>
          <code>{{ item.node.ciCode }}</code>
          <span class="topology-node-footer"><span>{{ environmentNames[String(item.node.environment)] || item.node.environment || '环境未设置' }}</span><StatusBadge v-if="item.node.status" :value="String(item.node.status)" /><span v-else>状态未设置</span></span>
        </button>
      </div>
      <div v-if="loading" class="topology-loading"><LoadingState text="正在读取依赖关系…" compact /></div>
      <EmptyState v-else-if="error" class="topology-empty" :icon="Network" title="依赖关系暂不可用" description="请点击页首「刷新目录」重新读取。" />
      <EmptyState v-else-if="!root" class="topology-empty" :icon="Network" title="选择一个配置项开始" description="查看它与服务、中间件之间已录入的直接关系。" />
      <div v-else-if="!edges.length" class="topology-no-relations"><Network :size="15" /><span>当前配置项尚未录入依赖关系</span></div>
    </div>
    <div class="topology-legend"><div><span class="topology-legend-label">配置项类型</span><span v-for="type in typeLegend" :key="type.label" class="topology-type-key" :class="`ci-tone-${type.tone}`"><component :is="type.icon" :size="13" />{{ type.label }}</span></div><span class="topology-direction"><span>源</span><ArrowRight :size="17" /><span>目标</span></span><small>状态为配置登记信息，不代表实时健康。</small></div>
    <footer class="topology-footer"><span><Move :size="13" />拖动画布 · 滚轮缩放 · 点击节点切换中心</span><button type="button" class="text-button" :aria-expanded="relationListOpen" @click="relationListOpen = !relationListOpen"><List :size="14" />{{ relationListOpen ? '收起关系清单' : '查看关系清单' }}</button></footer>
    <section v-if="relationListOpen" class="topology-relations" aria-label="已录入关系清单"><p>箭头遵循已录入关系的源与目标。配置状态不代表实时服务健康。</p><ul v-if="edges.length"><li v-for="edge in edges" :key="edge.key"><div><button type="button" class="text-button" :disabled="!nodeNames.has(edge.sourceCiCode)" @click="emit('select', edge.sourceCiCode)">{{ nodeNames.get(edge.sourceCiCode) || edge.sourceCiCode }}</button><span class="topology-relation-verb">{{ relationNames[edge.relationType] || edge.relationType || '关联' }}<ArrowRight :size="13" /></span><button type="button" class="text-button" :disabled="!nodeNames.has(edge.targetCiCode)" @click="emit('select', edge.targetCiCode)">{{ nodeNames.get(edge.targetCiCode) || edge.targetCiCode }}</button></div><p v-if="edge.description">{{ edge.description }}</p></li></ul><p v-else>尚无关系记录；有管理权限的账号可通过「新增依赖」建立关系。</p></section>
  </div>
</template>
