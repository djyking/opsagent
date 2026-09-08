import { request } from './http';

export type EventAction = 'RESULT' | 'TECH_PASS' | 'TECH_FAIL' | 'BUSINESS_CONFIRM' | 'CLOSE' | 'REOPEN';
export interface EventDecision { id: number; recordType: string; content: string; evidence?: string; createBy: number; createTime: string }
export interface EventLifecycle {
  eventId: string; ticketId: number; version: number; stage: 'HANDLING' | 'VERIFYING' | 'READY_TO_CLOSE' | 'CLOSED' | 'LEGACY_ARCHIVED';
  businessRequired: boolean; businessRule: string; result?: EventDecision; technical?: EventDecision;
  business?: EventDecision; closed?: EventDecision; allowedActions: EventAction[]; blockers: string[]; history: EventDecision[];
}
export interface RecoveryBinding {
  ticketId: number; version: number; kind: 'REGULAR' | 'ISOLATED'; targetCode?: string;
  environment?: string; observedEnvironment?: string; incidentId?: string; canEdit: boolean;
  editHint: string; blockers: string[]; targets: string[]; environments: Record<string, string>; rule: string;
}
export interface RecoveryBindingInput { targetCode: string; environment: string; version: number; requestId: string; reason: string }
export const eventActionLabels: Record<EventAction, string> = {
  RESULT: '提交处理结果', TECH_PASS: '确认技术恢复', TECH_FAIL: '验证未通过，返回处置',
  BUSINESS_CONFIRM: '确认业务恢复', CLOSE: '关闭事件', REOPEN: '重新处置事件',
};
export const eventLifecycleApi = {
  read: (ticketId: number) => request<EventLifecycle>({ url: `/api/tickets/${ticketId}/event-lifecycle` }),
  act: (ticketId: number, data: { action: EventAction; version: number; requestId: string; content: string; evidence?: string }) =>
    request<EventLifecycle>({ method: 'POST', url: `/api/tickets/${ticketId}/event-lifecycle`, data }),
  binding: (ticketId: number) => request<RecoveryBinding>({ url: `/api/tickets/${ticketId}/event-lifecycle/recovery-binding` }),
  bind: (ticketId: number, data: RecoveryBindingInput) => request<RecoveryBinding>({ method: 'POST', url: `/api/tickets/${ticketId}/event-lifecycle/recovery-binding`, data }),
};
