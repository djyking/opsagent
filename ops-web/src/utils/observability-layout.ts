import type { ServiceNode, ServiceRelation } from '@/api/observability';
import type { NodePortStyleProps, PolylineStyleProps } from '@antv/g6';
import { separateTopologyRoutes, type RouteObstacle, type RoutePoint, type RouteRequest } from './observability-routing';
type ShortestPathRouter = Extract<PolylineStyleProps['router'], { type: 'shortest-path' }>;

export type TopologyPositions = Record<string, { x: number; y: number }>;
export const TOPOLOGY_NODE_WIDTH = 136;
export const TOPOLOGY_NODE_HEIGHT = 78;
export const TOPOLOGY_FIT_PADDING = 24;
export const topologyGridLayout = {
  type: 'grid', cols: 5, condense: true, preventOverlap: true,
  nodeSize: [TOPOLOGY_NODE_WIDTH, TOPOLOGY_NODE_HEIGHT], nodeSpacing: 32, sortBy: 'displayOrder',
};
const layers: Record<string, number> = {
  GATEWAY: 0, SERVICE: 1, JAVA_SERVICE: 1, QUEUE: 2, MESSAGE_QUEUE: 2,
  CACHE: 2, DATABASE: 2, SEARCH: 2, VECTOR_DB: 2, VECTOR_DATABASE: 2,
  REGISTRY: 3, GOVERNANCE: 3, MONITOR: 3, ALERT: 3, EXTERNAL_API: 4,
  APPLICATION: 1, MYSQL: 2, POSTGRESQL: 2, REDIS: 2, RABBITMQ: 2,
  ELASTICSEARCH: 2, QDRANT: 2, NACOS: 3, PROMETHEUS: 3, GRAFANA: 3,
};
export function orderedTopologyNodes(nodes: ServiceNode[]) {
  return [...nodes].sort((left, right) => topologyLane(left) - topologyLane(right)
    || left.ciCode.localeCompare(right.ciCode, 'en'));
}
/** Presentation roles do not mutate CMDB types. Gateway is registered as SERVICE in older data. */
export function topologyLane(node: ServiceNode) {
  if (node.ciType === 'GATEWAY' || /(^|[-_])gateway($|[-_])/i.test(node.ciCode)) return 0;
  if (/prometheus|alertmanager|grafana|sentinel|nacos/i.test(node.ciCode)) return 3;
  return layers[node.ciType] ?? 3;
}
export const topologyLaneLabels = ['入口', '业务服务', '数据与消息', '治理与采集', '外部依赖'];
function valid(position?: { x: number; y: number }) { return position && Number.isFinite(position.x) && Number.isFinite(position.y) && Math.abs(position.x) <= 100000 && Math.abs(position.y) <= 100000; }
/** Barycentre sweeps use real dependencies while preserving the horizontal role bands. */
export function dependencyOrderedTopologyNodes(nodes: ServiceNode[], edges: ServiceRelation[], maximumRows = 4) {
  const groups = topologyLaneLabels.map((_, lane) => orderedTopologyNodes(nodes).filter(node => topologyLane(node) === lane));
  const lanes = new Map(nodes.map(node => [node.ciCode, topologyLane(node)]));
  const neighbours = new Map(nodes.map(node => [node.ciCode, new Set<string>()]));
  for (const edge of edges) {
    if (!lanes.has(edge.sourceCiCode) || !lanes.has(edge.targetCiCode)) continue;
    neighbours.get(edge.sourceCiCode)!.add(edge.targetCiCode);
    neighbours.get(edge.targetCiCode)!.add(edge.sourceCiCode);
  }
  for (let pass = 0; pass < 4; pass++) {
    for (const direction of [1, -1]) {
      for (const lane of direction === 1 ? [0, 1, 2, 3, 4] : [4, 3, 2, 1, 0]) {
        const rank = new Map<string, number>();
        groups.forEach(group => {
          const columns = Math.max(1, Math.ceil(group.length / maximumRows));
          const rows = Math.max(1, Math.ceil(group.length / columns));
          group.forEach((node, index) => rank.set(node.ciCode, (Math.floor(index / columns) + .5) / rows));
        });
        const score = (node: ServiceNode) => {
          const adjacent = [...neighbours.get(node.ciCode)!].filter(code => direction * (lane - lanes.get(code)!) > 0);
          return adjacent.length ? adjacent.reduce((sum, code) => sum + rank.get(code)!, 0) / adjacent.length : rank.get(node.ciCode)!;
        };
        const scores = new Map(groups[lane]!.map(node => [node.ciCode, score(node)]));
        groups[lane]!.sort((a, b) => scores.get(a.ciCode)! - scores.get(b.ciCode)! || a.ciCode.localeCompare(b.ciCode, 'en'));
      }
    }
  }
  return groups.flat();
}
export function initialTopologyPositions(nodes: ServiceNode[], compact = false, edges: ServiceRelation[] = []): TopologyPositions {
  const positions: TopologyPositions = {}; let left = 90;
  const maximumRows = compact ? 3 : 4;
  const ordered = dependencyOrderedTopologyNodes(nodes, edges, maximumRows);
  const maxRows = Math.min(maximumRows, Math.max(1, ...Array.from({ length: 5 }, (_, lane) => nodes.filter(node => topologyLane(node) === lane).length)));
  for (let lane = 0; lane < 5; lane++) {
    const group = ordered.filter(node => topologyLane(node) === lane);
    if (!group.length) continue;
    const columns = Math.ceil(group.length / maximumRows);
    const rows = Math.ceil(group.length / columns);
    group.forEach((node, index) => { positions[node.ciCode] = { x: left + index % columns * 188, y: 102 + (maxRows - rows) * 74 + Math.floor(index / columns) * 148 }; });
    left += columns * 188 + 40;
  }
  return positions;
}
/** Refresh, filtering and discovery never move known nodes. Only explicit auto-layout reflows. */
export function reconcileTopologyPositions(nodes: ServiceNode[], edges: ServiceRelation[], saved: TopologyPositions = {}, current: TopologyPositions = {}, compact = false): TopologyPositions {
  const result: TopologyPositions = { ...current }; const initial = initialTopologyPositions(nodes, compact, edges);
  for (const node of nodes) if (!valid(result[node.ciCode]) && valid(saved[node.ciCode])) result[node.ciCode] = { ...saved[node.ciCode]! };
  if (!Object.keys(result).length) return initial;
  for (const node of orderedTopologyNodes(nodes)) {
    if (valid(result[node.ciCode])) continue;
    if (valid(saved[node.ciCode])) { result[node.ciCode] = { ...saved[node.ciCode]! }; continue; }
    const neighbour = edges.flatMap(edge => edge.sourceCiCode === node.ciCode ? [edge.targetCiCode] : edge.targetCiCode === node.ciCode ? [edge.sourceCiCode] : []).find(code => valid(result[code]));
    const point = { ...initial[node.ciCode]! };
    if (neighbour) point.y = result[neighbour]!.y;
    for (let attempt = 0; Object.values(result).some(other => Math.abs(point.x - other.x) < TOPOLOGY_NODE_WIDTH + 20 && Math.abs(point.y - other.y) < TOPOLOGY_NODE_HEIGHT + 18) && attempt < 500; attempt++) point.y += 90;
    result[node.ciCode] = point;
  }
  return result;
}

