import { request } from "./http";
import type {
  AiAnswer,
  AiQuestion,
  AiReference,
  AiTask,
  CurrentUser,
  DocumentChunk,
  DocumentRecord,
  LoginResponse,
  NotificationRecord,
  OperationLog,
  PageResponse,
  Ticket,
  TicketComment,
  TicketLog,
  TicketTrace,
  TicketWorkRecord,
  WorkRecordType,
} from "@/types/api";

export const authApi = {
  login: (data: { username: string; password: string }) =>
    request<LoginResponse>({ method: "POST", url: "/api/auth/login", data }),
  register: (data: {
    username: string;
    password: string;
    displayName?: string;
  }) => request<void>({ method: "POST", url: "/api/auth/register", data }),
  me: () => request<CurrentUser>({ url: "/api/auth/me" }),
};

export const ticketApi = {
  page: async (params: Record<string, unknown>) => {
    const all = await request<Ticket[]>({ url: "/api/tickets" });
    const keyword = String(params.keyword || "").trim().toLowerCase();
    const status = String(params.status || "");
    const priority = String(params.priority || "");
    const pageNum = Math.max(Number(params.pageNum || 1), 1);
    const pageSize = Math.max(Number(params.pageSize || 10), 1);
    const filtered = all.filter(
      (ticket) =>
        (!keyword ||
          ticket.ticketNo.toLowerCase().includes(keyword) ||
          ticket.title.toLowerCase().includes(keyword) ||
          ticket.description.toLowerCase().includes(keyword)) &&
        (!status || ticket.status === status) &&
        (!priority || ticket.priority === priority),
    );
    const records = filtered.slice((pageNum - 1) * pageSize, pageNum * pageSize);
    return {
      records,
      total: filtered.length,
      pageNum,
      pageSize,
    } as PageResponse<Ticket>;
  },
  detail: (id: number) => request<Ticket>({ url: `/api/tickets/${id}` }),
  create: (data: {
    title: string;
    description: string;
    priority: string;
    affectedCiCode?: string;
  }) =>
    request<Ticket>({ method: "POST", url: "/api/tickets", data }),
  update: (
    _id: number,
    _data: Partial<Pick<Ticket, "title" | "description" | "priority">>,
  ) =>
    Promise.reject(new Error("仅 CREATED 工单可编辑，编辑接口将在下一批迁移")),
  action: async (
    id: number,
    action:
      | "accept"
      | "start"
      | "suspend"
      | "resume"
      | "waitConfirm"
      | "resolve"
      | "reopen"
      | "close",
    remark: string,
  ) => {
    const current = await request<Ticket>({ url: `/api/tickets/${id}` });
    if (action === "accept")
      return request<Ticket>({
        method: "POST",
        url: `/api/tickets/${id}/claim`,
        data: { version: current.version },
      });
    const targets = {
      start: "PROCESSING",
      suspend: "SUSPENDED",
      resume: "PROCESSING",
      waitConfirm: "WAITING_CONFIRM",
      resolve: "RESOLVED",
      reopen: "PROCESSING",
      close: "CLOSED",
    } as const;
    return request<Ticket>({
      method: "POST",
      url: `/api/tickets/${id}/transition`,
      data: { target: targets[action], version: current.version, remark },
    });
  },
  logs: (id: number) =>
    request<TicketLog[]>({ url: `/api/tickets/${id}/history` }),
  comments: (id: number) =>
    request<TicketComment[]>({ url: `/api/tickets/${id}/comments` }),
  comment: (id: number, content: string) =>
    request<TicketComment>({
      method: "POST",
      url: `/api/tickets/${id}/comments`,
      data: { content },
    }),
  workRecords: (id: number) =>
    request<TicketWorkRecord[]>({ url: `/api/tickets/${id}/work-records` }),
  addWorkRecord: (
    id: number,
    data: { recordType: WorkRecordType; content: string; evidence?: string },
  ) =>
    request<TicketWorkRecord>({
      method: "POST",
      url: `/api/tickets/${id}/work-records`,
      data,
    }),
  trace: (id: number) =>
    request<TicketTrace>({ url: `/api/tickets/${id}/trace` }),
  remove: (_id: number) =>
    Promise.reject(new Error("微服务版不提供直接物理删除工单")),
};

