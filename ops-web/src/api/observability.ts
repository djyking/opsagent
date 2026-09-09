import { request } from './http';

export type ServiceHealth = 'HEALTHY' | 'DEGRADED' | 'CRITICAL' | 'UNKNOWN' | 'MAINTENANCE' | 'DRILLING';
export interface ObservationCheck { step: string; status: string; reasonCode: string; message: string }
export interface NodeObservation { status: string; reasonCode: string; message: string; sampledAt?: string; fetchedAt?: string; lastSuccessfulScrapeAt?: string; maximumSampleAgeSeconds?: number; checks?: ObservationCheck[]; instances?: { instance: string; health: string; lastError?: string; lastScrape?: string; sampledAt?: string; lastSuccessfulScrapeAt?: string; up?: number | null }[] }
export interface MetricEvidence { value: number | null; unit: string; sampledAt?: string; windowSeconds?: number; reasonCode: string; scope: string; aggregation?: string }
export interface LayoutSnapshot { positions: Record<string, { x: number; y: number }>; source: 'PERSONAL' | 'TEAM' | 'AUTO'; environment: string }
export interface ServiceMetrics { rps?: number | null; errorRate?: number | null; p95Ms?: number | null; healthyInstances?: number | null; totalInstances?: number | null; cpuUsage?: number | null; memoryUsage?: number | null }
export interface ServiceNode { hostCiCode?: string; id?: number; ciCode: string; ciName: string; ciType: string; environment: string; ownerName?: string; endpoint?: string; status?: string; description?: string; systemName?: string; tags?: string[]; bindings?: { prometheusJob?: string; sentinelApp?: string; nacosDataId?: string; alertLabel?: string; hostCiCode?: string; hostId?: string; hostDisks?: string; hostInterfaces?: string }; health: ServiceHealth; drilling?: boolean; statusReason?: string; metrics?: ServiceMetrics; activeAlertCount?: number | null; sentinelBlockQps?: number | null; observedAt?: string; observation?: NodeObservation; metricEvidence?: Record<string, MetricEvidence>; lifecycle?: string; overlays?: { maintenance?: boolean; drilling?: boolean }; healthScope?: string; healthReasonCode?: string; virtual?: boolean; requiresObservation?: boolean; prometheusJob?: string; runtimeScopeMessage?: string }
export interface ServiceRelation { id?: number; sourceCiCode: string; targetCiCode: string; relationType: string; relationSource?: string; description?: string; rps?: number | null; errorRate?: number | null; p95Ms?: number | null }
export interface DataSourceStatus { name: string; healthy: boolean; message?: string }
export interface MetricHistoryPoint { at: string; value: number; unit: string; scope: string }
export interface ServiceMetricHistory { ciCode: string; environment: string; from: string; to: string; checkedAt: string; source: string; series: Record<string, MetricHistoryPoint[]>; message: string }
export interface LayoutPosition { ciCode: string; x: number; y: number }
export interface TopologySnapshot { nodes: ServiceNode[]; edges: ServiceRelation[]; checkedAt: string; dataSources: DataSourceStatus[]; layout?: Record<string, { x: number; y: number }>; message?: string; relationMessage?: string; activeAlerts?: Record<string, unknown>[]; activeAlertCount?: number | null }
export interface ObservabilityFilters { environment: string; timeRange: string; mode?: string }
export interface ObservationRequestOptions { signal?: AbortSignal; expectedIdentity?: string }
export interface ServiceDetail { node: ServiceNode; alerts: Record<string, unknown>[]; alertsAvailable?: boolean; relations: ServiceRelation[]; recentChanges: Record<string, unknown>[]; recentRuns: Record<string, unknown>[]; metricsUrl?: string; dataSources?: DataSourceStatus[]; checkedAt?: string }
export interface ConfigSummary { status: string; configurationCount?: number | null; editableCount?: number | null; sources?: { source: string; count: number; status: string }[]; message?: string; observedAt?: string }
export interface TrafficSummary { status: string; serviceId?: string; resourceCount?: number | null; ruleCount?: number | null; passQps?: number | null; blockQps?: number | null; avgRt?: number | null; activeThreads?: number | null; message?: string; observedAt?: string }
export interface InspectionItem { id: string | number; name: string; ciCode: string; environment?: string; currentHealth?: ServiceHealth; currentNode?: ServiceNode; observation?: NodeObservation; status: string; executionStatus?: string; result?: string; reasonCode?: string; lastCheckedAt?: string; consecutiveFailures?: number; summary?: string; automationId?: string; durationMs?: number; source?: string; evidence?: { metrics?: ServiceMetrics; observedAt?: string; activeAlertCount?: number | null; workflowRunId?: number; coverage?: string; observation?: NodeObservation; healthScope?: string } }
export interface InspectionSnapshot { items: InspectionItem[]; checkedAt: string; message?: string; today?: Record<string, number>; coverage?: string; schedule?: { enabled?: boolean; nextRunAt?: string; intervalMs?: number } }
const base = '/api/platform/observability';
export const observabilityApi = {
  topology: (params: ObservabilityFilters, options: ObservationRequestOptions = {}) => request<TopologySnapshot>({ url: `${base}/v3/topology`, params, timeout: 20_000, ...options }),
  service: (ciCode: string, params: ObservabilityFilters, options: ObservationRequestOptions = {}) => request<ServiceDetail>({ url: `${base}/services/${encodeURIComponent(ciCode)}`, params, timeout: 12_000, ...options }),
  metricHistory: (ciCode: string, params: ObservabilityFilters, options: ObservationRequestOptions = {}) => request<ServiceMetricHistory>({ url: `${base}/services/${encodeURIComponent(ciCode)}/metric-history`, params, timeout: 12_000, ...options }),
  configSummary: (ciCode: string, options: ObservationRequestOptions = {}) => request<ConfigSummary>({ url: '/api/platform/config-center/summary', params: { ciCode }, timeout: 12_000, ...options }),
  trafficSummary: (ciCode: string, options: ObservationRequestOptions = {}) => request<TrafficSummary>({ url: '/api/platform/traffic/summary', params: { ciCode }, timeout: 12_000, ...options }),
  saveLayout: (environment: string, positions: LayoutPosition[]) => request<void>({ method: 'PUT', url: `${base}/topology/layout`, data: { environment, positions } }),
  layout: (environment: string, options: ObservationRequestOptions = {}) => request<LayoutSnapshot>({ url: `${base}/topology/layout`, params: { environment }, timeout: 12_000, ...options }),
  savePersonalLayout: (environment: string, positions: LayoutPosition[]) => request<void>({ method: 'PUT', url: `${base}/topology/layout/personal`, data: { environment, positions } }),
  resetPersonalLayout: (environment: string) => request<void>({ method: 'DELETE', url: `${base}/topology/layout/personal`, params: { environment } }),
  inspections: (params: ObservabilityFilters) => request<InspectionSnapshot>({ url: `${base}/inspections`, params }),
  inspectionHistory: (ciCode: string) => request<{ items: InspectionItem[]; limit: number }>({ url: `${base}/inspections/${encodeURIComponent(ciCode)}/history` }),
  runInspection: (ciCode: string) => request<InspectionItem>({ method: 'POST', url: `${base}/inspections/${encodeURIComponent(ciCode)}/run` }),
  deleteCi: (id: number) => request<void>({ method: 'DELETE', url: `/api/platform/cmdb/cis/${id}` }),
  updateRelation: (id: number, data: Record<string, unknown>) => request<void>({ method: 'PUT', url: `/api/platform/cmdb/relations/${id}`, data }),
};
