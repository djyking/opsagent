import { request } from "./http";

export interface KnowledgeIndexTask {
  id: number;
  documentId: number;
  documentVersion: number;
  currentDocumentVersion: number | null;
  operation: "INDEX" | "DELETE";
  status: string;
  retryCount: number;
  lastError: string | null;
  documentName: string | null;
  documentStatus: string | null;
  reviewStatus: string | null;
  documentDeleted: boolean;
  repairable: boolean;
  repairReason: string;
  updateTime: string | null;
}

export const knowledgeIndexApi = {
  failedTasks: () => request<KnowledgeIndexTask[]>({ url: "/api/knowledge/admin/index/failed-tasks" }),
  retryTask: (task: KnowledgeIndexTask) => request<number>({
    method: "POST",
    url: `/api/knowledge/admin/index/tasks/${task.id}/retry`,
    data: { documentVersion: task.documentVersion, operation: task.operation },
  }),
  latestReindexTask: () => request<Record<string, unknown> | null>({ url: "/api/knowledge/admin/reindex/latest" }),
};