/** Unique invisible ports separate shared endpoints; G6 routes around visible node bounds. */
export function topologyConnections(nodes: ServiceNode[], edges: ServiceRelation[], positions: TopologyPositions) {
  const codes = new Set(nodes.map(node => node.ciCode));
  const ports: Record<string, NodePortStyleProps[]> = Object.fromEntries(nodes.map(node => [node.ciCode, []]));
  const connections = edges.filter(edge => codes.has(edge.sourceCiCode) && codes.has(edge.targetCiCode)).map((edge, index) => {
    const source = positions[edge.sourceCiCode] ?? { x: 0, y: 0 };
    const target = positions[edge.targetCiCode] ?? { x: 0, y: 0 };
    const sourceSide: RouteRequest['sourceSide'] = target.x < source.x ? 'left' : 'right';
    const targetSide: RouteRequest['targetSide'] = target.x > source.x ? 'left' : 'right';
    const id = `relation-${edge.id ?? `${edge.sourceCiCode}-${edge.targetCiCode}-${index}`}`;
    const router: ShortestPathRouter = { type: 'shortest-path', enableObstacleAvoidance: true, gridSize: 8, offset: 8 + index % 3 * 3, maximumLoops: 6000, startDirections: [sourceSide], endDirections: [targetSide] };
    return { id, edge, sourceSide, targetSide, sourcePort: `${id}-source`, targetPort: `${id}-target`, router };
  });
  for (const node of nodes) {
    for (const side of ['left', 'right'] as const) {
      const endpoints = connections.flatMap(connection => [
        ...(connection.edge.sourceCiCode === node.ciCode && connection.sourceSide === side ? [{ key: connection.sourcePort, neighbour: connection.edge.targetCiCode }] : []),
        ...(connection.edge.targetCiCode === node.ciCode && connection.targetSide === side ? [{ key: connection.targetPort, neighbour: connection.edge.sourceCiCode }] : []),
      ]).sort((a, b) => (positions[a.neighbour]?.y ?? 0) - (positions[b.neighbour]?.y ?? 0) || a.key.localeCompare(b.key, 'en'));
      endpoints.forEach((endpoint, index) => ports[node.ciCode]!.push({ key: endpoint.key, placement: [side === 'left' ? 0 : 1, .2 + .6 * (index + .5) / endpoints.length] }));
    }
  }
  const obstacles: RouteObstacle[] = nodes.flatMap(node => {
    const position = positions[node.ciCode];
    return position ? [{ left: position.x - TOPOLOGY_NODE_WIDTH / 2, right: position.x + TOPOLOGY_NODE_WIDTH / 2, top: position.y - TOPOLOGY_NODE_HEIGHT / 2, bottom: position.y + TOPOLOGY_NODE_HEIGHT / 2 }] : [];
  });
  const laneY = Math.min(...nodes.map(node => positions[node.ciCode]?.y ?? 92)) - 66;
  for (let lane = 0; lane < topologyLaneLabels.length; lane++) {
    const group = nodes.filter(node => topologyLane(node) === lane).map(node => positions[node.ciCode]).filter((position): position is { x: number; y: number } => !!position);
    if (!group.length) continue;
    const center = (Math.min(...group.map(point => point.x)) + Math.max(...group.map(point => point.x))) / 2;
    obstacles.push({ left: center - 68, right: center + 68, top: laneY - 14, bottom: laneY + 14 });
  }
  const portPoint = (code: string, key: string): RoutePoint => {
    const placement = ports[code]!.find(port => port.key === key)!.placement as [number, number];
    const position = positions[code] ?? { x: 0, y: 0 };
    return [position.x + (placement[0] - .5) * TOPOLOGY_NODE_WIDTH, position.y + (placement[1] - .5) * TOPOLOGY_NODE_HEIGHT];
  };
  const routes = separateTopologyRoutes(connections.map(connection => ({ id: connection.id, source: portPoint(connection.edge.sourceCiCode, connection.sourcePort), target: portPoint(connection.edge.targetCiCode, connection.targetPort), sourceSide: connection.sourceSide, targetSide: connection.targetSide })), obstacles);
  return { ports, connections: connections.map(connection => ({ ...connection, controlPoints: routes[connection.id] })) };
}
