export interface AiContext { page?: string; service?: string; ticketId?: number; alertId?: string; environment?: string; timeRange?: string; evidenceBundleId?: string }
const identifier = (value: unknown, limit = 120) => typeof value === 'string' && value.length <= limit && /^[\w.:-]+$/.test(value) ? value : undefined;
export function cleanAiContext(context: AiContext): AiContext {
  return { page: typeof context.page === 'string' && /^\/[\w/-]*$/.test(context.page) ? context.page : undefined,
    service: identifier(context.service), ticketId: Number.isSafeInteger(context.ticketId) && Number(context.ticketId) > 0 ? context.ticketId : undefined,
    alertId: identifier(context.alertId), environment: identifier(context.environment, 40), timeRange: identifier(context.timeRange, 20), ...(identifier(context.evidenceBundleId, 120) ? { evidenceBundleId: identifier(context.evidenceBundleId, 120) } : {}) };
}
export function contextFromRoute(route: { path: string; params: Record<string, unknown>; query: Record<string, unknown> }): AiContext {
  return cleanAiContext({ page: route.path,
    service: typeof route.query.alertService === 'string' ? route.query.alertService : typeof route.query.ciCode === 'string' ? route.query.ciCode : typeof route.query.service === 'string' ? route.query.service : undefined,
    ticketId: Number(route.path.startsWith('/tickets/') ? route.params.id : route.query.ticketId) || undefined,
    alertId: typeof route.query.alertId === 'string' ? route.query.alertId : undefined,
    environment: typeof route.query.environment === 'string' ? route.query.environment : undefined,
    timeRange: typeof route.query.timeRange === 'string' ? route.query.timeRange : undefined });
}
export function contextLabel(context: AiContext) {
  return [context.service, context.ticketId ? `事件 #${context.ticketId}` : '', context.alertId ? `告警 ${context.alertId}` : '', context.environment, context.timeRange].filter(Boolean).join(' · ');
}
const contextMarker = '\n\n当前页面上下文：';
const analysisInstruction = '请针对当前对象结合可读取的实时数据与知识来源进行只读分析，区分事实、推断及证据缺口；无权限或无数据时请明确说明。';
export function assistantQuestionBody(question: string) {
  const start = question.indexOf(contextMarker);
  return start >= 0 && question.slice(start).includes(analysisInstruction) ? question.slice(0, start) : question;
}
export function assistantQuestionEvidence(question: string) {
  const body = assistantQuestionBody(question);
  return body === question ? '' : question.slice(body.length).trim();
}
export function contextualQuestion(question: string, context: AiContext, evidence = '') {
  const body = assistantQuestionBody(question);
  const scope = contextLabel(cleanAiContext(context));
  if (!scope) return body.trim().slice(0, 2000);
  // Scope identifiers are hints for existing authorized retrieval; never fabricate live observations.
  const suffix = `${contextMarker}${scope}。${analysisInstruction}${evidence ? '\n' + evidence.slice(0, 1350) : ''}`;
  return body.trim().slice(0, Math.max(0, 2000 - suffix.length)) + suffix;
}
