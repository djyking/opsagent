import { request } from './http';
export interface WorkflowNode { id: string; type: string; label?: string; config?: Record<string, unknown> }
export interface Graph { nodes: WorkflowNode[]; edges: { from: string; to: string; when?: string }[] }
export interface RunRow { id: string; definition_id: string; version: number; owner_id: number; status: string; node_id: string; created_at: string;
  ticketId?: number; incidentId?: string; ticket_id?: number; incident_id?: string; pause_requested?: boolean }
export interface RunFilters { ticketId?: number; incidentId?: string }
export interface Approval { id: string; run_id: string; status: string; args_hash: string; revision: number; reason?: string; expires_at: string; payload: Record<string, unknown> }
export interface PendingApproval extends Approval { runStatus: string; ownerId: number; ticketId: number; incidentId: string;
  nodeId: string; nodeLabel: string; runDeadline: string; pauseRequested: boolean }
export interface ModelFailure { code: string; reason: string; canResume: boolean; retryable: boolean;
  recoveryAction: 'NEW_RUN' | 'CHECK_CONFIGURATION' }
export type RunTokenBudgetMode = 'UNLIMITED' | 'LIMITED';
export interface AutomationLimits extends Record<string, unknown> {
  tokenBudgetMode?: RunTokenBudgetMode; maxTotalTokens?: number; maxModelTurns?: number; maxToolCalls?: number;
}
export interface RunUsage {
  budget: { mode: RunTokenBudgetMode; limitTokens: number | null; chargedTokens: number; remainingTokens?: number | null };
  model: { availability: 'AVAILABLE' | 'UNAVAILABLE'; coverage?: 'COMPLETE' | 'PARTIAL' | 'UNAVAILABLE';
    knownInputTokens?: number; knownOutputTokens?: number; knownTotalTokens?: number; knownUsageAttempts?: number;
    unknownUsageAttempts?: number; unknownCountIsLowerBound?: boolean; pendingCalls?: number;
    providers?: { provider: string; knownInputTokens: number; knownOutputTokens: number; knownTotalTokens: number;
      knownUsageAttempts: number; unknownUsageAttempts: number }[] };
  embedding: { reservedTokens: number; reservationCount: number; actualTokens?: number | null; actualUsageKnown: boolean };
}
export interface RunDetail { id: string; ownerId: number; status: string; nodeId: string; createdAt: string; pauseRequested?: boolean;
  snapshot: { graph: Graph; model: Model; hash: string }; approvals: Approval[];
  state: { ticketId: number; incidentId: string; turns: number; toolCount: number; tokens: number; tokenBudget?: number; tokenBudgetMode?: RunTokenBudgetMode; summary?: string;
    deadline: string; message?: string; modelFailure?: ModelFailure; ticketResolved: boolean;
    recoveryVerification?: { resolved?: boolean; toStatus?: string; reason?: string };
    outputs?: Record<string, unknown>; observations?: Record<string, unknown> } }
export interface Model { provider: string; model: string; configured: boolean; toolCalling: boolean; verificationStatus: string }
export interface RunEvent { id: number; type: string; nodeId: string; createdAt: string; payload: unknown }
export interface Definition { id: string; name: string; draft_revision: number; published_version: number; graph?: Graph }
export interface Target { targetCode: string; status: string; incidentId?: string; scenarioCode?: string; expectedRevision?: string; appliedRevision?: string;
  configured: boolean; canStart: boolean; ownedByCurrentActor?: boolean; nextAvailableAt?: string; configurationStatus?: string; observedAt?: string;
  expiresAt?: string; recoverySource?: string; business: { httpStatus: number; reasonCode: string; observedAt?: string; consecutiveSuccesses?: number; queueDrained?: boolean; deliveredAt?: string };
  queue?: { queue: string; messagesReady: number; consumerCount: number; publishedTotal: number; deliveredTotal: number; lastDeliveredAt?: string; observedAt?: string };
  sentinel?: { resource: string; qps: number; passedTotal: number; blockedTotal: number } }
