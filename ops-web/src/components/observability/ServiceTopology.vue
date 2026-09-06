<script setup lang="ts">
import { h, nextTick, onBeforeUnmount, onMounted, ref, render, watch } from 'vue';
import { Graph, type ElementDatum, type IElementEvent, type NodeData } from '@antv/g6';
import '@/styles/pages/observability.css';
import type { ServiceNode, ServiceRelation, LayoutPosition } from '@/api/observability';
import { ciType } from '@/components/cmdb/topology';
import { effectiveHealth, observationLabels, healthColors, healthLabels, metric, observationTime, relationLabels } from '@/utils/observability';
import { graphStructure, nodeGroup, nodeGroupLabels, readGraphView, relationKey, wheelIntent, type GraphInputMode } from '@/utils/topology-view';
import { orderedTopologyNodes, topologyGridLayout, TOPOLOGY_FIT_PADDING, TOPOLOGY_NODE_WIDTH, TOPOLOGY_NODE_HEIGHT } from '@/utils/observability-layout';

const props = withDefaults(defineProps<{ nodes: ServiceNode[]; edges: ServiceRelation[]; selected?: string; editing?: boolean; showTraffic?: boolean; layout?: Record<string, { x: number; y: number }>; wallboard?: boolean; compact?: boolean; storageKey?: string; minimap?: boolean; inputMode?: GraphInputMode }>(), { selected: '', editing: false, showTraffic: false, wallboard: false, compact: false, minimap: false, inputMode: 'mouse' });
const emit = defineEmits<{ select: [code: string]; edgeSelect: [edge: ServiceRelation]; change: []; error: [message: string] }>();
const host = ref<HTMLDivElement>(); const zoom = ref(100); const layoutChanged = ref(false);
let graph: Graph | undefined; let resize: ResizeObserver | undefined; let disposed = false; let revision = 0; let started = false; let structure = ''; let queue = Promise.resolve(); let spaceHeld = false; let pointerStart: { x: number; y: number } | undefined; let dragged = false; let activeStorageKey: string | undefined; let lastPersistAt = 0;
const remembered = new Map<string, { x: number; y: number }>();
const iconMarkup = new Map<string, string>();
function escape(value: unknown) { return String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char]!); }
function icon(node: ServiceNode) { if (!iconMarkup.has(node.ciType)) { const box = document.createElement('span'); render(h(ciType(node.ciType).icon, { size: 19, 'stroke-width': 1.8 }), box); const svg = box.innerHTML; render(null, box); iconMarkup.set(node.ciType, svg); } return iconMarkup.get(node.ciType); }
function html(node: ServiceNode) {
  const state = effectiveHealth(node); const color = healthColors[state]; const m = node.metrics; const group = nodeGroup(node);
  return `<div class="obs-graph-node group-${group} ${props.selected === node.ciCode ? 'is-selected' : ''} ${node.drilling ? 'is-drilling' : ''}" role="${props.wallboard ? 'img' : 'button'}" tabindex="${props.wallboard ? '-1' : '0'}" data-ci-code="${escape(node.ciCode)}" style="--health-color:${color}" title="${escape(node.ciName)} · ${escape(node.observation?.message || node.statusReason || healthLabels[state])}"><div class="obs-graph-node-heading"><span class="obs-graph-icon">${icon(node)}</span><strong title="${escape(node.ciName)}">${escape(node.ciName)}</strong><i></i></div><div class="obs-graph-code">${escape(ciType(node.ciType).label)} · ${nodeGroupLabels[group]}</div><div class="obs-graph-metrics"><span>RPS <b>${escape(metric(m?.rps))}</b></span><span>P95 <b>${escape(metric(m?.p95Ms, ' ms', 0))}</b></span></div><div class="obs-graph-foot"><span>${healthLabels[state]}${node.drilling ? ' · 演练' : ''}</span>${node.activeAlertCount ? `<small>${Number(node.activeAlertCount)} 活动告警</small>` : `<small>${node.observation ? observationLabels[node.observation.status] : '查看证据 →'}</small>`}</div></div>`;
}
function data(useSaved = true) {
  return { nodes: orderedTopologyNodes(props.nodes).map((node, index) => ({ id: node.ciCode, data: { node, displayOrder: props.nodes.length - index }, style: useSaved && props.layout?.[node.ciCode] ? { ...props.layout[node.ciCode] } : undefined })),
    edges: props.edges.map((edge, index) => ({ id: relationKey(edge, index), source: edge.sourceCiCode, target: edge.targetCiCode, data: { edge }, style: {
      labelText: props.showTraffic && (!props.selected || edge.sourceCiCode === props.selected || edge.targetCiCode === props.selected) ? `${relationLabels[edge.relationType] || edge.relationType}${edge.rps == null ? '' : ` · ${edge.metricsScope === 'SAMPLED_TRACE_PAIRS' ? '采样 ' : ''}${metric(edge.rps, '/s')}`}` : '',
      lineDash: edge.relationSource === 'OBSERVED' ? [5, 3] : undefined,
      opacity: props.selected && edge.sourceCiCode !== props.selected && edge.targetCiCode !== props.selected ? .28 : .85,
    } })) };
}
function rememberPositions() {
  graph?.getNodeData().forEach(node => { if (Number.isFinite(Number(node.style?.x)) && Number.isFinite(Number(node.style?.y))) remembered.set(node.id, { x: Number(node.style?.x), y: Number(node.style?.y) }); });
}
function persist() {
  if (!started || !graph || !activeStorageKey || props.editing) return;
  rememberPositions();
  try { const point = graph.getPosition(); localStorage.setItem(`opsagent-graph:${activeStorageKey}`, JSON.stringify({ version: 1, structure, zoom: graph.getZoom(), position: [point[0], point[1]], positions: Object.fromEntries(remembered) })); } catch { /* Storage may be unavailable; canvas interaction remains usable. */ }
}
function draw(reset = false, restoreSaved = false) {
  queue = queue.then(async () => {
    if (!graph || disposed) return;
    const current = ++revision; const nextStructure = graphStructure(props.nodes, props.edges); const next = data(!reset);
    try {
      if (!started || reset || restoreSaved) {
        if (!started) activeStorageKey = props.storageKey;
        const previous = !started && !reset ? readGraphView(activeStorageKey) : undefined;
        if (previous) next.nodes.forEach(node => { node.style = previous.positions[node.id] || node.style; });
        const saved = next.nodes.every(node => !!node.style);
        graph.setOptions({ layout: saved ? undefined : topologyGridLayout });
        graph.setData(next); await graph.render();
        if (disposed || current !== revision) return;
        if (previous) { await graph.zoomTo(previous.zoom, false); await graph.translateTo(previous.position, false); }
        else if (!started || reset) await graph.fitView();
        started = true; layoutChanged.value = false;
      } else if (nextStructure !== structure) {
        rememberPositions();
        const maxY = Math.max(0, ...Array.from(remembered.values()).map(point => point.y)); let added = 0;
        next.nodes.forEach(node => { const point = remembered.get(node.id); node.style = point || node.style || { x: (added % 5) * 232, y: maxY + 152 + Math.floor(added++ / 5) * 152 }; });
        graph.setOptions({ layout: undefined }); graph.setData(next); await graph.draw();
        layoutChanged.value = true;
      } else {
        // Metrics/state refresh is a pure data patch: no setData, render, layout or fit.
        graph.updateNodeData(next.nodes.map(({ id, data }) => ({ id, data })));
        graph.updateEdgeData(next.edges); await graph.draw();
      }
      if (disposed || !graph || current !== revision) return;
      structure = nextStructure; rememberPositions(); zoom.value = Math.round(graph.getZoom() * 100); persist();
    } catch (cause) { if (!disposed) emit('error', cause instanceof Error ? cause.message : '拓扑画布渲染失败'); }
  });
  return queue;
}
async function autoLayout() { await draw(true); if (props.editing) emit('change'); }
async function fit() { if (!started || disposed) return; await graph?.fitView(); zoom.value = Math.round((graph?.getZoom() || 1) * 100); persist(); }
async function focusSelected() { if (!started || disposed) return; if (props.selected && graph?.getNodeData().some(node => node.id === props.selected)) await graph.focusElement(props.selected, false); persist(); }
async function zoomBy(factor: number) { if (!started || disposed) return; await graph?.zoomBy(factor); zoom.value = Math.round((graph?.getZoom() || 1) * 100); persist(); }
function positions(): LayoutPosition[] { return graph?.getNodeData().filter(node => !props.nodes.find(item => item.ciCode === node.id)?.virtual).map(node => ({ ciCode: node.id, x: Number(node.style?.x || 0), y: Number(node.style?.y || 0) })) || []; }
function inputTarget(target: EventTarget | null) { return !!(target as HTMLElement)?.closest?.('input, textarea, select, [contenteditable="true"]'); }
function keySelect(event: KeyboardEvent) {
  if (!started || disposed || props.wallboard || props.compact || inputTarget(event.target)) return;
  if (event.key === 'Escape') { spaceHeld = false; return; }
  const node = (event.target as HTMLElement)?.closest<HTMLElement>('[data-ci-code]');
  if (event.key === ' ') { event.preventDefault(); spaceHeld = true; }
  if (event.key === 'Enter' && node?.dataset.ciCode) { event.preventDefault(); emit('select', node.dataset.ciCode); }
  if (event.key === '+' || event.key === '=') { event.preventDefault(); void zoomBy(1.2); }
  if (event.key === '-') { event.preventDefault(); void zoomBy(1 / 1.2); }
  const steps: Record<string, [number, number]> = { ArrowLeft: [40, 0], ArrowRight: [-40, 0], ArrowUp: [0, 40], ArrowDown: [0, -40] };
  if (steps[event.key] && !node) { event.preventDefault(); void graph?.translateBy(steps[event.key]!, false); }
}
function keyUp(event: KeyboardEvent) { if (event.key === ' ') { spaceHeld = false; const node = (event.target as HTMLElement)?.closest<HTMLElement>('[data-ci-code]'); if (!dragged && node?.dataset.ciCode && !props.compact && !props.wallboard) { event.preventDefault(); emit('select', node.dataset.ciCode); } } }
function blur() { spaceHeld = false; }
function pointerDown(event: PointerEvent) { pointerStart = { x: event.clientX, y: event.clientY }; dragged = false; }
function pointerMove(event: PointerEvent) { if (pointerStart && event.buttons && Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y) > 5) dragged = true; }
function wheel(event: WheelEvent) {
  if (!started || disposed || props.compact || props.wallboard || !graph || !host.value || inputTarget(event.target)) return;
  const intent = wheelIntent(event, props.inputMode, host.value.clientHeight);
  if (intent.kind === 'browser') return;
  event.preventDefault(); event.stopPropagation();
  if (intent.kind === 'pan') void graph.translateBy([intent.x, intent.y], false);
  else { const rect = host.value.getBoundingClientRect(); void graph.zoomBy(intent.factor, false, [event.clientX - rect.left, event.clientY - rect.top]); }
}
function afterTransform() {
  // G6 emits its initial transform inside the viewport constructor, before assigning it.
  if (!started || disposed || !graph) return;
  zoom.value = Math.round(graph.getZoom() * 100);
  const now = performance.now(); if (now - lastPersistAt > 200) { lastPersistAt = now; persist(); }
}
defineExpose({ autoLayout, fit, focusSelected, zoomBy, positions, zoom, layoutChanged, reset: () => draw(false, true) });
onMounted(async () => {
  await nextTick(); if (!host.value || disposed) return;
  host.value.addEventListener('keydown', keySelect); host.value.addEventListener('keyup', keyUp); host.value.addEventListener('blur', blur);
  host.value.addEventListener('wheel', wheel, { passive: false }); host.value.addEventListener('pointerdown', pointerDown, true); host.value.addEventListener('pointermove', pointerMove, true);
  graph = new Graph({ container: host.value, width: host.value.clientWidth || 900, height: host.value.clientHeight || 590, padding: TOPOLOGY_FIT_PADDING, animation: false, zoomRange: [.15, 2],
    node: { type: 'html', style: { size: [TOPOLOGY_NODE_WIDTH, TOPOLOGY_NODE_HEIGHT], dx: -TOPOLOGY_NODE_WIDTH / 2, dy: -TOPOLOGY_NODE_HEIGHT / 2, innerHTML: (datum: NodeData) => html((datum.data as { node: ServiceNode }).node) } },
    edge: { type: 'quadratic', style: { stroke: getComputedStyle(host.value).getPropertyValue('--oa-border-default').trim() || '#a8b7d1', lineWidth: 1.35, endArrow: true, endArrowSize: 7, labelFontSize: 11, labelFill: '#61718c', labelBackground: true, labelBackgroundFill: '#f6f9fe', labelPadding: [3, 5] }, state: { active: { stroke: '#596ff6', lineWidth: 2.2 } } },
    behaviors: [{ type: 'drag-canvas', enable: (event: IElementEvent) => !props.compact && !props.wallboard && (event.targetType === 'canvas' || spaceHeld) }, { type: 'drag-element', key: 'node-drag', enable: (event?: IElementEvent) => props.editing && !spaceHeld && !props.nodes.find(node => node.ciCode === event?.target?.id)?.virtual, animation: false, dropEffect: 'none' }],
    transforms: [{ type: 'process-parallel-edges', mode: 'bundle', distance: 18 }],
    plugins: [{ type: 'tooltip', enable: (event: IElementEvent) => event.targetType === 'edge', getContent: async (_event: IElementEvent, items: ElementDatum[]) => { const box = document.createElement('div'); const edge = (items[0]?.data as { edge?: ServiceRelation })?.edge; if (!edge) return box; box.className = 'obs-edge-tooltip'; box.textContent = `${edge.sourceCiCode} → ${edge.targetCiCode}\n${relationLabels[edge.relationType] || edge.relationType} · ${edge.relationSource || 'CMDB'}\n${edge.metricsScope === 'SAMPLED_TRACE_PAIRS' ? '采样调用对 /s' : 'RPS'} ${metric(edge.rps)} · Error ${metric(edge.errorRate == null ? null : edge.errorRate, '%')} · P95 ${metric(edge.p95Ms, 'ms')}\n${edge.windowStart ? observationTime(edge.windowStart) + ' — ' + observationTime(edge.windowEnd) : ''}\n点击查看该调用方向的保留 Trace`; return box; } }],
  });
  graph.on('node:click', event => { if (!props.wallboard && !dragged) emit('select', String((event as IElementEvent).target.id)); });
  graph.on('edge:click', event => { if (props.wallboard || props.compact || dragged) return; const selected = props.edges.find((edge, index) => relationKey(edge, index) === String((event as IElementEvent).target.id)); if (selected) emit('edgeSelect', selected); });
  graph.on('node:dragend', () => { if (props.editing) { rememberPositions(); emit('change'); } });
  graph.on('aftertransform', afterTransform);
  resize = new ResizeObserver(() => { if (host.value && graph && !disposed) graph.setSize(host.value.clientWidth, host.value.clientHeight); }); resize.observe(host.value);
  await draw();
});
watch(() => [props.nodes, props.edges, props.layout], () => { void draw(); });
watch(() => [props.selected, props.showTraffic], () => { void draw(); });
watch(() => props.minimap, enabled => { if (!graph) return; graph.setPlugins(plugins => [...plugins.filter(plugin => typeof plugin !== 'object' || plugin.key !== 'service-minimap'), ...(enabled ? [{ type: 'minimap', key: 'service-minimap', size: [180, 110] as [number, number], position: 'right-bottom' as const, delay: 200 }] : [])]); });
watch(() => props.storageKey, () => { persist(); started = false; remembered.clear(); structure = ''; void draw(); });
onBeforeUnmount(() => { persist(); disposed = true; revision++;
  host.value?.removeEventListener('keydown', keySelect); host.value?.removeEventListener('keyup', keyUp); host.value?.removeEventListener('blur', blur);
  host.value?.removeEventListener('wheel', wheel); host.value?.removeEventListener('pointerdown', pointerDown, true); host.value?.removeEventListener('pointermove', pointerMove, true);
  resize?.disconnect(); const previous = graph; graph = undefined;
  previous?.off('aftertransform', afterTransform); previous?.destroy();
});
</script>
<template><div class="obs-topology-canvas" :class="{ 'is-editing': editing, 'is-wallboard': wallboard, 'is-compact': compact }" ref="host" :tabindex="compact || wallboard ? -1 : 0" role="region" aria-label="服务有向依赖拓扑，节点可点击查看详情，方向键平移，加减键缩放" /></template>
