import type { ServiceNode, ServiceRelation } from '@/api/observability';

export type GraphInputMode = 'mouse' | 'touchpad';
export const governanceRelations = new Set(['MONITORED_BY', 'CONFIGURED_BY', 'REGISTERED_WITH', 'REGISTERS_TO', 'GOVERNED_BY', 'MANAGED_BY']);
export function nodeGroup(node: ServiceNode) {
  if (node.environment === 'DEMO') return 'demo';
  if (['GATEWAY', 'SERVICE', 'JAVA_SERVICE'].includes(node.ciType)) return 'application';
  if (node.ciType === 'EXTERNAL_API') return 'external';
  return 'infrastructure';
}
export const nodeGroupLabels: Record<string, string> = { application: '应用服务', infrastructure: '基础设施', external: '外部依赖', demo: '隔离演示' };
export function relationKey(edge: ServiceRelation, index: number) {
  return edge.id != null ? `relation-${edge.id}` : `relation-${edge.sourceCiCode}:${edge.targetCiCode}:${edge.relationType}:${edge.relationSource || ''}:${index}`;
}
export function graphStructure(nodes: ServiceNode[], edges: ServiceRelation[]) {
  return JSON.stringify([nodes.map(node => node.ciCode).sort(), edges.map((edge, index) => `${relationKey(edge, index)}:${edge.sourceCiCode}:${edge.targetCiCode}`).sort()]);
}
export function neighborhood(nodes: ServiceNode[], edges: ServiceRelation[], selected: string, hops: number, governance = false) {
  const visibleEdges = edges.filter(edge => governance || !governanceRelations.has(edge.relationType));
  if (!selected || !hops) return { nodes, edges: visibleEdges };
  const visible = new Set([selected]); let frontier = new Set([selected]);
  for (let hop = 0; hop < hops; hop++) {
    const next = new Set<string>();
    for (const edge of visibleEdges) {
      if (frontier.has(edge.sourceCiCode) && !visible.has(edge.targetCiCode)) next.add(edge.targetCiCode);
      if (frontier.has(edge.targetCiCode) && !visible.has(edge.sourceCiCode)) next.add(edge.sourceCiCode);
    }
    next.forEach(code => visible.add(code)); frontier = next;
  }
  return { nodes: nodes.filter(node => visible.has(node.ciCode)), edges: visibleEdges.filter(edge => visible.has(edge.sourceCiCode) && visible.has(edge.targetCiCode)) };
}
export function wheelIntent(event: Pick<WheelEvent, 'deltaX' | 'deltaY' | 'deltaMode' | 'ctrlKey' | 'metaKey' | 'altKey'>, mode: GraphInputMode, height: number) {
  const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? height : 1;
  const x = Math.max(-180, Math.min(180, event.deltaX * unit));
  const y = Math.max(-180, Math.min(180, event.deltaY * unit));
  // Browser Ctrl/Cmd zoom remains available in mouse mode. Touchpad pinch emits Ctrl+wheel.
  if (mode === 'mouse' && (event.ctrlKey || event.metaKey)) return { kind: 'browser' as const };
  if (mode === 'touchpad' && !(event.ctrlKey || event.altKey)) return { kind: 'pan' as const, x: -x, y: -y };
  return { kind: 'zoom' as const, factor: Math.exp(-y * .004) };
}
export interface StoredGraphView { version: 1; structure: string; zoom: number; position: [number, number]; positions: Record<string, { x: number; y: number }> }
export function readGraphView(key?: string): StoredGraphView | undefined {
  if (!key) return;
  try {
    const value = JSON.parse(localStorage.getItem(`opsagent-graph:${key}`) || 'null');
    if (value?.version !== 1 || !Number.isFinite(value.zoom) || value.zoom < .15 || value.zoom > 2 || !Array.isArray(value.position)
      || value.position.length !== 2 || !value.position.every(Number.isFinite)) return;
    const positions: StoredGraphView['positions'] = {};
    for (const [code, point] of Object.entries(value.positions || {}) as Array<[string, { x: number; y: number }]>) {
      if (point && Number.isFinite(point.x) && Number.isFinite(point.y)) positions[code] = point;
    }
    return { ...value, positions };
  } catch { return; }
}