export interface Incident { incidentId: string; scenarioCode: string; ownerId: number; status: string; startedAt: string; expiresAt: string;
  recoveredAt?: string; recoverySource?: string; lastHttpStatus?: number; lastReason?: string }
export interface RestoreResult extends Target { actionAccepted: boolean; recoveryVerified: boolean; agentRecovered: boolean }
const base = '/api/automation';
export const automationApi = {
  models: () => request<{ models: Model[] }>({ url: `${base}/models` }),
  probe: (provider: string) => request<Model>({ method: 'POST', url: `${base}/models/${encodeURIComponent(provider.toUpperCase())}/probe` }),
  tools: () => request<{ tools: { function: { name: string; description: string; parameters: unknown } }[]; limits: AutomationLimits; approvalRequired: string[] }>({ url: `${base}/tools` }),
  definitions: () => request<Definition[]>({ url: `${base}/definitions` }),
  definition: (id: string) => request<Definition>({ url: `${base}/definitions/${id}` }),
  save: (id: string, name: string, revision: number, graph: Graph) => request({ method: 'PUT', url: `${base}/definitions/${id}`, data: { name, revision, graph } }),
  validate: (graph: Graph) => request({ method: 'POST', url: `${base}/definitions/validate`, data: graph }),
  publish: (id: string, revision: number) => request({ method: 'POST', url: `${base}/definitions/${id}/publish`, data: { revision } }),
  runs: (page = 1, filters: RunFilters = {}) => request<{ items: RunRow[]; total: number }>({ url: `${base}/runs`, params: { page, size: 10, ...filters } }),
  run: (id: string) => request<RunDetail>({ url: `${base}/runs/${id}` }),
  usage: (id: string) => request<RunUsage>({ url: `${base}/runs/${encodeURIComponent(id)}/usage` }),
  events: (id: string, after = 0) => request<RunEvent[]>({ url: `${base}/runs/${id}/events`, params: { after } }),
  create: (ticketId: number, definitionId: string, provider: string, requestId: string) => request<{ id: string }>({ method: 'POST', url: `${base}/runs`, data: { ticketId, definitionId, provider, requestId } }),
  cancel: (id: string) => request({ method: 'POST', url: `${base}/runs/${id}/cancel` }),
  pause: (id: string) => request({ method: 'POST', url: `${base}/runs/${id}/pause` }),
  resume: (id: string) => request({ method: 'POST', url: `${base}/runs/${id}/resume` }),
  pendingApprovals: () => request<{ items: PendingApproval[]; total: number }>({ url: `${base}/approvals/pending`, params: { limit: 50 } }),
  decide: (approval: Approval, approved: boolean, reason: string) => request({ method: 'POST', url: `${base}/approvals/${approval.id}/decision`, data: { revision: approval.revision, argsHash: approval.args_hash, approved, reason } }),
  target: (targetCode = 'ops-demo-order-service') => request<Target>({ url: '/api/platform/operations/demo/target', params: { targetCode } }),
  scenarios: (targetCode = 'ops-demo-order-service') => request<{ incidents: Incident[] }>({ url: '/api/platform/operations/demo/scenarios', params: { targetCode } }),
  start: (scenarioCode: string) => request<Incident>({ method: 'POST', url: '/api/platform/operations/demo/scenarios', data: { scenarioCode, ttlSeconds: 900 } }),
  restore: (target: Target, idempotencyKey = `manual-${crypto.randomUUID()}`) => request<RestoreResult>({ method: 'POST', url: '/api/platform/operations/demo/actions', data: { incidentId: target.incidentId,
    expectedRevision: target.expectedRevision, action: ({ NACOS_REDIS_CONFIG_DRIFT: 'RESTORE_CONFIGURATION', SENTINEL_RULE_REGRESSION: 'RESTORE_FLOW_RULE', RABBITMQ_CONSUMER_PAUSED: 'RESTORE_QUEUE_CONSUMER' } as Record<string, string>)[target.scenarioCode || ''], idempotencyKey } }),
};
