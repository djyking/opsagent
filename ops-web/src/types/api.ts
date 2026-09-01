export interface ApiResponse<T> { code: number; message: string; data: T; traceId?: string }
export interface PageResponse<T> { records: T[]; total: number; pageNum: number; pageSize: number }
export interface LoginResponse { accessToken: string; refreshToken: string; tokenType: string; expiresAt: string }
export interface CurrentUser { userId: number; username: string; displayName?: string; roles: string[]; permissions: string[] }

export type TicketStatus = 'CREATED' | 'ASSIGNED' | 'PROCESSING' | 'SUSPENDED' | 'WAITING_CONFIRM' | 'RESOLVED' | 'CLOSED' | 'REJECTED'
export type TicketPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'
export interface Ticket {
  id: number; ticketNo: string; title: string; description: string; priority: TicketPriority
  status: TicketStatus; creatorId: number; assigneeId?: number; version: number
  createTime: string; updateTime: string
}
export interface TicketLog {
  id: number; ticketId: number; operatorId: number; operationType: string
  fromStatus?: string; toStatus: string; remark?: string; createTime: string
}
export interface DocumentRecord {
  id: number; ticketId: number; originalName: string; contentType: string; fileExtension: string
  fileSize: number; fileHash: string; parseStatus: 'PENDING' | 'PARSING' | 'SUCCESS' | 'FAILED'
  parseError?: string; createBy: number; createTime: string; updateTime: string
}
export interface DocumentChunk {
  id: number; documentId: number; chunkIndex: number; content: string; tokenCount?: number
  pageNumber?: number; sectionTitle?: string; createTime: string
}
export interface AiReference {
  chunkId: number; documentId: number; chunkIndex: number; pageNumber?: number
  relevanceScore: number; excerpt: string
}
export interface AiAnswer {
  questionId: number; ticketId: number; documentId?: number; answer: string
  modelName: string; costTimeMs: number; references: AiReference[]
}
export interface AiQuestion {
  id: number; ticketId: number; documentId?: number; userId: number; question: string
  answer?: string; modelName?: string; promptTokens?: number; completionTokens?: number
  status: 'SUCCESS' | 'FAILED'; errorMessage?: string; costTimeMs?: number
  references: AiReference[]; createTime: string
}
export interface NotificationRecord {
  id: number; ticketId: number; receiver: string; title: string; content: string
  status: string; createTime: string
}
export interface OperationLog {
  id: number; bizType: string; bizId: number; operationType: string
  operator: string; content: string; createTime: string
}
export interface AiTask {
  id: number; bizType: string; bizId: number; taskType: string; status: string
  requestPayload?: string; result?: string; createTime: string; updateTime: string
}