export const itsmApi = {
  cis: (params: { keyword?: string; type?: string } = {}) =>
    request<Record<string, unknown>[]>({ url: "/api/platform/cmdb/cis", params }),
  topology: (ciCode: string) =>
    request<Record<string, unknown>>({
      url: `/api/platform/cmdb/cis/${encodeURIComponent(ciCode)}/topology`,
    }),
  createCi: (data: Record<string, unknown>) =>
    request<Record<string, unknown>>({
      method: "POST",
      url: "/api/platform/cmdb/cis",
      data,
    }),
  updateCi: (id: number, data: Record<string, unknown>) =>
    request<Record<string, unknown>>({
      method: "PUT",
      url: `/api/platform/cmdb/cis/${id}`,
      data,
    }),
  createRelation: (data: Record<string, unknown>) =>
    request<Record<string, unknown>>({
      method: "POST",
      url: "/api/platform/cmdb/relations",
      data,
    }),
  deleteRelation: (id: number) =>
    request<void>({
      method: "DELETE",
      url: `/api/platform/cmdb/relations/${id}`,
    }),
  schedules: () =>
    request<Record<string, unknown>[]>({ url: "/api/platform/oncall/schedules" }),
  shifts: (scheduleId?: number) =>
    request<Record<string, unknown>[]>({
      url: "/api/platform/oncall/shifts",
      params: { scheduleId },
    }),
  currentOnCall: (serviceCiCode?: string) =>
    request<Record<string, unknown>>({
      url: "/api/platform/oncall/current",
      params: { serviceCiCode },
    }),
  createSchedule: (data: Record<string, unknown>) =>
    request<Record<string, unknown>>({
      method: "POST",
      url: "/api/platform/oncall/schedules",
      data,
    }),
  updateSchedule: (id: number, data: Record<string, unknown>) =>
    request<Record<string, unknown>>({
      method: "PUT",
      url: `/api/platform/oncall/schedules/${id}`,
      data,
    }),
  createShift: (data: Record<string, unknown>) =>
    request<Record<string, unknown>>({
      method: "POST",
      url: "/api/platform/oncall/shifts",
      data,
    }),
  updateShift: (id: number, data: Record<string, unknown>) =>
    request<Record<string, unknown>>({
      method: "PUT",
      url: `/api/platform/oncall/shifts/${id}`,
      data,
    }),
  deleteShift: (id: number) =>
    request<void>({
      method: "DELETE",
      url: `/api/platform/oncall/shifts/${id}`,
    }),
  slaOverview: () =>
    request<Record<string, unknown>[]>({ url: "/api/tickets/sla/overview" }),
  ticketSla: (ticketId: number) =>
    request<Record<string, unknown>>({ url: `/api/tickets/${ticketId}/sla` }),
  alerts: (status = "") =>
    request<Record<string, unknown>[]>({
      url: "/api/tickets/alerts",
      params: { status },
    }),
  reviewDocuments: (status = "") =>
    request<Record<string, unknown>[]>({
      url: "/api/knowledge/review/documents",
      params: { status },
    }),
  submitReview: (documentId: number) =>
    request<Record<string, unknown>>({
      method: "POST",
      url: `/api/knowledge/documents/${documentId}/submit-review`,
    }),
  approveDocument: (documentId: number, comment = "") =>
    request<Record<string, unknown>>({
      method: "POST",
      url: `/api/knowledge/documents/${documentId}/approve`,
      data: { comment },
    }),
  rejectDocument: (documentId: number, comment: string) =>
    request<Record<string, unknown>>({
      method: "POST",
      url: `/api/knowledge/documents/${documentId}/reject`,
      data: { comment },
    }),
  archiveDocument: (documentId: number, comment = "") =>
    request<Record<string, unknown>>({
      method: "POST",
      url: `/api/knowledge/documents/${documentId}/archive`,
      data: { comment },
    }),
};

export const documentApi = {
  list: async (_ticketId: number) => {
    const rows = await request<Record<string, unknown>[]>({
      url: `/api/knowledge/tickets/${_ticketId}/documents`,
    });
    return rows.map((row) => ({
      id: Number(row.id),
      ticketId: 0,
      originalName: String(row.original_name),
      contentType: "",
      fileExtension: String(row.file_type),
      fileSize: Number(row.file_size),
      fileHash: String(row.content_hash),
      parseStatus:
        row.status === "PARSED" || row.status === "INDEXED"
          ? "SUCCESS"
          : (String(row.status) as DocumentRecord["parseStatus"]),
      parseError: row.parse_error ? String(row.parse_error) : undefined,
      createBy: Number(row.create_by),
      createTime: String(row.create_time),
      updateTime: String(row.update_time),
    }));
  },
  upload: async (_ticketId: number, file: File) => {
    const data = new FormData();
    data.append("file", file);
    data.append("ticketId", String(_ticketId));
    const id = await request<number>({
      method: "POST",
      url: "/api/knowledge/bases/1/documents",
      data,
    });
    return { id } as DocumentRecord;
  },
  parse: async (id: number) => {
    await request<void>({
      method: "POST",
      url: `/api/knowledge/documents/${id}/parse`,
    });
    return { id, parseStatus: "SUCCESS" } as DocumentRecord;
  },
  chunks: (id: number) =>
    request<DocumentChunk[]>({ url: `/api/knowledge/documents/${id}/chunks` }),
  remove: (id: number) =>
    request<{ documentId: number; taskId: number; indexStatus: string }>({
      method: "DELETE",
      url: `/api/knowledge/documents/${id}`,
    }),
};

