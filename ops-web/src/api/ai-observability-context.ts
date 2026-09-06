import { request } from './http';
import type { ServiceDetail } from './observability';
import { cleanAiContext, type AiContext } from '@/utils/ai-context';

export interface AiObservabilityReference {
  service: string; environment: 'PROD' | 'DEMO'; timeRange: '5m' | '15m' | '30m' | '1h' | '6h'; evidenceBundleId?: string;
}
export async function resolveAiObservabilityContext(scope: AiContext, signal?: AbortSignal): Promise<AiObservabilityReference | undefined> {
  const context = cleanAiContext(scope);
  if (!context.service || signal?.aborted) return;
  let environment = context.environment?.toUpperCase();
  if (environment !== 'PROD' && environment !== 'DEMO') {
    // Resolve only the service identity. Observed facts are collected and authorized again by the RAG backend.
    const detail = await request<ServiceDetail>({ url: `/api/platform/observability/services/${encodeURIComponent(context.service)}`,
      params: { environment: context.environment || 'ALL', timeRange: context.timeRange || '15m' }, timeout: 8000, signal });
    if (signal?.aborted) return;
    if (detail.node?.ciCode !== context.service) throw new Error('无法确认当前服务身份，请重新选择服务或移除上下文');
    environment = detail.node.identity?.environment || detail.node.environment;
  }
  if (environment !== 'PROD' && environment !== 'DEMO') throw new Error('当前服务尚未提供受支持的生产或演示环境身份，请选择明确环境或移除上下文');
  const timeRange = context.timeRange || '15m';
  if (!['5m', '15m', '30m', '1h', '6h'].includes(timeRange)) throw new Error('当前分析时间范围无效，请重新选择');
  return { service: context.service, environment, timeRange: timeRange as AiObservabilityReference['timeRange'], ...(context.evidenceBundleId ? { evidenceBundleId: context.evidenceBundleId } : {}) };
}
