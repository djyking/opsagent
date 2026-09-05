import { request } from "./http";
import { normalizeReferences, type RagStreamResult } from "./rag-stream";

export interface Conversation { id: string; title: string; createTime: string; updateTime: string }
export interface ConversationTurn {
  id: number;
  question: string;
  answer: string;
  status: "PROCESSING" | "COMPLETE" | "INCOMPLETE" | "INTERRUPTED";
  errorMessage?: string;
  createTime: string;
  result?: RagStreamResult;
}
export const conversationApi = {
  list: (page = 1) => request<{ records: Conversation[]; total: number }>({ url: "/api/rag/conversations", params: { page, pageSize: 20 } }),
  create: () => request<Conversation>({ url: "/api/rag/conversations", method: "POST", data: {} }),
  rename: (id: string, title: string) => request<Conversation>({ url: `/api/rag/conversations/${id}`, method: "PATCH", data: { title } }),
  remove: (id: string) => request<void>({ url: `/api/rag/conversations/${id}`, method: "DELETE" }),
  async messages(id: string, beforeId?: number) {
    const page = await request<{records: ConversationTurn[]; hasMore: boolean}>({ url: `/api/rag/conversations/${id}/messages`, params: { beforeId } });
    for (const turn of page.records) {
      if (turn.result) turn.result.references = normalizeReferences(turn.result.references as unknown as Record<string, unknown>[]);
    }
    return page;
  },
};
