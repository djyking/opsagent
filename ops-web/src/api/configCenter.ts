import { request } from './http';
export interface ConfigurationSource { source: string; count: number; status: string }
export interface ConfigCenterSummary { status: string; configurationCount: number | null; editableCount: number | null; sources: ConfigurationSource[]; message: string; observedAt: string }
export interface ConfigurationIdentity { sourceType: string; sourceInstanceId: string; environment: string; namespaceId: string; group: string; dataId: string; targetScope: string[] }
export interface ConfigurationCapabilities { canRead: boolean; canDiff: boolean; canEdit: boolean; canPublish: boolean; canRollback: boolean; canVerifyApplied: boolean; reasons: Record<string, string> }
export interface ConfigCenterItem { id: string; name: string; serviceId: string; source: string; namespace: string; group: string; dataId: string; format: string; editable: boolean; managementPath: string; status: string; description: string; modifiedAt: string; identity: ConfigurationIdentity; capabilities: ConfigurationCapabilities; shared: boolean }
export interface ConfigurationProposal { proposalId: string; immutableDigest: string; configurationId: string; targetCode: string; catalogId: string; identity: ConfigurationIdentity; expectedRevision: string; before: Record<string, unknown>; desired: Record<string, unknown>; changes: {field: string; before: unknown; after: unknown}[]; action: string; rollbackVersionId?: number; createdAt: string; expiresAt: string; status: string; targetSnapshot: Record<string, unknown>; comment: string; ownerId: number }
export interface ConfigurationProposalRequest { expectedRevision: string; requestId: string; comment: string; patch?: {op: 'replace'; path: string; value: unknown}[]; rollbackVersionId?: number }
export interface ConfigCenterCatalog { status: string; items: ConfigCenterItem[]; message: string; observedAt: string }
export interface ConfigCenterDetail { item: ConfigCenterItem; status: string; content: string; revision: string; message: string; observedAt: string }
export interface ConfigCenterHistory { status: string; items: { id: number; actor: string; modifiedAt: string; operation: string }[]; message: string }
export interface ConfigCenterDiff { status: string; versionId: number; currentContent: string; previousContent: string; message: string }
const base = '/api/platform/config-center';
export const configCenterApi = {
  list: (ciCode?: string) => request<ConfigCenterCatalog>({ url: base, params: { ciCode } }),
  summary: (ciCode?: string) => request<ConfigCenterSummary>({ url: `${base}/summary`, params: { ciCode } }),
  detail: (id: string) => request<ConfigCenterDetail>({ url: `${base}/${encodeURIComponent(id)}` }),
  history: (id: string) => request<ConfigCenterHistory>({ url: `${base}/${encodeURIComponent(id)}/history` }),
  diff: (id: string, versionId: number) => request<ConfigCenterDiff>({ url: `${base}/${encodeURIComponent(id)}/diff`, params: { versionId } }),
  propose: (id: string, data: ConfigurationProposalRequest) => request<ConfigurationProposal>({method: 'POST', url: `${base}/${encodeURIComponent(id)}/proposals`, data}),
  configurationRun: (data: {proposalId: string; immutableDigest: string; requestId: string}) => request<{id: string}>({method: 'POST', url: '/api/automation/configuration-runs', data}),
};
