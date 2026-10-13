import { request } from './http';

export type ManagedConfigurationId = 'order-business' | 'order-runtime';
export interface BusinessConfiguration { catalogTitle: string; notice: string; discountPercent: number }
export interface ManagedConfigurationItem {
  id: ManagedConfigurationId; name: string; group: string; dataId: string; editable: boolean; description: string;
}
export interface ManagedConfiguration extends ManagedConfigurationItem {
  content: Record<string, unknown>; revision: string; appliedRevision: string;
  applicationStatus: string; nacosStatus: string; canPublish: boolean; blockedReason: string; observedAt: string;
  business: { httpStatus?: number; reasonCode?: string; catalogTitle?: string; notice?: string;
    discountPercent?: number; basePrice?: number; quotedPrice?: number; catalog?: string;
    businessConfigurationRevision?: string; observedAt?: string };
  application?: { instanceId?: string; targetCode?: string; observedAt?: string; appliedAt?: string;
    verificationScope?: string; multiInstanceCoverage?: boolean;
    applicationPause?: { active?: boolean; expiresAt?: string; recoverySource?: string } };
}
export interface ConfigurationVersion {
  id: number; version: number; action: string; status: string; content: Record<string, unknown>;
  previousContent: Record<string, unknown>; revision: string; expectedRevision: string; comment: string;
  actorName: string; createdAt: string; finishedAt: string | null; rollbackVersionId: number | null; message?: string;
}
export interface ConfigurationHistory { items: ConfigurationVersion[]; total: number; page: number; size: number }
export interface ConfigurationOperation { operation: ConfigurationVersion; configuration: ManagedConfiguration }
const base = '/api/platform/configuration/managed';
export const configurationApi = {
  list: () => request<{ items: ManagedConfigurationItem[] }>({ url: base }),
  detail: (id: ManagedConfigurationId) => request<ManagedConfiguration>({ url: `${base}/${id}` }),
  history: (id: ManagedConfigurationId, page = 1) => request<ConfigurationHistory>({ url: `${base}/${id}/history`, params: { page, size: 8 } }),
  validate: (id: ManagedConfigurationId, content: BusinessConfiguration) => request<{ valid: boolean; normalizedContent: BusinessConfiguration; description: string }>({ method: 'POST', url: `${base}/${id}/validate`, data: { content } }),
  publish: (id: ManagedConfigurationId, data: { content: BusinessConfiguration; expectedRevision: string; requestId: string; comment: string }) => request<ConfigurationOperation>({ method: 'POST', url: `${base}/${id}/publish`, data }),
  rollback: (id: ManagedConfigurationId, data: { versionId: number; expectedRevision: string; requestId: string; comment: string }) => request<ConfigurationOperation>({ method: 'POST', url: `${base}/${id}/rollback`, data }),
};
