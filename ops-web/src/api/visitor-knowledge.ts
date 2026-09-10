import { request } from '@/api/http';

export interface ExperienceDocument {
  id: number; original_name: string; file_type: string; file_size: number;
  status: string; stage: 'UPLOADED' | 'QUEUED' | 'PARSING' | 'INDEXING' | 'INDEXED' | 'FAILED';
  chunk_count: number; embedding_model?: string; parse_error?: string;
  embedding_tokens: number; embedding_unknown_calls: number; embedding_calls: number;
  create_time: string; version: number;
}
export interface ExperienceLibrary {
  baseId: number; name: string; expiresAt: string; documents: ExperienceDocument[]; usedBytes: number;
  limits: { files: number; fileBytes: number; totalBytes: number; characters: number; chunks: number; concurrent: number };
}
const prefix = '/api/knowledge/experience';
export const visitorKnowledgeApi = {
  get: () => request<ExperienceLibrary>({ url: prefix }),
  upload: (file: File, ticketId?: number) => { const data = new FormData(); data.append('file', file); if (ticketId !== undefined) data.append('ticketId', String(ticketId)); return request<number>({ method: 'POST', url: `${prefix}/documents`, data }); },
  process: (id: number, indexOnly = false) => request<void>({ method: 'POST', url: `${prefix}/documents/${id}/${indexOnly ? 'index' : 'parse'}` }),
  remove: (id: number) => request<void>({ method: 'DELETE', url: `${prefix}/documents/${id}` }),
  chunks: (id: number) => request<{ id: number; chunk_index: number; token_count: number; content: string }[]>({ url: `/api/knowledge/documents/${id}/chunks` }),
};
