import type { ServiceHealth, ServiceNode, ServiceRelation, ObservationStatus } from '@/api/observability';

export const healthLabels: Record<ServiceHealth, string> = { HEALTHY: '健康', DEGRADED: '降级', CRITICAL: '故障', UNKNOWN: '待观测', MAINTENANCE: '维护中', DRILLING: '演练中' };
export const healthColors: Record<ServiceHealth, string> = { HEALTHY: '#16a67a', DEGRADED: '#e99a20', CRITICAL: '#e05252', UNKNOWN: '#8795a8', MAINTENANCE: '#6980a1', DRILLING: '#8c65db' };
export const relationLabels: Record<string, string> = { CALLS: '调用', ROUTES_TO: '路由到', DEPENDS_ON: '依赖', READS_FROM: '读取自', WRITES_TO: '写入到', PUBLISHES_TO: '发布到', CONSUMES_FROM: '消费自', AUTHENTICATES_WITH: '认证于', MONITORED_BY: '监控于', READS: '读取', WRITES: '写入', REGISTERS_TO: '注册到', SENDS_TO: '发送至' };
export const observationLabels: Record<ObservationStatus, string> = { READY: '观测就绪', PARTIAL: '部分接入', NOT_CONFIGURED: '未配置采集', UNSUPPORTED: '暂不支持', NO_DATA: '暂无样本', STALE: '采样过期', FAILED: '采集失败' };
export const healthScopeLabels: Record<string, string> = { JVM_RUNTIME: 'JVM 运行状态', NATIVE_METRICS: '原生指标范围', REQUEST_WINDOW: '当前请求窗口', BUSINESS_PROBE: '业务探针验证' };
export function effectiveHealth(node: Pick<ServiceNode, 'health' | 'observedAt' | 'observation'>, now = Date.now()): ServiceHealth {
  // V3 freshness is calculated by the backend from actual source intervals. An independent probe can still establish health when metric collection fails.
  if (node.observation) return Object.hasOwn(healthLabels, node.health) && !['MAINTENANCE', 'DRILLING'].includes(node.health) ? node.health : 'UNKNOWN';
  if (node.health === 'MAINTENANCE') return 'MAINTENANCE';
  if (!node.observedAt || !Number.isFinite(Date.parse(node.observedAt)) || now - Date.parse(node.observedAt) > 90_000 || Date.parse(node.observedAt) - now > 15_000) return 'UNKNOWN';
  return Object.hasOwn(healthLabels, node.health) ? node.health : 'UNKNOWN';
}
export function metric(value: number | null | undefined, unit = '', digits = 1) { return typeof value === 'number' && Number.isFinite(value) ? `${value.toLocaleString('zh-CN', { maximumFractionDigits: digits })}${unit}` : '—'; }
export function observationTime(value?: string) { return value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '暂无有效采样'; }
export function filterTopology(nodes: ServiceNode[], edges: ServiceRelation[], keyword: string, onlyUnhealthy: boolean, now = Date.now()) {
  const term = keyword.trim().toLowerCase();
  const filtered = nodes.filter(node => (!term || `${node.ciCode} ${node.ciName} ${node.ciType} ${node.ownerName || ''}`.toLowerCase().includes(term)) && (!onlyUnhealthy || node.drilling || ['CRITICAL', 'DEGRADED', 'DRILLING'].includes(effectiveHealth(node, now))));
  const codes = new Set(filtered.map(node => node.ciCode));
  return { nodes: filtered, edges: edges.filter(edge => codes.has(edge.sourceCiCode) && codes.has(edge.targetCiCode)) };
}
export function serviceContext(ciCode: string, environment: string, timeRange: string) { return { ...(ciCode ? { ciCode } : {}), environment, timeRange }; }
export function safeMetricUrl(value?: string) { if (!value) return undefined; try { const url = new URL(value, 'https://opsagent.cloud'); return ['http:', 'https:'].includes(url.protocol) ? value : undefined; } catch { return undefined; } }
