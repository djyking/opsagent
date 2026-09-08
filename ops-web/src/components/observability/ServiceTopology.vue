<script setup lang="ts">
import { computed, h, nextTick, onBeforeUnmount, onMounted, ref, render, watch } from 'vue';
import { Graph, type IElementEvent, type NodeData } from '@antv/g6';
import type { ServiceNode, ServiceRelation, LayoutPosition } from '@/api/observability';
import { serviceIcon } from '@/utils/observability-icons';
import { effectiveHealth, healthColors, healthLabels, metric, relationLabels } from '@/utils/observability';
import { orderedTopologyNodes, initialTopologyPositions, reconcileTopologyPositions, topologyConnections, topologyLane, topologyLaneLabels, type TopologyPositions, TOPOLOGY_FIT_PADDING, TOPOLOGY_NODE_WIDTH, TOPOLOGY_NODE_HEIGHT } from '@/utils/observability-layout';
import '@/styles/pages/observability.css';

const props = withDefaults(defineProps<{ nodes: ServiceNode[]; edges: ServiceRelation[]; selected?: string; editing?: boolean; showTraffic?: boolean; layout?: Record<string, { x: number; y: number }>; wallboard?: boolean; scopeKey?: string; compact?: boolean }>(), { selected: '', editing: false, showTraffic: false, wallboard: false, scopeKey: '', compact: false });
const emit = defineEmits<{ select: [code: string]; change: []; error: [message: string] }>();
const host = ref<HTMLDivElement>(); const zoom = ref(100);
const interactionActive = ref(false);
const canvasHeight = ref<string>();
const hoveredNode = ref('');
const hoveredEdge = ref('');
const hoveredRelation = ref<ServiceRelation>();
const relationSummary = computed(() => {
  if (hoveredRelation.value) return relationTooltip(hoveredRelation.value);
  const focused = hoveredNode.value || props.selected;
  const node = props.nodes.find(item => item.ciCode === focused);
  if (node) return `${node.ciName} · 已突出直接关联路径；悬停连线查看关系`;
  return '悬停节点可突出关联路径，悬停连线可查看关系';
});
let graph: Graph | undefined; let resize: ResizeObserver | undefined; let disposed = false; let revision = 0; let started = false;
let coordinates: TopologyPositions = {};
let manualViewport = false;
let previousCoordinates: TopologyPositions | undefined;
let surfaceResize: ResizeObserver | undefined;
let pointerDrag: { code: string; pointerId: number; clientX: number; clientY: number; x: number; y: number; moved: boolean } | undefined;
let ignoreClickUntil = 0;
function sizeCanvas() {
  if (!host.value || disposed) return;
  if (props.wallboard || window.innerWidth <= 760) { canvasHeight.value = undefined; return; }
  const top = host.value.getBoundingClientRect().top + window.scrollY;
  const footer = host.value.closest('.obs-topology-panel')?.querySelector('footer')?.getBoundingClientRect().height || 36;
  canvasHeight.value = `${Math.max(310, Math.min(props.compact ? 580 : 900, window.innerHeight - top - footer - 68))}px`;
}
const lanePrefix = '__opsagent_lane_';
const iconMarkup = new Map<string, string>();
function escape(value: unknown) { return String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]!); }
function relationTooltip(edge: ServiceRelation) {
  const measures = [edge.rps == null ? '' : `RPS ${metric(edge.rps)}`, edge.errorRate == null ? '' : `Error ${metric(edge.errorRate, '%')}`, edge.p95Ms == null ? '' : `P95 ${metric(edge.p95Ms, 'ms')}`].filter(Boolean);
  return `${edge.sourceCiCode} → ${edge.targetCiCode}\n${[relationLabels[edge.relationType] || edge.relationType, edge.relationSource || '关系来源未提供', ...measures].join(' · ')}`;
}
function icon(node: ServiceNode) { const key = `${node.ciType}:${node.ciCode}`; if (!iconMarkup.has(key)) { const box = document.createElement('span'); render(h(serviceIcon(node), { size: 19, 'stroke-width': 1.8 }), box); const svg = box.innerHTML; render(null, box); iconMarkup.set(key, svg); } return iconMarkup.get(key); }
function html(node: ServiceNode) {
  const state = effectiveHealth(node); const color = healthColors[state];
  return `<div class="obs-graph-node ${props.selected === node.ciCode ? 'is-selected' : ''} ${node.drilling ? 'is-drilling' : ''}" role="${props.wallboard ? 'img' : 'button'}" tabindex="${props.wallboard ? '-1' : '0'}" data-ci-code="${escape(node.ciCode)}" style="--health-color:${color}" aria-label="${escape(`${node.ciName}，${healthLabels[state]}`)}" title="${escape(node.statusReason || healthLabels[state])}"><span class="obs-graph-icon">${icon(node)}<i></i></span><span class="obs-graph-label"><strong>${escape(node.ciName)}</strong><small>${escape(node.overlays?.maintenance ? '维护中' : node.drilling ? '演练中' : state === 'UNKNOWN' ? '待观测' : '')}</small></span></div>`;
}
function data() {
  const { ports, connections } = topologyConnections(props.nodes, props.edges, coordinates);
  const nodes: NodeData[] = orderedTopologyNodes(props.nodes).map((node, index) => ({ id: node.ciCode, data: { node, displayOrder: props.nodes.length - index }, style: { ...coordinates[node.ciCode], ports: ports[node.ciCode] } }));
  const laneY = Math.min(...props.nodes.map(node => coordinates[node.ciCode]?.y ?? 92)) - 66;
  for (let lane = 0; lane < topologyLaneLabels.length; lane++) {
    const points = props.nodes.filter(node => topologyLane(node) === lane).map(node => coordinates[node.ciCode]).filter((point): point is { x: number; y: number } => !!point);
    if (points.length) nodes.push({ id: `${lanePrefix}${lane}`, data: { lane: topologyLaneLabels[lane] }, style: { x: (Math.min(...points.map(point => point.x)) + Math.max(...points.map(point => point.x))) / 2, y: laneY, size: [136, 28], dy: -14 } });
  }
  return { nodes, edges: connections.map(({ id, edge, sourcePort, targetPort, router, controlPoints }) => ({ id, source: edge.sourceCiCode, target: edge.targetCiCode, data: { edge }, style: { labelText: '', sourcePort, targetPort, controlPoints: controlPoints ?? [], router: controlPoints ? false : router, lineDash: ['PUBLISHES_TO', 'CONSUMES_FROM', 'SENDS_TO'].includes(edge.relationType) ? [5, 3] : undefined } })) };
}
async function applyFocus() {
  if (!graph || !started || disposed) return;
  const node = props.nodes.some(item => item.ciCode === (hoveredNode.value || props.selected)) ? hoveredNode.value || props.selected : '';
  const states: Record<string, string[]> = {};
  for (const edge of graph.getEdgeData()) {
    const related = hoveredEdge.value ? edge.id === hoveredEdge.value : edge.source === node || edge.target === node;
    states[edge.id!] = node || hoveredEdge.value ? [related ? 'active' : 'inactive'] : [];
  }
  try { await graph.setElementState(states, false); } catch (cause) { if (!disposed) emit('error', cause instanceof Error ? cause.message : '关联路径更新失败'); }
}
function clearHover() { hoveredNode.value = ''; hoveredEdge.value = ''; hoveredRelation.value = undefined; void applyFocus(); }
function focusNode(event: FocusEvent) { const code = (event.target as HTMLElement)?.closest<HTMLElement>('[data-ci-code]')?.dataset.ciCode; if (code) { hoveredNode.value = code; void applyFocus(); } }
function pointerFocus(event: PointerEvent) {
  if (pointerDrag?.pointerId === event.pointerId && graph) {
    event.preventDefault(); event.stopPropagation();
    const dx = event.clientX - pointerDrag.clientX, dy = event.clientY - pointerDrag.clientY;
    if (Math.hypot(dx, dy) < 4 && !pointerDrag.moved) return;
    pointerDrag.moved = true; manualViewport = true;
    coordinates[pointerDrag.code] = { x: pointerDrag.x + dx / graph.getZoom(), y: pointerDrag.y + dy / graph.getZoom() };
    graph.updateNodeData([{ id: pointerDrag.code, style: coordinates[pointerDrag.code] }]);
    void graph.draw();
    return;
  }
  const target = event.target as HTMLElement;
  if (target.closest?.('.obs-minimap')) return;
  const code = target.closest?.<HTMLElement>('[data-ci-code]')?.dataset.ciCode || '';
  if (code === hoveredNode.value) return;
  hoveredNode.value = code;
  if (code) { hoveredEdge.value = ''; hoveredRelation.value = undefined; }
  void applyFocus();
}
function startPointerDrag(event: PointerEvent) {
  if (!props.editing || props.wallboard || event.button !== 0 || !graph || !host.value) return;
  const node = (event.target as HTMLElement).closest?.<HTMLElement>('[data-ci-code]');
  const code = node?.dataset.ciCode, point = code && coordinates[code];
  if (!code || !point || node.closest('.obs-minimap')) return;
  event.preventDefault(); event.stopPropagation();
  pointerDrag = { code, pointerId: event.pointerId, clientX: event.clientX, clientY: event.clientY, ...point, moved: false };
  host.value.setPointerCapture(event.pointerId);
  activateCanvas();
}
async function finishPointerDrag(event: PointerEvent) {
  if (!pointerDrag || pointerDrag.pointerId !== event.pointerId) return;
  event.preventDefault(); event.stopPropagation();
  const finished = pointerDrag; pointerDrag = undefined; ignoreClickUntil = Date.now() + 200;
  if (host.value?.hasPointerCapture(event.pointerId)) host.value.releasePointerCapture(event.pointerId);
  if (finished.moved) { await draw(); emit('change'); }
  else if (event.type !== 'pointercancel') emit('select', finished.code);
}
function captureCoordinates() { graph?.getNodeData().forEach(node => { if (!node.id.startsWith(lanePrefix) && Number.isFinite(Number(node.style?.x)) && Number.isFinite(Number(node.style?.y))) coordinates[node.id] = { x: Number(node.style?.x), y: Number(node.style?.y) }; }); }
async function revealSelection() {
  if (!graph || !host.value || disposed || !props.nodes.some(node => node.ciCode === props.selected)) return;
  const selected = [...host.value.querySelectorAll<HTMLElement>('[data-ci-code]')].find(node => node.dataset.ciCode === props.selected && !node.closest('.obs-minimap'));
  const box = selected?.getBoundingClientRect(), canvas = host.value.getBoundingClientRect();
  if (box && box.width > 0 && box.left >= canvas.left + 12 && box.right <= canvas.right - 12 && box.top >= canvas.top + 12 && box.bottom <= canvas.bottom - 12) return;
  await graph.focusElement(props.selected, false);
}
async function fitReadable() { if (!graph || disposed) return; await graph.fitView(); if (graph.getZoom() < .86) await graph.zoomTo(.86); if (props.compact && graph.getZoom() > 1.15) await graph.zoomTo(1.15); await revealSelection(); afterTransform(); }
async function draw(reset = false) {
  if (!graph || disposed) return; const current = ++revision;
  try {
    if (started && !reset) captureCoordinates();
    coordinates = reset ? initialTopologyPositions(props.nodes, props.compact, props.edges) : reconcileTopologyPositions(props.nodes, props.edges, props.layout, coordinates, props.compact);
    graph.setOptions({ layout: undefined });
    graph.setData(data());
    await graph.render();
    if (disposed || current !== revision) return;
    if (!started || reset) { await fitReadable(); started = true; manualViewport = false; }
    await applyFocus();
    zoom.value = Math.round(graph.getZoom() * 100);
  } catch (cause) { if (!disposed) emit('error', cause instanceof Error ? cause.message : '拓扑画布渲染失败'); }
}
async function autoLayout() { captureCoordinates(); previousCoordinates = structuredClone(coordinates); await draw(true); if (props.editing) emit('change'); }
async function undoLayout() { if (!previousCoordinates || !graph) return; coordinates = previousCoordinates; previousCoordinates = undefined; graph.setData(data()); await graph.render(); await fitReadable(); await applyFocus(); if (props.editing) emit('change'); }
async function restoreLayout() { coordinates = {}; previousCoordinates = undefined; started = false; await draw(); }
function afterTransform() { if (started && !disposed && graph) zoom.value = Math.round(graph.getZoom() * 100); }
async function fit() { if (!started || disposed || !graph) return; manualViewport = false; await graph.fitView(); afterTransform(); }
async function restoreView() { if (!started || disposed || !graph) return; manualViewport = false; await fitReadable(); }
function activateCanvas() { interactionActive.value = true; manualViewport = true; }
function leaveCanvas(event: PointerEvent) { if (!host.value?.contains(event.target as Node)) interactionActive.value = false; }
function leaveWithEscape(event: KeyboardEvent) { if (event.key === 'Escape') interactionActive.value = false; }
function markWheel(event: WheelEvent) { if (interactionActive.value) { event.preventDefault(); manualViewport = true; } }
async function zoomBy(factor: number) { if (!started || disposed || !graph) return; manualViewport = true; await graph.zoomBy(factor); afterTransform(); }
function positions(): LayoutPosition[] { captureCoordinates(); return Object.entries(coordinates).map(([ciCode, position]) => ({ ciCode, ...position })); }
function keySelect(event: KeyboardEvent) { if (props.wallboard || !['Enter', ' '].includes(event.key)) return; const node = (event.target as HTMLElement)?.closest<HTMLElement>('[data-ci-code]'); if (node?.dataset.ciCode) { event.preventDefault(); emit('select', node.dataset.ciCode); } }
defineExpose({ autoLayout, undoLayout, restoreLayout, fit, restoreView, zoomBy, positions, zoom, interactionActive, reset: () => draw(true) });
onMounted(async () => {
  await nextTick(); if (!host.value || disposed) return;
  sizeCanvas(); await nextTick();
  window.addEventListener('resize', sizeCanvas);
  document.addEventListener?.('fullscreenchange', sizeCanvas);
  surfaceResize = new ResizeObserver(sizeCanvas);
  const surface = host.value.closest('.oa-dashboard, .observability-workspace');
  if (surface) surfaceResize.observe(surface);
  host.value.addEventListener('keydown', keySelect);
  host.value.addEventListener('focusin', focusNode);
  host.value.addEventListener('focusout', clearHover);
  // HTML cards forward events through G6; native delegation keeps their hover stable after redraw.
  host.value.addEventListener('pointermove', pointerFocus, true);
  host.value.addEventListener('pointerdown', startPointerDrag, true);
  host.value.addEventListener('pointerup', finishPointerDrag, true);
  host.value.addEventListener('pointercancel', finishPointerDrag, true);
  host.value.addEventListener('pointerleave', clearHover);
  document.addEventListener('pointerdown', leaveCanvas);
  document.addEventListener('keydown', leaveWithEscape);
  graph = new Graph({ container: host.value, width: host.value.clientWidth || 900, height: host.value.clientHeight || 590, padding: TOPOLOGY_FIT_PADDING, animation: false, zoomRange: [.15, 2],
    node: { type: 'html', style: { size: [TOPOLOGY_NODE_WIDTH, TOPOLOGY_NODE_HEIGHT], dx: -TOPOLOGY_NODE_WIDTH / 2, dy: -TOPOLOGY_NODE_HEIGHT / 2, innerHTML: (datum: NodeData) => datum.data?.lane ? `<div class="obs-graph-lane">${escape(datum.data.lane)}</div>` : html((datum.data as { node: ServiceNode }).node) } },
    edge: { type: 'polyline', style: { radius: 24, stroke: '#397cff', lineWidth: 1.35, opacity: .6, endArrow: true, endArrowSize: 6 }, state: { active: { stroke: '#1f65ec', lineWidth: 2.1, opacity: 1, halo: false, zIndex: 2 }, inactive: { opacity: .14, lineWidth: 1 } } },
    behaviors: ['drag-canvas', { type: 'zoom-canvas', key: 'focused-zoom', preventDefault: false, enable: () => interactionActive.value }],
    plugins: !props.compact ? [{ type: 'minimap', size: [140, 84], position: 'right-top', className: 'obs-minimap', containerStyle: { right: '16px', top: '14px', border: '1px solid #dfe8fb', borderRadius: '8px', background: '#ffffffed' }, maskStyle: { border: '1px solid #3478ed', background: '#3478ed0a' } }] : [],
  });
  graph.on('node:click', event => { const code = String((event as IElementEvent).target.id); if (!props.wallboard && Date.now() >= ignoreClickUntil && !code.startsWith(lanePrefix)) emit('select', code); });
  graph.on('edge:pointerenter', event => { const id = String((event as IElementEvent).target.id); hoveredEdge.value = id; hoveredRelation.value = (graph?.getEdgeData(id)?.data as { edge?: ServiceRelation })?.edge; void applyFocus(); });
  graph.on('edge:pointerleave', clearHover);
  host.value.addEventListener('pointerdown', activateCanvas);
  host.value.addEventListener('wheel', markWheel, { passive: false });
  graph.on('aftertransform', afterTransform);
  resize = new ResizeObserver(() => { if (host.value && graph && !disposed) { graph.setSize(host.value.clientWidth, host.value.clientHeight); if (started && props.selected) void revealSelection(); else if (started && !manualViewport) void fitReadable(); } }); resize.observe(host.value);
  await draw();
});
watch(() => [props.nodes, props.edges, props.layout], () => { void draw(); });
watch(() => props.scopeKey, () => { coordinates = {}; previousCoordinates = undefined; started = false; void draw(); });
watch(() => [props.selected, props.showTraffic], async () => { if (!graph) return; graph.updateNodeData(props.nodes.map(node => ({ id: node.ciCode, data: { node } }))); await graph.draw(); await applyFocus(); });
watch(() => props.selected, async () => { await nextTick(); if (started) await revealSelection(); });
watch(() => props.editing, () => { if (!props.editing) void draw(); });
onBeforeUnmount(() => { disposed = true; revision++; pointerDrag = undefined; document.removeEventListener('pointerdown', leaveCanvas); document.removeEventListener('keydown', leaveWithEscape); host.value?.removeEventListener('pointerdown', activateCanvas); host.value?.removeEventListener('wheel', markWheel); window.removeEventListener('resize', sizeCanvas); document.removeEventListener?.('fullscreenchange', sizeCanvas); surfaceResize?.disconnect(); host.value?.removeEventListener('keydown', keySelect); host.value?.removeEventListener('focusin', focusNode); host.value?.removeEventListener('focusout', clearHover); host.value?.removeEventListener('pointermove', pointerFocus, true); host.value?.removeEventListener('pointerdown', startPointerDrag, true); host.value?.removeEventListener('pointerup', finishPointerDrag, true); host.value?.removeEventListener('pointercancel', finishPointerDrag, true); host.value?.removeEventListener('pointerleave', clearHover); resize?.disconnect(); const previous = graph; graph = undefined; previous?.off('aftertransform', afterTransform); previous?.destroy(); });
</script>
<template><div class="obs-topology-surface"><div class="obs-topology-canvas" :class="{ 'is-editing': editing, 'is-wallboard': wallboard, 'is-compact': compact, 'is-interaction-active': interactionActive }" :style="{ height: canvasHeight }" ref="host" role="group" tabindex="0" :aria-label="`服务依赖拓扑。${interactionActive ? '滚轮缩放已启用，按 Esc 退出' : '点击画布启用滚轮缩放'}`" @keydown.enter.self="activateCanvas" /><div class="obs-topology-relation" :class="{ 'has-relation': hoveredRelation }" role="status">{{ relationSummary }}</div></div></template>
