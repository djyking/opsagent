import { request } from './http';

export interface EventFact { id: string; label: string; value: string; source: string; observedAt: string | null; scope: string }
export interface EventChange { id: string; kind: string; summary: string; observedAt: string | null; status: string; source: string;
  revisionBefore: string | null; revisionAfter: string | null; before?: Record<string, unknown>; after?: Record<string, unknown> }
export interface EventRun { id: string; status: string; nodeId: string; createdAt: string; updatedAt: string | null; ticketResolved: boolean; message: string }
export interface EventWorkspace {
  schemaVersion: number; ticketId: number; incidentId: string | null; targetCode: string | null; generatedAt: string;
  stage: { code: string; label: string; basis: string };
  access: { runScope: string; operationalEvidence: boolean; notice: string };
  actions: { canDiagnose: boolean; reason: string; definitionId: string | null };
  facts: EventFact[];
  hypotheses: { id: string; title: string; reason: string; evidenceIds: string[]; source: string; runId: string }[];
  gaps: { id: string; message: string; source: string }[];
  changes: EventChange[];
  verification: { status: string; label: string; scope: string; source: string; observedAt: string | null;
    incidentMatched: boolean; businessHealthy: boolean; consecutiveSuccesses: number; alertResolved: boolean; agentAttributed: boolean };
  runs: EventRun[]; runTotal: number; latestRunId: string | null; pendingApprovalIds: string[];
  diagnosis: { summary: string; knownFacts: string[]; candidateCauses: string[]; evidenceGaps: string[]; recordedAt: string | null; runId: string | null };
  sources: { name: string; status: string; message: string }[];
  limits: { runs: number; changes: number };
}
export const eventWorkspaceApi = {
  read: (ticketId: number) => request<EventWorkspace>({ url: `/api/automation/tickets/${ticketId}/workspace`, timeout: 25000 }),
};
