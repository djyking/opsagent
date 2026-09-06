export type WorkspaceActionIcon = 'TicketCheck' | 'MessageSquareText' | 'BookOpen' | 'CalendarClock' | 'TimerReset' | 'Network' | 'Activity' | 'Siren' | 'BookCheck' | 'DatabaseZap' | 'ShieldCheck';

export interface WorkspaceAction {
  id: string;
  label: string;
  description: string;
  keywords: readonly string[];
  icon: WorkspaceActionIcon;
  admin: boolean;
  path: string;
  query?: Readonly<Record<string, string>>;
}

export interface WorkspaceActionDestination {
  path: string;
  query?: Record<string, string>;
}

// This is a catalog of existing routes, not an AI inference or an execution API.
export const workspaceActions: readonly WorkspaceAction[] = [
  { id: 'configuration', label: '配置中心', description: '查看 Nacos 配置、实际应用状态和版本，管理员可校验、发布与回退', keywords: ['配置中心', 'Nacos配置', '配置文件', '配置管理', '配置发布', '配置回退'], icon: 'Activity', admin: false, path: '/configuration' },
  { id: 'automation', label: '自动化中心', description: '查看执行任务、审批状态、运行问题和恢复证据', keywords: ['自动化', 'AI自动化', 'Agent', 'Workflow', '工作流', '演练', '故障注入', '自动修复', '运行问题', '待审批', '查看审批', '动作审批'], icon: 'Activity', admin: false, path: '/automation' },
  { id: 'ticket-create', label: '新建事件', description: '记录业务影响，分派责任并跟进处置', keywords: ['创建工单', '新建工单', '创建一个工单', '创建事件', '开工单', '提单', '报修', '提交故障'], icon: 'TicketCheck', admin: false, path: '/tickets', query: { create: '1' } },
  { id: 'event-diagnosis', label: '诊断事件', description: '选择具体事件，在事件工作区核对证据、诊断与处置', keywords: ['AI诊断', '事件诊断', '诊断此事件', '诊断工单'], icon: 'TicketCheck', admin: false, path: '/tickets' },
  { id: 'rag', label: 'AI 助手', description: '通用问答与知识检索；问题带入会话草稿，确认后发送', keywords: ['AI助手', '智能问答', '智能排障', '排查', '分析故障', '问答', '提问', 'AI', 'Redis', 'RabbitMQ', 'MySQL', '连接超时', '消息堆积', '故障原因', '怎么处理', '如何处理'], icon: 'MessageSquareText', admin: false, path: '/rag/chat' },
  { id: 'ticket-search', label: '事件处置', description: '查看事件队列、责任人和处置进度', keywords: ['事件', '事件队列', '工单', '工单中心', '查询工单', '搜索工单', '查工单', '处理进度', '待处理', '我的工单', 'ticket'], icon: 'TicketCheck', admin: false, path: '/tickets' },
  { id: 'oncall', label: '值班排班', description: '查看当班人员、轮值计划和班次日历', keywords: ['值班', '排班', '谁在值班', '当班', '班次', '轮值', 'oncall'], icon: 'CalendarClock', admin: false, path: '/itsm/oncall' },
  { id: 'sla', label: 'SLA 看板', description: '查看响应与解决时限、超时风险及规则', keywords: ['SLA', '服务等级', '响应时限', '解决时限', '超时工单', '超时风险'], icon: 'TimerReset', admin: false, path: '/itsm/sla' },
  { id: 'cmdb', label: '服务目录与依赖拓扑', description: '查看服务信息、依赖关系和健康状态', keywords: ['服务目录', '服务拓扑', '拓扑', '依赖', 'CMDB', '服务关系'], icon: 'Network', admin: false, path: '/operations', query: { tab: 'topology' } },
  { id: 'knowledge', label: '知识与经验', description: '检索运维文档，维护可审核、可复用的知识', keywords: ['知识库', '知识', '文档', '手册', '知识检索', '运维规范', 'knowledge'], icon: 'BookOpen', admin: false, path: '/knowledge' },
  { id: 'knowledge-upload', label: '上传知识文档', description: '打开知识库上传入口，添加新的文档', keywords: ['上传知识', '上传文档', '导入文档', '添加文档'], icon: 'BookOpen', admin: false, path: '/knowledge', query: { upload: '1' } },
  { id: 'monitor', label: '服务与观测', description: '核对服务指标、依赖关系和运行配置', keywords: ['运维中心', '监控', '运行状态', '运行风险', '健康检查', '中间件', 'Prometheus', 'Grafana', 'Sentinel', 'Nacos'], icon: 'Activity', admin: false, path: '/operations' },
  { id: 'workflow', label: '持续巡检', description: '查看只读巡检任务与真实采集记录', keywords: ['持续巡检', '只读巡检', '自动巡检', '巡检记录'], icon: 'Activity', admin: false, path: '/automation', query: { tab: 'inspection' } },
  { id: 'alerts', label: '原始告警', description: '查看监控告警及其关联事件', keywords: ['活动告警', '告警', '报警', '异常事件', 'alert'], icon: 'Siren', admin: false, path: '/itsm/alerts' },
  { id: 'review', label: '知识审核', description: '审核待发布文档，查看审核意见', keywords: ['审核', '发布审批', '待发布', '待审核'], icon: 'BookCheck', admin: true, path: '/knowledge/review' },
  { id: 'index', label: '索引管理', description: '查看索引一致性、失败任务和修复入口', keywords: ['索引', '索引健康', '索引修复', '索引重建', 'Elasticsearch', 'Qdrant', '向量库'], icon: 'DatabaseZap', admin: true, path: '/knowledge/index-admin' },
  { id: 'audit', label: '操作审计', description: '查询系统操作记录和审计详情', keywords: ['审计', '操作记录', '操作日志', '审计日志'], icon: 'ShieldCheck', admin: true, path: '/admin' },
];

