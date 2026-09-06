import { request } from "./http";

export interface OperationsTarget {
  service: string; ciCode: string; health: "up" | "down" | "unknown"; observedAt: string;
}

export interface OperationsMetric {
  id: string; label: string; job: string; unit: string;
  currentValue: number | null; forecastValue: number | null; slopePerMinute: number | null;
  sampleCount: number; status: "OK" | "RISK" | "UNKNOWN"; method: string; reason: string;
  observedAt: string | null; points: { timestamp: string; value: number }[];
}

export interface OperationsRisk {
  key: string; severity: string; title: string; detail: string; evidence: string; recommendation: string;
}

export interface OperationsOverview {
  capturedAt: string; source: string; status: "HEALTHY" | "ATTENTION" | "UNKNOWN";
  summary: string; windowMinutes: number; targets: OperationsTarget[];
  metrics: OperationsMetric[]; risks: OperationsRisk[];
  nacos: {
    status: string; message: string; serviceCount: number | null;
    healthyInstanceCount: number | null; configurationCount: number | null;
    services: { name: string; instanceCount: number | null; healthyInstanceCount: number | null }[];
    configurations: { dataId: string; group: string; modifiedAt: string }[];
  };
  sentinel: {
    status: string; message: string; ruleSource: string;
    rules: { resource: string; grade: string; count: number; controlBehavior: string }[];
    passedTotal: number | null; blockedTotal: number | null; metricsObservedAt: string | null;
  };
}

export interface OperationsWorkflow {
  code: "HEALTH_CHECK" | "ISOLATED_DRILL"; title: string; description: string;
  mode: "READ_ONLY" | "ISOLATED"; steps: string[]; available: boolean; unavailableReason: string;
  scheduleEnabled: boolean; intervalMinutes: number; lastRunAt: string | null;
}

export interface OperationsRun {
  id: number; workflowCode: string; title: string; mode: string;
  status: "RUNNING" | "SUCCEEDED" | "ATTENTION" | "FAILED";
  summary: string; startedAt: string; finishedAt: string | null; actor: string;
}

export interface OperationsRunDetail extends OperationsRun {
  steps: { id: number; sequence: number; title: string; status: string; detail: string;
    evidence: string; startedAt: string; finishedAt: string | null }[];
  audits: { action: string; detail: string; createdAt: string; actor: string }[];
}

export const operationsApi = {
  overview: (windowMinutes = 60) => request<OperationsOverview>({
    url: "/api/platform/operations/overview", params: { windowMinutes },
  }),
  workflows: () => request<OperationsWorkflow[]>({ url: "/api/platform/operations/workflows" }),
  runs: (pageNum = 1, pageSize = 10) => request<{ records: OperationsRun[]; total: number;
    pageNum: number; pageSize: number }>({ url: "/api/platform/operations/runs", params: { pageNum, pageSize } }),
  run: (id: number) => request<OperationsRunDetail>({ url: `/api/platform/operations/runs/${id}` }),
  start: (workflowCode: OperationsWorkflow["code"]) => request<OperationsRunDetail>({
    method: "POST", url: "/api/platform/operations/runs",
    data: { workflowCode, ...(workflowCode === "ISOLATED_DRILL" ? { scenario: "SERVICE_UNAVAILABLE" } : {}) },
  }),
};
