import type { ServiceHealth, ServiceNode, ServiceRelation } from '@/api/observability';

export const healthLabels: Record<ServiceHealth, string> = { HEALTHY: '健康', DEGRADED: '降级', CRITICAL: '故障', UNKNOWN: '待观测', MAINTENANCE: '维护中', DRILLING: '演练中' };
export const healthColors: Record<ServiceHealth, string> = { HEALTHY: '#16a67a', DEGRADED: '#e99a20', CRITICAL: '#e05252', UNKNOWN: '#8795a8', MAINTENANCE: '#6980a1', DRILLING: '#8c65db' };
export const relationLabels: Record<string, string> = { CALLS: '调用', ROUTES_TO: '路由到', DEPENDS_ON: '依赖', READS_FROM: '读取自', WRITES_TO: '写入到', PUBLISHES_TO: '发布到', CONSUMES_FROM: '消费自', AUTHENTICATES_WITH: '认证于', MONITORED_BY: '监控于', READS: '读取', WRITES: '写入', REGISTERS_TO: '注册到', SENDS_TO: '发送至' };
export function effectiveHealth(node: Pick<ServiceNode, 'health' | 'observedAt' | 'observation' | 'healthScope'>, now = Date.now()): ServiceHealth {
  if (node.health === 'MAINTENANCE') return 'MAINTENANCE';
  const age = node.healthScope === 'BUSINESS_PROBE' ? 30 : node.observation?.maximumSampleAgeSeconds || 90;
  if (!node.observedAt || !Number.isFinite(Date.parse(node.observedAt)) || now - Date.parse(node.observedAt) >= age * 1000 || Date.parse(node.observedAt) - now > 15_000) return 'UNKNOWN';
  return Object.hasOwn(healthLabels, node.health) ? node.health : 'UNKNOWN';
}
export const observationLabels: Record<string, string> = { READY: '采集正常', PARTIAL: '部分采集', FAILED: '采集失败', STALE: '样本过期', NO_DATA: '等待样本', NOT_CONFIGURED: '未接入', UNSUPPORTED: '未支持', NOT_RUN: '尚未检查' };
export const healthScopeLabels: Record<string, string> = { REQUEST_WINDOW: '请求指标', JVM_RUNTIME: '进程 / JVM 指标', WINDOWS_HOST: 'Windows 主机资源', HOST_RESOURCE: '主机资源', HOST_RESOURCES: '主机资源', NATIVE_METRICS: '原生指标', BUSINESS_PROBE: '业务探针', ACTIVE_ALERT: '活动告警', BUSINESS: '业务证据', CLIENT_PEER_ONLY: '客户端依赖', OUTSIDE_SELECTED_ENVIRONMENT: '跨环境依赖' };
export function observationState(node: Pick<ServiceNode, 'observation'>, now = Date.now()) {
  const observation = node.observation;
  if (!observation) return 'NOT_CONFIGURED';
  if (['READY', 'PARTIAL'].includes(observation.status) && (!observation.sampledAt || !Number.isFinite(Date.parse(observation.sampledAt)) || Date.parse(observation.sampledAt) - now > 15_000 || now - Date.parse(observation.sampledAt) >= (observation.maximumSampleAgeSeconds || 90) * 1000)) return 'STALE';
  return observation.status;
}
export function observationReason(node: Pick<ServiceNode, 'observation' | 'statusReason'>, now = Date.now()) {
  if (observationState(node, now) === 'STALE') return '最近采集样本已过期，等待新的有效样本';
  return node.observation?.message || node.statusReason || '尚未取得采集证据，请核对绑定与采集目标';
}
export const metricLabels: Record<string, string> = { rabbitmqQueueReadSuccess: '队列只读检查', rabbitmqQueueMessages: '待消费消息', rabbitmqQueueConsumers: '队列消费者', rps: '请求速率', errorRate: '错误率', p95Ms: 'P95 延迟', cpuUsage: '进程 CPU 使用', memoryUsage: 'JVM 堆内存', hostCpuUsage: '主机 CPU 使用', hostMemoryUsage: '主机物理内存', hostDiskUsage: '主机磁盘使用', hostNetworkReceiveRate: '主机网络接收', hostNetworkTransmitRate: '主机网络发送', connections: '连接数', consumers: '消费者', messagesReady: '待消费消息', messagesUnacked: '未确认消息', memoryAlarm: '内存告警', diskAlarm: '磁盘告警', registeredServices: '注册服务', registeredInstances: '注册实例', configurationCount: '配置数量', collections: '集合数', vectors: '向量数', recoveryMode: '恢复模式', timeSeries: '时序数量', sourceAlerts: '告警数量', infrastructureReadSuccess: '组件只读检查', infrastructureAuthSuccess: '组件鉴权', mysqlQuerySuccess: '数据库只读查询', mysqlConnections: '当前连接数', mysqlMaxConnections: '连接数上限', redisPingSuccess: 'Redis 响应', redisClients: '客户端连接', redisUsedMemory: '内存用量', elasticClusterStatus: '集群健康', elasticNodes: '集群节点', elasticSearchSuccess: '搜索检查', prometheusReady: '采集器就绪', prometheusTimeSeries: '时序数量', prometheusFailedTargets: '抓取失败目标', alertmanagerReady: '告警组件就绪', alertmanagerConfigLoaded: '路由配置加载', alertmanagerAlerts: '活动告警', grafanaDatabaseReady: 'Grafana 存储就绪', sentinelConsoleReady: 'Sentinel 控制台', qdrantReady: '向量库就绪', qdrantCollections: '向量集合' };
export function evidenceMetric(node: ServiceNode, key: string, now = Date.now()) {
  const evidence = node.metricEvidence?.[key];
  if (!evidence || !evidence.sampledAt || !Number.isFinite(Date.parse(evidence.sampledAt)) || Date.parse(evidence.sampledAt) - now > 15_000 || now - Date.parse(evidence.sampledAt) >= (node.observation?.maximumSampleAgeSeconds || 90) * 1000) return '—';
  if (key === 'elasticClusterStatus') return ({ 0: '正常', 1: '副本未齐（yellow）', 2: '分片不可用（red）' } as Record<number, string>)[evidence.value ?? -1] || '—';
  if (evidence.unit === 'boolean' && evidence.value != null) return evidence.value === 1 ? '是' : '否';
  if (evidence.unit === 'bytes' && evidence.value != null) return `${(evidence.value / 1024 / 1024).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} MB`;
  return metric(evidence.value, evidence.unit === 'boolean' ? '' : ({ 'requests/s': ' /s', '%': '%', percent: '%', 'bytes/s': ' B/s', 'bits/s': ' bit/s', ms: ' ms' } as Record<string, string>)[evidence.unit] || '', 2);
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
