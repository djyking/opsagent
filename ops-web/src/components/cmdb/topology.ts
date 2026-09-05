import { Activity, Bell, Boxes, Database, Globe, Layers3, Network, Radio, Search, Server, Workflow } from "@lucide/vue";

export type CiRecord = Record<string, unknown>;
export interface CiNode extends CiRecord { ciCode: string; ciName: string; ciType: string }
export interface CiEdge { sourceCiCode: string; targetCiCode: string; relationType: string; description: string; key: string }
export interface PositionedNode { node: CiNode; x: number; y: number; role: string }
export const NODE_WIDTH = 224;
export const NODE_HEIGHT = 126;
export const ciTypes = {
  SERVICE: { label: "服务", icon: Server, tone: "service" },
  DATABASE: { label: "数据库", icon: Database, tone: "database" },
  CACHE: { label: "缓存", icon: Layers3, tone: "cache" },
  QUEUE: { label: "消息队列", icon: Radio, tone: "queue" },
  MESSAGE_QUEUE: { label: "消息队列", icon: Radio, tone: "queue" },
  GATEWAY: { label: "网关", icon: Globe, tone: "gateway" },
  ALERT: { label: "告警", icon: Bell, tone: "alert" },
  MONITOR: { label: "监控", icon: Activity, tone: "monitor" },
  REGISTRY: { label: "注册中心", icon: Network, tone: "registry" },
  SEARCH: { label: "搜索", icon: Search, tone: "search" },
  VECTOR_DB: { label: "向量库", icon: Boxes, tone: "database" },
};
export function ciType(value: unknown) {
  const key = String(value || "");
  return ciTypes[key as keyof typeof ciTypes] || { label: key || "配置项", icon: Workflow, tone: "other" };
}
export const relationNames: Record<string, string> = { DEPENDS_ON: "依赖", CALLS: "调用", READS: "读取", WRITES: "写入", PUBLISHES_TO: "发布到", CONSUMES_FROM: "消费自", ROUTES_TO: "路由到", SENDS_TO: "发送至", REGISTERS_TO: "注册到" };
export const environmentNames: Record<string, string> = { PROD: "生产", STAGING: "预发布", TEST: "测试", DEV: "开发" };

export function normalizeNodes(nodes: CiRecord[], root?: CiRecord): CiNode[] {
  const result = new Map<string, CiNode>();
  // An isolated CI is returned by the API as root, with an empty nodes array.
  for (const node of [...nodes, ...(root ? [root] : [])]) {
    const code = String(node.ciCode || "");
    if (code) result.set(code, { ...node, ciCode: code, ciName: String(node.ciName || code), ciType: String(node.ciType || "") });
  }
  return [...result.values()];
}

export function normalizeEdges(edges: CiRecord[]): CiEdge[] {
  return edges.map((edge, index) => ({ sourceCiCode: String(edge.sourceCiCode || ""), targetCiCode: String(edge.targetCiCode || ""), relationType: String(edge.relationType || ""), description: String(edge.description || ""), key: `${edge.sourceCiCode}:${edge.targetCiCode}:${edge.relationType}:${index}` }));
}

export function layoutTopology(nodes: CiNode[], edges: CiEdge[], rootCode: string) {
  const incoming = new Set(edges.filter(edge => edge.targetCiCode === rootCode).map(edge => edge.sourceCiCode));
  const outgoing = new Set(edges.filter(edge => edge.sourceCiCode === rootCode).map(edge => edge.targetCiCode));
  const byName = (a: CiNode, b: CiNode) => a.ciName.localeCompare(b.ciName, "zh-CN");
  const groups = [
    { key: "incoming", title: "入向关联", detail: "指向当前配置项", nodes: nodes.filter(node => node.ciCode !== rootCode && incoming.has(node.ciCode) && !outgoing.has(node.ciCode)).sort(byName) },
    { key: "root", title: "当前配置项", detail: "本次查看的中心", nodes: nodes.filter(node => node.ciCode === rootCode) },
    { key: "outgoing", title: "出向关联", detail: "从当前配置项出发", nodes: nodes.filter(node => node.ciCode !== rootCode && outgoing.has(node.ciCode)).sort(byName) },
    { key: "other", title: "其他关联项", detail: "接口返回的配置项", nodes: nodes.filter(node => node.ciCode !== rootCode && !incoming.has(node.ciCode) && !outgoing.has(node.ciCode)).sort(byName) },
  ].filter(group => group.nodes.length);
  const rows = Math.max(1, ...groups.map(group => Math.min(4, group.nodes.length)));
  const height = Math.max(350, 104 + rows * NODE_HEIGHT + (rows - 1) * 26);
  let x = 30;
  const positioned: PositionedNode[] = [];
  const columns = groups.map(group => {
    const columnCount = Math.ceil(group.nodes.length / 4);
    const width = columnCount * NODE_WIDTH + (columnCount - 1) * 24;
    const groupRows = Math.min(4, group.nodes.length);
    const blockHeight = groupRows * NODE_HEIGHT + (groupRows - 1) * 26;
    const y = 80 + (height - 104 - blockHeight) / 2;
    group.nodes.forEach((node, index) => positioned.push({ node, x: x + Math.floor(index / 4) * (NODE_WIDTH + 24), y: y + (index % 4) * (NODE_HEIGHT + 26), role: node.ciCode === rootCode ? "当前配置项" : incoming.has(node.ciCode) && outgoing.has(node.ciCode) ? "双向关联" : group.title }));
    const column = { ...group, x, width };
    x += width + 106;
    return column;
  });
  return { nodes: positioned, columns, width: Math.max(284, x - 106 + 30), height };
}

export function edgeGeometry(edge: CiEdge, index: number, nodes: PositionedNode[], parallelCount = 1) {
  const source = nodes.find(item => item.node.ciCode === edge.sourceCiCode);
  const target = nodes.find(item => item.node.ciCode === edge.targetCiCode);
  if (!source || !target) return undefined;
  const lane = (index - (parallelCount - 1) / 2) * Math.min(22, 80 / Math.max(1, parallelCount - 1));
  const sy = source.y + NODE_HEIGHT / 2 + lane;
  const ty = target.y + NODE_HEIGHT / 2 + lane;
  if (source === target) {
    const start = source.x + NODE_WIDTH * .35;
    const end = source.x + NODE_WIDTH * .75;
    return { path: `M ${start} ${source.y} C ${start} ${source.y - 40}, ${end} ${source.y - 40}, ${end} ${source.y}`, x: source.x + NODE_WIDTH * .55, y: source.y - 25 };
  }
  const forward = target.x > source.x;
  const sx = source.x + (forward ? NODE_WIDTH : 0);
  const tx = target.x + (forward ? 0 : NODE_WIDTH);
  const middle = (sx + tx) / 2;
  return { path: `M ${sx} ${sy} C ${middle} ${sy}, ${middle} ${ty}, ${tx} ${ty}`, x: middle, y: (sy + ty) / 2 };
}
