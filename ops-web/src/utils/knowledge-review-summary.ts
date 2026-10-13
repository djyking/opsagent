/** Extract only source text; missing sections stay explicitly unprovided. */
export function knowledgeSummary(text: string) {
  const sections = text.split(/\n(?=#{1,3}\s)/);
  const find = (pattern: RegExp) => sections.find(section => pattern.test(section.split('\n')[0] || ''))
    ?.replace(/^#{1,3}\s+[^\n]+\n?/, '').trim();
  const compact = (value?: string, max = 520) => value ? value.slice(0, max) + (value.length > max ? '…' : '') : '原文未提供此项，请查看全文核对。';
  return {
    structured: !!(find(/症状|事实|事故概况|事件概述|问题描述/) || find(/处置运行|有效处置|处理过程|解决方案|处置措施/) || find(/恢复验证|验证结果/)),
    excerpt: compact(text.trim(), 420),
    incident: compact(find(/症状|事实|事故概况|事件概述|问题描述/)),
    actions: compact(find(/处置运行|有效处置|处理过程|解决方案|处置措施/)),
    verification: compact(find(/恢复验证|验证结果/), 360),
    limits: compact(find(/证据缺口|适用|限制|注意事项/), 900),
    rootPending: /待.*(?:核对|复核)|候选原因|尚未.*确认/.test(text),
  };
}
export function knowledgeRetrievalLabel(reviewStatus: unknown, indexStatus: unknown) {
  if (reviewStatus !== 'PUBLISHED') return '审核发布后再建立索引';
  return ({ SUCCESS: '索引已完成，可按知识权限检索', FAILED: '已发布，索引失败', PROCESSING: '已发布，索引处理中', PENDING: '已发布，等待索引' } as Record<string, string>)[String(indexStatus)] || '已发布，索引状态待核实';
}
