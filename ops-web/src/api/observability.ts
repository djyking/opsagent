import { request } from './http';

export type ServiceHealth = 'HEALTHY' | 'DEGRADED' | 'CRITICAL' | 'UNKNOWN' | 'MAINTENANCE' | 'DRILLING';
export interface ServiceMetrics { rps?: number | null; errorRate?: number | null; p95Ms?: number | null; healthyInstances?: number | null; totalInstances?: number | null; cpuUsage?: number | null; memoryUsage?: number | null }
export interface ServiceNode { id?: number; ciCode: string; ciName: string; ciType: string; environment: string; ownerName?: string; endpoint?: string; status?: string; description?: string; systemName?: string; tags?: string[]; bindings?: { prometheusJob?: string; sentinelApp?: string; nacosDataId?: string; alertLabel?: string }; health: ServiceHealth; drilling?: boolean; statusReason?: string; metrics?: ServiceMetrics; activeAlertCount?: number | null; sentinelBlockQps?: number | null; observedAt?: string }
export interface ServiceRelation { evidenceRefs?: string[]; quality?: string; metricsScope?: string; windowStart?: string; windowEnd?: string; sampledRequests?: number | null; id?: number | string; sourceCiCode: string; targetCiCode: string; relationType: string; relationSource?: string; description?: string; rps?: number | null; errorRate?: number | null; p95Ms?: number | null }
export interface DataSourceStatus { name: string; healthy: boolean; message?: string }
export type ObservationStatus = 'READY' | 'PARTIAL' | 'NOT_CONFIGURED' | 'UNSUPPORTED' | 'NO_DATA' | 'STALE' | 'FAILED';
export interface ObservationCheck { evidenceRef?: string; name?: string; step?: string; status?: string; reasonCode?: string; message?: string }
export interface ObservationInstance { instanceId?: string; instance?: string; source?: string; lastSeen?: string; status?: string; sampledAt?: string }
export interface ObservationEvidence { maximumSampleAgeSeconds?: number; status: ObservationStatus; reasonCode?: string; message?: string; sampledAt?: string; fetchedAt?: string; lastSuccessfulScrapeAt?: string; checks?: ObservationCheck[]; instances?: ObservationInstance[] }
export interface ObservationCoverage { eligible: number; ready: number; partial: number; failed: number; unknown: number; excluded: number; ratio: number | null; completeRatio: number | null }
export interface ServiceNode {
  identity?: { ciCode: string; environment: string; namespace?: string; cluster?: string; sourceInstanceId?: string };
  virtual?: boolean;
  lifecycle?: 'ACTIVE' | 'INACTIVE' | 'RETIRED'; requiresObservation?: boolean;
  healthReasonCode?: string; healthScope?: string; evidenceRefs?: string[]; observation?: ObservationEvidence;
  overlays?: { maintenance?: boolean; drilling?: boolean; alertSilenced?: boolean };
  metricEvidence?: Record<string, { value?: number | null; unit?: string; windowSeconds?: number; sampledAt?: string; reasonCode?: string; scope?: string }>;
}
export interface LayoutPosition { ciCode: string; x: number; y: number }
export interface TopologySnapshot { nodes: ServiceNode[]; edges: ServiceRelation[]; checkedAt: string; dataSources: DataSourceStatus[]; layout?: Record<string, { x: number; y: number }>; message?: string; relationMessage?: string; activeAlerts?: Record<string, unknown>[]; activeAlertCount?: number | null }
export interface TopologySnapshot { coverage?: ObservationCoverage }
export interface ObservabilityFilters { environment: string; timeRange: string; mode?: string }
export interface ServiceDetail { node: ServiceNode; alerts: Record<string, unknown>[]; alertsAvailable?: boolean; relations: ServiceRelation[]; recentChanges: Record<string, unknown>[]; recentRuns: Record<string, unknown>[]; metricsUrl?: string; dataSources?: DataSourceStatus[]; checkedAt?: string }
export interface ConfigSummary { status: string; configurationCount?: number | null; editableCount?: number | null; sources?: { source: string; count: number; status: string }[]; message?: string; observedAt?: string }
export interface TrafficSummary { status: string; serviceId?: string; resourceCount?: number | null; ruleCount?: number | null; passQps?: number | null; blockQps?: number | null; avgRt?: number | null; activeThreads?: number | null; message?: string; observedAt?: string }
export interface InspectionItem { executionStatus?: string; result?: string; runId?: string; scheduledFor?: string; startedAt?: string; finishedAt?: string; reasonCode?: string; executor?: string; nextRunAt?: string; id: string | number; name: string; ciCode: string; environment?: string; currentHealth?: ServiceHealth; status: string; lastCheckedAt?: string; consecutiveFailures?: number; summary?: string; automationId?: string; durationMs?: number; source?: string; evidence?: { metrics?: ServiceMetrics; observedAt?: string; activeAlertCount?: number | null; workflowRunId?: number; coverage?: string } }
export interface InspectionSnapshot { schedule?: { enabled: boolean; intervalMs: number; nextRunAt?: string; reasonCode?: string }; items: InspectionItem[]; checkedAt: string; message?: string; today?: Record<string, number>; coverage?: string }
const base = '/api/platform/observability';
export const observabilityApi = {
  topology: (params: ObservabilityFilters) => request<TopologySnapshot>({ url: `${base}/topology`, params }),
  service: (ciCode: string, params: ObservabilityFilters) => request<ServiceDetail>({ url: `${base}/services/${encodeURIComponent(ciCode)}`, params }),
  configSummary: (ciCode: string) => request<ConfigSummary>({ url: '/api/platform/config-center/summary', params: { ciCode } }),
  trafficSummary: (ciCode: string) => request<TrafficSummary>({ url: '/api/platform/traffic/summary', params: { ciCode } }),
  saveLayout: (environment: string, positions: LayoutPosition[]) => request<void>({ method: 'PUT', url: `${base}/topology/layout`, data: { environment, positions } }),
  inspections: (params: ObservabilityFilters) => request<InspectionSnapshot>({ url: `${base}/inspections`, params }),
  inspectionHistory: (ciCode: string) => request<{ items: InspectionItem[]; limit: number }>({ url: `${base}/inspections/${encodeURIComponent(ciCode)}/history` }),
  runInspection: (ciCode: string) => request<InspectionItem>({ method: 'POST', url: `${base}/inspections/${encodeURIComponent(ciCode)}/run` }),
  deleteCi: (id: number) => request<void>({ method: 'DELETE', url: `/api/platform/cmdb/cis/${id}` }),
  updateRelation: (id: number, data: Record<string, unknown>) => request<void>({ method: 'PUT', url: `/api/platform/cmdb/relations/${id}`, data }),
};
