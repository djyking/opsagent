import { request } from './http';
export type TrafficRuleType = 'FLOW' | 'DEGRADE' | 'SYSTEM';
export type TrafficRule = Record<string, string | number | boolean>;
export interface TrafficSummary { status: string; serviceId: string; resourceCount: number | null; ruleCount: number | null; passQps: number | null; blockQps: number | null; avgRt: number | null; activeThreads: number | null; message: string; observedAt: string }
export interface TrafficResource { resource: string; label: string; status: string; passQps: number | null; blockQps: number | null; avgRt: number | null; activeThreads: number | null; measurement: string }
export interface TrafficRuleSet { type: string; label: string; status: string; supported: boolean; editable: boolean; dataId: string; revision: string; persistedRules: TrafficRule[]; appliedRules: TrafficRule[]; applicationStatus: string; message: string }
export interface TrafficWorkspace { summary: TrafficSummary; resources: TrafficResource[]; ruleSets: TrafficRuleSet[] }
export interface TrafficStream { id: string; label: string; serviceId: string; status: string; requestsPerSecond: number | null; requestCount: number | null; blockedCount: number | null; apiRequestsPerSecond: number | null; sampledAt: string | null; scope: string; message: string }
export interface TrafficOverview { source: string; windowSeconds: number; refreshedAt: string; streams: TrafficStream[] }
export interface TrafficChange { id: number; type: string; action: string; status: string; before: TrafficRule[]; after: TrafficRule[]; expectedRevision: string; revision: string; comment: string; actor: string; createdAt: string; finishedAt: string | null; rollbackVersionId: number | null; message: string }
export interface TrafficPublish { rules: TrafficRule[]; expectedRevision: string; requestId: string; comment: string }
const base = '/api/platform/traffic';
export const trafficGovernanceApi = {
  overview: () => request<TrafficOverview>({ url: `${base}/overview` }),
  workspace: (ciCode?: string) => request<TrafficWorkspace>({ url: base, params: { ciCode } }),
  summary: (ciCode?: string) => request<TrafficSummary>({ url: `${base}/summary`, params: { ciCode } }),
  ruleSet: (type: string) => request<TrafficRuleSet>({ url: `${base}/rules/${type}` }),
  history: (type: string) => request<{ items: TrafficChange[] }>({ url: `${base}/history`, params: { type } }),
  validate: (type: string, rules: TrafficRule[]) => request<{ valid: boolean; rules: TrafficRule[]; message: string }>({ method: 'POST', url: `${base}/rules/${type}/validate`, data: { rules } }),
  publish: (type: string, data: TrafficPublish) => request<{ operation: TrafficChange; ruleSet: TrafficRuleSet }>({ method: 'POST', url: `${base}/rules/${type}/publish`, data }),
  rollback: (type: string, data: Omit<TrafficPublish, 'rules'> & { versionId: number }) => request<{ operation: TrafficChange; ruleSet: TrafficRuleSet }>({ method: 'POST', url: `${base}/rules/${type}/rollback`, data }),
};
