export interface AiContext { page?: string; service?: string; ticketId?: number; documentId?: number; alertId?: string; environment?: string; timeRange?: string }
export interface AiObservabilityContext { service: string; environment: 'PROD' | 'DEMO'; timeRange: '5m' | '15m' | '30m' | '1h' | '6h' }
export function isProductGuideQuestion(question: string) {
  const body = assistantQuestionBody(question).trim();
  return /(?:系统|平台|OpsAgent).*(?:怎么用|如何使用|使用指南|使用说明|功能介绍|有什么功能|有哪些功能|能做什么|怎么开始)|(?:介绍|了解).*(?:系统|平台|OpsAgent).*(?:功能|使用)?|^(?:系统使用指南|功能介绍|使用指南|你能做什么|你可以做什么)[？?。！!]*$|访客.*(?:能做什么|能用什么|权限|如何使用|怎么用|可以.*(?:审批|上传|操作)|有效期|多久|额度)|(?:怎么|如何).*(?:发起|启动|审批|体验).*(?:演练|自动化)|(?:怎么|如何).*(?:上传.*(?:文档|资料)|解析.*文档|文档.*(?:解析|切片|向量化|问答)|查看切片|私有问答|体验库)|(?:配置中心|配置变更).*(?:怎么用|如何使用|权限|谁能|只读|管理员)/i.test(body)
    && !/(?:如何|怎么)(?:设计|实现)|(?:当前|现在|实时|最近).*(?:故障|异常|健康|日志|负载|恢复了吗|运行状态|配置值)|(?:文档|附件|工单)(?:中|里|内容|记载|写|说)|根据.*(?:文档|附件|工单)|(?:密码|密钥|令牌|token|api.key).*(?:多少|是什么|告诉|给我|查询|输出)/i.test(body);
}
export function isConversation(question: string) {
  const text = assistantQuestionBody(question).toLowerCase().replace(/[\s\p{P}]+/gu, '');
  return /^(?:(?:你好|您好|嗨|哈喽|早上好|下午好|晚上好|早安|晚安|谢谢你?|再见|好的|收到|在吗|你是谁|你叫什么(?:名字)?|你能做什么|你可以做什么|介绍一下你自己|自我介绍|hello|hi|hey|thanks|thankyou|goodmorning|goodnight)[啊呀哦呢吗吧]*)+$/.test(text);
}
export function isRunbookCatalogQuestion(question: string) {
  return /(?:runbook|处置手册).*(?:推荐|目录|有哪些|在哪|入口|执行|运行|启动)|(?:推荐|查找|列出|有哪些|执行|运行|启动).*(?:runbook|处置手册)/i.test(assistantQuestionBody(question));
}
export function requestContextForQuestion(scope: AiContext, question: string): AiContext {
  if (isConversation(question) || isProductGuideQuestion(question)) return {};
  const context = cleanAiContext(scope);
  if (context.documentId) return { documentId: context.documentId, ...(context.ticketId ? { ticketId: context.ticketId } : {}) };
  if (isRunbookCatalogQuestion(question)) return context;
  const body = assistantQuestionBody(question);
  if (/(?:什么是|原理|教程|如何|怎么|常见原因|通用|一般如何|介绍|解释|区别|推荐.*(?:Runbook|手册|文档))/i.test(body)
    && !/(?:当前|现在|最近|实时|本系统|本项目|我们|现场|该服务|这个服务|该事件|这个事件|这个文档|这份文档|附件|工单)/.test(body)) return {};
  return context;
}
export function serverObservabilityContext(scope: AiContext, question = ''): AiObservabilityContext | undefined {
  const context = requestContextForQuestion(scope, question);
  if (!context.service || context.service.length > 64) return undefined;
  return { service: context.service, environment: context.environment === 'PROD' ? 'PROD' : context.environment === 'DEMO' || context.service.startsWith('ops-demo-') ? 'DEMO' : 'PROD',
    timeRange: ['5m', '15m', '30m', '1h', '6h'].includes(context.timeRange || '') ? context.timeRange as AiObservabilityContext['timeRange'] : '15m' };
}
const identifier = (value: unknown, limit = 120) => typeof value === 'string' && value.length <= limit && /^[\w.:-]+$/.test(value) ? value : undefined;
export function cleanAiContext(context: AiContext): AiContext {
  return { page: typeof context.page === 'string' && /^\/[\w/-]*$/.test(context.page) ? context.page : undefined,
    service: identifier(context.service), ticketId: Number.isSafeInteger(context.ticketId) && Number(context.ticketId) > 0 ? context.ticketId : undefined,
    ...(Number.isSafeInteger(context.documentId) && Number(context.documentId) > 0 ? { documentId: context.documentId } : {}),
    alertId: identifier(context.alertId), environment: identifier(context.environment, 40), timeRange: identifier(context.timeRange, 20) };
}
export function contextFromRoute(route: { path: string; params: Record<string, unknown>; query: Record<string, unknown> }): AiContext {
  return cleanAiContext({ page: route.path,
    service: typeof route.query.alertService === 'string' ? route.query.alertService : typeof route.query.ciCode === 'string' ? route.query.ciCode : typeof route.query.service === 'string' ? route.query.service : undefined,
    ticketId: Number(route.path.startsWith('/tickets/') ? route.params.id : route.query.ticketId) || undefined,
    documentId: Number(route.query.documentId) || undefined,
    alertId: typeof route.query.alertId === 'string' ? route.query.alertId : undefined,
    environment: typeof route.query.environment === 'string' ? route.query.environment : undefined,
    timeRange: typeof route.query.timeRange === 'string' ? route.query.timeRange : undefined });
}
export function contextLabel(context: AiContext) {
  return [context.service, context.ticketId ? `事件 #${context.ticketId}` : '', context.documentId ? `所选文档 #${context.documentId}` : '', context.alertId ? `告警 ${context.alertId}` : '', context.environment, context.timeRange].filter(Boolean).join(' · ');
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
  const requestContext = requestContextForQuestion(context, body);
  // Document scope is sent as a validated request field, not a live-analysis instruction in the query text.
  if (requestContext.documentId) return body.trim().slice(0, 2000);
  const scope = contextLabel(requestContext);
  if (!scope) return body.trim().slice(0, 2000);
  // Scope identifiers are hints for existing authorized retrieval; never fabricate live observations.
  const suffix = `${contextMarker}${scope}。${analysisInstruction}${evidence ? '\n' + evidence.slice(0, 1350) : ''}`;
  return body.trim().slice(0, Math.max(0, 2000 - suffix.length)) + suffix;
}
