import { ApiError, request } from "./http";

export interface ReviewChunk {
  id: number;
  chunkIndex: number;
  content: string;
  tokenCount: number | null;
  pageNumber: number | null;
}

export interface ReviewPreview {
  documentId: number;
  originalName: string;
  fileType: string;
  fileSize: number;
  version: number;
  parseStatus: string;
  reviewStatus: string;
  parseError: string;
  sourceAvailable: boolean;
  total: number;
  pageNum: number;
  pageSize: number;
  chunks: ReviewChunk[];
}

export const knowledgeReviewApi = {
  preview: (id: number, pageNum = 1) => request<ReviewPreview>({
    url: `/api/knowledge/review/documents/${id}/preview`, params: { pageNum, pageSize: 8 },
  }),
  text: (id: number) => request<{ documentId: number; version: number; chunkCount: number; text: string }>({
    url: `/api/knowledge/review/documents/${id}/text`,
  }),
  source: async (id: number): Promise<Blob> => {
    const token = localStorage.getItem("opsagent_token");
    const response = await fetch(`${import.meta.env.VITE_API_BASE_URL || ""}/api/knowledge/review/documents/${id}/source`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      cache: "no-store",
      signal: AbortSignal.timeout(65_000),
    });
    if (response.status === 401) {
      localStorage.removeItem("opsagent_token");
      localStorage.removeItem("opsagent_token_expire_at");
      if (!location.pathname.startsWith("/login")) location.assign("/login");
    }
    if (response.headers.get("content-type")?.includes("json")) {
      const body = await response.json();
      throw new ApiError(body.message || "原文件下载失败", response.status, body.code);
    }
    if (!response.ok) throw new ApiError("原文件下载失败，请稍后重试", response.status);
    return response.blob();
  },
};