function normalize(value: string) {
  return value.normalize('NFKC').toLocaleLowerCase('en-US').replace(/[\s\p{P}\p{S}]+/gu, '');
}

function matchScore(action: WorkspaceAction, query: string) {
  const label = normalize(action.label);
  if (label === query) return 120;
  let score = label.includes(query) ? 70 : query.includes(label) ? 80 : 0;
  for (const keyword of action.keywords) {
    const term = normalize(keyword);
    if (term === query) score = Math.max(score, 110);
    else if (query.includes(term)) score = Math.max(score, 50 + Math.min(term.length, 20));
    else if (query.length >= 2 && term.includes(query)) score = Math.max(score, 35);
  }
  return score;
}

export function searchWorkspaceActions(query: string, isAdmin: boolean): WorkspaceAction[] {
  const allowed = workspaceActions.filter(action => !action.admin || isAdmin);
  const normalized = normalize(query);
  if (!normalized) return ['ticket-search', 'automation', 'monitor', 'knowledge', 'rag', 'ticket-create']
    .map(id => allowed.find(action => action.id === id)).filter((action): action is WorkspaceAction => !!action);
  return allowed.map((action, order) => ({ action, order, score: matchScore(action, normalized) }))
    .filter(result => result.score > 0)
    .sort((a, b) => b.score - a.score || a.order - b.order)
    .map(result => result.action);
}

export function actionDestination(action: WorkspaceAction, query = ''): WorkspaceActionDestination {
  // Preserve the user's text. Draft length and validation belong to the receiving composer.
  if (action.id === 'rag') {
    const featureNames = ['ai助手', '助手', '智能问答', '智能排障', '问答', 'ai', '提问', '排查问题'];
    const draft = featureNames.includes(normalize(query)) ? '' : query;
    return { path: action.path, query: { new: '1', ...(draft ? { draft } : {}) } };
  }
  if (action.id === 'ticket-search' && query) {
    const featureNames = ['事件处置', '事件', '事件队列', '查询事件', '搜索事件', '工单', '查询工单', '工单中心', '搜索工单', '查工单', '查找工单', '我的工单', 'ticket'];
    if (featureNames.includes(normalize(query))) return { path: action.path };
    const keyword = query.replace(/^\s*(?:查询|搜索|查找|查)(?:一下)?(?:工单|事件)[\s:：]*/u, '');
    return { path: action.path, query: { keyword } };
  }
  return { path: action.path, ...(action.query ? { query: { ...action.query } } : {}) };
}
