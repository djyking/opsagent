import { request } from './http';
import type { ObservabilityFilters, TopologySnapshot } from './observability';

export interface TraceStatus { status: string; message?: string; samplingPolicy?: string }
export interface TopologyV3Snapshot extends TopologySnapshot { windowStart?: string; windowEnd?: string; graphVersion?: string; trace?: TraceStatus; history?: boolean }
export interface RuntimeInstance { instanceId: string; ciCode: string; environment: string; runtimeKind: string; firstSeenAt?: string; lastSeenAt?: string; observationStatus?: string; source: string; metadata?: Record<string, unknown> }
export interface InstanceSnapshot { ciCode: string; environment: string; runtimeServiceCiCode?: string; items: RuntimeInstance[]; status: string; message?: string; podSupported: boolean }
export interface TraceRow { traceId: string; startTime: string; durationMs: number; rootService: string; serviceCount: number | null; source: string }
export interface TraceList { status: string; message?: string; items: TraceRow[] }
export interface TraceSpan { spanId: string; parentSpanId?: string; ciCode: string; environment: string; instanceId?: string; kind: string; startTime: string; durationMs: number; status: string; operation: string; peerService?: string; links?: unknown[] }
export interface TraceDetail { traceId: string; environment: string; spans: TraceSpan[]; partial: boolean; source: string }
export interface TopologyHistoryRow { id: string | number; environment: string; windowStart: string; windowEnd: string; generatedAt: string; graphVersion: string; dataQuality: string }
export interface TopologyHistory { items: TopologyHistoryRow[]; oldestAt?: string; retentionHours: number }
export interface DependencyDifference { id: string; category: string; sourceCiCode: string; targetCiCode: string; relationType: string; relationSource?: string; evidenceRefs?: string[]; reason: string; handling?: { decision?: string; note?: string; expiresAt?: string } }
export interface DependencyDifferences { items: DependencyDifference[]; traceStatus: string | TraceStatus }
const base = '/api/platform/observability/v3';
export const observabilityV3Api = {
  topology: (params: ObservabilityFilters) => request<TopologyV3Snapshot>({ url: `${base}/topology`, params }),
  instances: (ciCode: string, params: ObservabilityFilters) => request<InstanceSnapshot>({ url: `${base}/services/${encodeURIComponent(ciCode)}/instances`, params }),
  traces: (params: ObservabilityFilters & { ciCode: string; targetCiCode?: string }) => request<TraceList>({ url: `${base}/traces`, params }),
  trace: (id: string, ciCode: string, environment: string) => request<TraceDetail>({ url: `${base}/traces/${encodeURIComponent(id)}`, params: { ciCode, environment } }),
  history: (params: { environment: string; from: string; to: string }) => request<TopologyHistory>({ url: `${base}/history`, params }),
  historySnapshot: (id: string | number) => request<TopologyV3Snapshot>({ url: `${base}/history/${encodeURIComponent(id)}` }),
  differences: (params: ObservabilityFilters) => request<DependencyDifferences>({ url: `${base}/differences`, params }),
  decideDifference: (id: string, data: { decision: 'ACKNOWLEDGED' | 'IGNORE'; note: string; ignoreUntil?: string }) => request<void>({ method: 'PUT', url: `${base}/differences/${encodeURIComponent(id)}/decision`, data }),
};