export const aiApi = {
  ask: async (
    ticketId: number,
    data: { question: string; documentId?: number; topK?: number },
  ) => {
    const result = await request<{
      answer: string;
      references: AiReference[];
      model: string;
      provider: string;
      latencyMs: number;
    }>({
      method: "POST",
      url: "/api/rag/ask",
      data: {
        question: data.question,
        topK: data.topK || 5,
        documentId: data.documentId,
      },
    });
    return {
      questionId: Date.now(),
      ticketId,
      answer: result.answer,
      modelName: `${result.provider}/${result.model}`,
      costTimeMs: result.latencyMs,
      references: result.references,
    } as AiAnswer;
  },
  page: async (_ticketId: number, _params = { pageNum: 1, pageSize: 20 }) =>
    ({
      records: [],
      total: 0,
      pageNum: 1,
      pageSize: 20,
    }) as PageResponse<AiQuestion>,
  detail: (_id: number): Promise<AiQuestion> =>
    Promise.reject(new Error("问答历史接口尚未启用")),
};

export const adminApi = {
  knowledgeIndexConsistency: () =>
    request<Record<string, unknown>>({
      url: "/api/knowledge/admin/index/consistency",
    }),
  requestKnowledgeReindex: () =>
    request<number>({
      method: "POST",
      url: "/api/knowledge/admin/reindex",
    }),
  knowledgeReindexTask: (taskId: number) =>
    request<Record<string, unknown>>({
      url: `/api/knowledge/admin/reindex/${taskId}`,
    }),
  failedKnowledgeIndexTasks: () =>
    request<Record<string, unknown>[]>({
      url: "/api/knowledge/admin/index/failed-tasks",
    }),
  repairKnowledgeIndex: (documentId: number) =>
    request<number>({
      method: "POST",
      url: `/api/knowledge/admin/index/repair/${documentId}`,
    }),
  notifications: (params: {
    pageNum: number;
    pageSize: number;
    status?: string;
  }) =>
    request<PageResponse<Record<string, unknown>> & { unreadTotal: number }>({
      url: "/api/platform/admin/notifications",
      params,
    }).then((page) => ({
      ...page,
      records: page.records.map(
        (row) =>
          ({
            id: Number(row.id),
            ticketId: Number(row.ticket_id),
            receiver: String(row.receiver_id),
            title: String(row.title),
            content: String(row.content),
            status: String(row.status),
            createTime: String(row.create_time),
          }) as NotificationRecord,
      ),
    })),
  notificationStatus: (id: number, status: string) =>
    request<NotificationRecord>({
      method: "PUT",
      url: `/api/platform/admin/notifications/${id}/status`,
      params: { status },
    }),
  readAllNotifications: () =>
    request<{ updated: number; unreadTotal: number }>({
      method: "PUT",
      url: "/api/platform/admin/notifications/read-all",
    }),
  audits: (params: {
    pageNum: number;
    pageSize: number;
    bizId?: string;
    operation?: string;
  }) =>
    request<PageResponse<Record<string, unknown>>>({
      url: "/api/platform/admin/audits",
      params,
    }).then((page) => ({
      ...page,
      records: page.records.map(
        (row) =>
          ({
            id: Number(row.id),
            serviceName: String(row.service_name || ""),
            bizType: String(row.biz_type),
            bizId: Number(row.biz_id || 0),
            operationType: String(row.operation),
            operator: String(row.user_id || "系统"),
            traceId: String(row.trace_id || ""),
            content: String(row.detail_json || "无附加信息"),
            createTime: String(row.create_time),
          }) as OperationLog,
      ),
    })),
  tasks: (params: { pageNum: number; pageSize: number }) =>
    request<PageResponse<AiTask>>({ url: "/api/tasks/ai", params }),
  taskStatus: (id: number, status: string) =>
    request<AiTask>({
      method: "PUT",
      url: `/api/tasks/ai/${id}/status`,
      params: { status },
    }),
};
