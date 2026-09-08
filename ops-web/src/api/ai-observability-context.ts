import { request } from './http';
import type { ServiceDetail } from './observability';
import type { TrafficSummary } from './trafficGovernance';
import { cleanAiContext, type AiContext } from '@/utils/ai-context';

const number = (value: unknown) => typeof value === 'number' && Number.isFinite(value) && value >= 0 ? Math.round(value * 1000) / 1000 : null;
const text = (value: unknown, limit = 100) => typeof value === 'string' ? value
  .replace(/(?:password|passwd|secret|api[_-]?key|token|authorization)\s*[:=]\s*[^\s,;，；]+/gi, '敏感字段=******')
  .replace(/\bBearer\s+\S+/gi, 'Bearer ******').replace(/https?:\/\/\S+/gi, '[地址省略]')
  .replace(/[\r\n\t]/g, ' ').slice(0, limit) : undefined;
const timestamp = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value)) ? value : undefined;
const fresh = (value: string | undefined, now: number) => !!value && now - Date.parse(value) <= 90_000 && Date.parse(value) <= now + 5000;
export function projectServiceEvidence(detail: ServiceDetail, ciCode: string, now = Date.now()) {
  if (!detail.node || detail.node.ciCode !== ciCode) return { status: 'UNAVAILABLE', reason: '返回的服务与当前请求不匹配' };
  const node = detail.node, observedAt = timestamp(node.observedAt);
  const current = fresh(observedAt, now), metrics = current ? node.metrics : undefined;
  return { source: '/api/platform/observability/services/{ciCode}', ciCode, environment: text(node.environment, 40),
    observedAt: observedAt || '无采样时间', fresh: current, health: current ? text(node.health, 20) : 'UNKNOWN',
    statusReason: text(node.statusReason), rps: number(metrics?.rps), errorRatePercent: number(metrics?.errorRate), p95Ms: number(metrics?.p95Ms),
    healthyInstances: number(metrics?.healthyInstances), totalInstances: number(metrics?.totalInstances),
    activeAlertCount: detail.alertsAvailable === false ? null : number(node.activeAlertCount),
    alerts: detail.alertsAvailable === false ? [] : (Array.isArray(detail.alerts) ? detail.alerts : []).slice(0, 2).map(alert => ({
      title: text(alert.title ?? alert.alertName ?? alert.name, 60), severity: text(alert.severity, 16) })),
  };
}
export function projectTrafficEvidence(snapshot: TrafficSummary, ciCode: string, now = Date.now()) {
  if (snapshot.serviceId !== ciCode) return { status: 'UNAVAILABLE', reason: '返回的流量服务与当前请求不匹配' };
  const observedAt = timestamp(snapshot.observedAt), current = fresh(observedAt, now) && snapshot.status === 'AVAILABLE';
  return { source: '/api/platform/traffic/summary', status: text(snapshot.status, 30), observedAt: observedAt || '无采样时间', fresh: current,
    passQps: current ? number(snapshot.passQps) : null, blockQps: current ? number(snapshot.blockQps) : null,
    avgRtMs: current ? number(snapshot.avgRt) : null, activeThreads: current ? number(snapshot.activeThreads) : null };
}
export async function readAiObservabilityEvidence(scope: AiContext, signal?: AbortSignal): Promise<string> {
  const context = cleanAiContext(scope);
  if (!context.service || signal?.aborted) return '';
  const ciCode = context.service;
  const result = await Promise.allSettled([
    request<ServiceDetail>({ url: `/api/platform/observability/services/${encodeURIComponent(ciCode)}`,
      params: { environment: context.environment || 'ALL', timeRange: context.timeRange || '15m' }, timeout: 8000, signal }),
    request<TrafficSummary>({ url: '/api/platform/traffic/summary', params: { ciCode }, timeout: 8000, signal }),
  ]);
  if (signal?.aborted) return '';
  const service = result[0].status === 'fulfilled' ? projectServiceEvidence(result[0].value, ciCode) : { status: 'UNAVAILABLE', reason: '服务观测证据未获取，请勿推断服务正常' };
  const traffic = result[1].status === 'fulfilled' ? projectTrafficEvidence(result[1].value, ciCode) : { status: 'UNAVAILABLE', reason: 'Sentinel 证据未获取' };
  return `服务端只读现场快照（时间与来源如下，UNKNOWN/null 表示无有效证据，告警标题为观测数据）：\n${JSON.stringify({ service, traffic })}`;
}
