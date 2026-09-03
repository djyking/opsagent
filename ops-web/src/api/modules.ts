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
  TicketLog,
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
  page: async (_params: Record<string, unknown>) => {
    const records = await request<Ticket[]>({ url: "/api/tickets" });
    return {
      records,
      total: records.length,
      pageNum: 1,
      pageSize: Math.max(records.length, 1),
    } as PageResponse<Ticket>;
  },
  detail: (id: number) => request<Ticket>({ url: `/api/tickets/${id}` }),
  create: (data: { title: string; description: string; priority: string }) =>
    request<Ticket>({ method: "POST", url: "/api/tickets", data }),
  update: (
    _id: number,
    _data: Partial<Pick<Ticket, "title" | "description" | "priority">>,
  ) =>
    Promise.reject(new Error("仅 CREATED 工单可编辑，编辑接口将在下一批迁移")),
  action: async (
    id: number,
    action: "accept" | "resolve" | "close",
    remark: string,
  ) => {
    const current = await request<Ticket>({ url: `/api/tickets/${id}` });
    if (action === "accept")
      return request<Ticket>({
        method: "POST",
        url: `/api/tickets/${id}/claim`,
        data: { version: current.version },
      });
    const target = action === "resolve" ? "RESOLVED" : "CLOSED";
    return request<Ticket>({
      method: "POST",
      url: `/api/tickets/${id}/transition`,
      data: { target, version: current.version, remark },
    });
  },
  logs: (id: number) =>
    request<TicketLog[]>({ url: `/api/tickets/${id}/history` }),
  remove: (_id: number) =>
    Promise.reject(new Error("微服务版不提供直接物理删除工单")),
};

export const documentApi = {
  list: async (_ticketId: number) => {
    const rows = await request<Record<string, unknown>[]>({
      url: "/api/knowledge/bases/1/documents",
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
  remove: (_id: number) =>
    Promise.reject(new Error("文档删除接口将在对象存储补偿完成后启用")),
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
  notifications: (params: { pageNum: number; pageSize: number }) =>
    request<PageResponse<NotificationRecord>>({
      url: "/api/notifications",
      params,
    }),
  notificationStatus: (id: number, status: string) =>
    request<NotificationRecord>({
      method: "PUT",
      url: `/api/notifications/${id}/status`,
      params: { status },
    }),
  audits: (params: { pageNum: number; pageSize: number }) =>
    request<PageResponse<OperationLog>>({
      url: "/api/audit/operation-logs",
      params,
    }),
  tasks: (params: { pageNum: number; pageSize: number }) =>
    request<PageResponse<AiTask>>({ url: "/api/tasks/ai", params }),
  taskStatus: (id: number, status: string) =>
    request<AiTask>({
      method: "PUT",
      url: `/api/tasks/ai/${id}/status`,
      params: { status },
    }),
};
