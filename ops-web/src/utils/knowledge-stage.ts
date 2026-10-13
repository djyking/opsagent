export interface KnowledgeStageInput { status: string; review_status?: string; index_status?: string; chunk_count?: number }
/** One display stage, while parsing, review and indexing remain independent source states. */
export function knowledgeStage(document: KnowledgeStageInput) {
  if (document.status === 'FAILED') return { label: '解析失败', tone: 'danger' };
  if (document.index_status === 'FAILED') return { label: '索引失败', tone: 'danger' };
  if (document.status === 'PARSING') return { label: '正在解析', tone: 'primary' };
  if (!['PARSED', 'INDEXED'].includes(document.status)) return { label: '待解析', tone: 'muted' };
  if (document.chunk_count === 0) return { label: '内容待补齐', tone: 'warning' };
  if (document.review_status === 'REJECTED') return { label: '审核退回', tone: 'warning' };
  if (document.review_status === 'PUBLISHED') {
    return document.index_status === 'SUCCESS' || (!document.index_status && document.status === 'INDEXED') ? { label: '可检索', tone: 'success' } : { label: '已发布待索引', tone: 'primary' };
  }
  if (['PENDING', 'PENDING_REVIEW', 'IN_REVIEW'].includes(document.review_status || '')) return { label: '待审核', tone: 'primary' };
  return { label: '待提交审核', tone: 'muted' };
}
