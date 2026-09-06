import type { ServiceNode } from '@/api/observability';

export const TOPOLOGY_NODE_WIDTH = 200;
export const TOPOLOGY_NODE_HEIGHT = 120;
export const TOPOLOGY_FIT_PADDING = 24;
export const topologyGridLayout = {
  type: 'grid', cols: 5, condense: true, preventOverlap: true,
  nodeSize: [TOPOLOGY_NODE_WIDTH, TOPOLOGY_NODE_HEIGHT], nodeSpacing: 32, sortBy: 'displayOrder',
};
const layers: Record<string, number> = {
  GATEWAY: 0, SERVICE: 1, JAVA_SERVICE: 1, QUEUE: 2, MESSAGE_QUEUE: 2,
  CACHE: 3, DATABASE: 4, SEARCH: 5, VECTOR_DB: 6, VECTOR_DATABASE: 6,
  REGISTRY: 7, GOVERNANCE: 8, MONITOR: 9, ALERT: 9, EXTERNAL_API: 10,
};
export function orderedTopologyNodes(nodes: ServiceNode[]) {
  return [...nodes].sort((left, right) => (layers[left.ciType] ?? 11) - (layers[right.ciType] ?? 11)
    || left.ciCode.localeCompare(right.ciCode, 'en'));
}
