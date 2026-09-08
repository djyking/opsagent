import { request } from './http';
export type FileAction = 'PUBLISH_ONLY' | 'PUBLISH_RESTART' | 'APPLY_ONLY';
export interface ConfigurationField { key: string; label: string; category: string; type: 'string' | 'integer' | 'number' | 'boolean' | 'json'; unit?: string; min?: number; max?: number; value?: unknown; sensitive: boolean; hasValue: boolean; editable: boolean; options?: unknown[] }
export interface ConfigurationFile { id: string; serviceId: string; label: string; format: string; sensitive: boolean; editable: boolean; status: string; reason?: string; affectedServices: string[]; applyAction: string }
export interface ConfigurationFileDetail extends ConfigurationFile { version: string; fields: ConfigurationField[]; redactedContent: string; rawEditable: boolean; loadedVersion?: string; publishedVersion?: string; lastTaskId?: string }
export interface ConfigurationDraft { id: string; fileId: string; baseVersion: string; targetVersion: string; digest: string; action: FileAction; rollbackOnFailure: boolean; affectedServices: string[]; diff: { key: string; label: string; before: unknown; after: unknown; sensitive: boolean }[]; status: string; createdBy: number; createdAt: string; approvedBy?: number; approvedAt?: string; taskId?: string; impact: string }
export interface ConfigurationTask { id: string; draftId: string; fileId: string; action: FileAction; status: string; baseVersion: string; targetVersion: string; createdAt: string; updatedAt: string; message: string; filePublished: boolean; rollbackStatus: string; awaitingVerification?: boolean; targets: { serviceId: string; status: string; beforePid?: number; afterPid?: number; loaded: boolean; healthy: boolean; businessVerified: boolean; evidence: unknown[] }[]; events: { at: string; stage: string; message: string }[] }
export interface ConfigurationHistory { drafts: ConfigurationDraft[]; tasks: ConfigurationTask[] }
const base = '/api/platform/configuration/files';
export const configurationFilesApi = {
  list: () => request<{ items: ConfigurationFile[]; executorStatus: string }>({ url: base }),
  detail: (id: string) => request<ConfigurationFileDetail>({ url: `${base}/${encodeURIComponent(id)}` }),
  history: (id: string) => request<ConfigurationHistory>({ url: `${base}/${encodeURIComponent(id)}/history` }),
  draft: (id: string) => request<ConfigurationDraft>({ url: `${base}/drafts/${encodeURIComponent(id)}` }),
  create: (id: string, data: { baseVersion: string; changes?: { key: string; value: unknown }[]; content?: string; action: FileAction; rollbackOnFailure: boolean; requestId: string }) => request<ConfigurationDraft>({ method: 'POST', url: `${base}/${encodeURIComponent(id)}/drafts`, data }),
  submit: (id: string, digest: string, requestId: string) => request<ConfigurationDraft>({ method: 'POST', url: `${base}/drafts/${encodeURIComponent(id)}/submit`, data: { digest, requestId } }),
  approve: (id: string, digest: string, decision: 'APPROVE' | 'REJECT', comment: string, requestId: string) => request<ConfigurationDraft>({ method: 'POST', url: `${base}/drafts/${encodeURIComponent(id)}/approve`, data: { digest, decision, comment, requestId } }),
  execute: (id: string, digest: string, requestId: string) => request<ConfigurationTask>({ method: 'POST', url: `${base}/drafts/${encodeURIComponent(id)}/execute`, data: { digest, requestId } }),
  task: (id: string) => request<ConfigurationTask>({ url: `${base}/tasks/${encodeURIComponent(id)}` }),
  verify: (id: string, requestId: string) => request<ConfigurationTask>({ method: 'POST', url: `${base}/tasks/${encodeURIComponent(id)}/verify`, data: { requestId } }),
};
