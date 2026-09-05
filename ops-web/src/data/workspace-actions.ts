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
  { id: 'ticket-create', label: '创建工单', description: '打开新建表单，记录问题并分配处理人', keywords: ['新建工单', '创建一个工单', '开工单', '提单', '报修', '提交故障'], icon: 'TicketCheck', admin: false, path: '/tickets', query: { create: '1' } },
  { id: 'rag', label: '智能问答', description: '把问题带入新会话草稿，确认后再发送', keywords: ['智能排障', '排查', '分析故障', '问答', '提问', 'AI', 'Redis', 'RabbitMQ', 'MySQL', '连接超时', '消息堆积', '故障原因', '怎么处理', '如何处理'], icon: 'MessageSquareText', admin: false, path: '/rag/chat' },
  { id: 'ticket-search', label: '查询工单', description: '进入工单中心，按关键词查看处理进度', keywords: ['工单', '工单中心', '搜索工单', '查工单', '处理进度', '待处理', '我的工单', 'ticket'], icon: 'TicketCheck', admin: false, path: '/tickets' },
  { id: 'oncall', label: '值班排班', description: '查看当班人员、轮值计划和班次日历', keywords: ['值班', '排班', '谁在值班', '当班', '班次', '轮值', 'oncall'], icon: 'CalendarClock', admin: false, path: '/itsm/oncall' },
  { id: 'sla', label: 'SLA 看板', description: '查看响应与解决时限、超时风险及规则', keywords: ['SLA', '服务等级', '响应时限', '解决时限', '超时工单', '超时风险'], icon: 'TimerReset', admin: false, path: '/itsm/sla' },
  { id: 'cmdb', label: '服务目录与依赖拓扑', description: '查看服务信息、依赖关系和健康状态', keywords: ['服务目录', '服务拓扑', '拓扑', '依赖', 'CMDB', '服务关系'], icon: 'Network', admin: false, path: '/itsm/cmdb' },
  { id: 'knowledge', label: '知识库', description: '检索文档、查看切片和维护运维知识', keywords: ['知识', '文档', '手册', '知识检索', '运维规范', 'knowledge'], icon: 'BookOpen', admin: false, path: '/knowledge' },
  { id: 'knowledge-upload', label: '上传知识文档', description: '打开知识库上传入口，添加新的文档', keywords: ['上传知识', '上传文档', '导入文档', '添加文档'], icon: 'BookOpen', admin: false, path: '/knowledge', query: { upload: '1' } },
  { id: 'monitor', label: '系统监控', description: '查看服务、中间件和监控组件的实时状态', keywords: ['监控', '运行状态', '健康检查', '中间件', 'Prometheus', 'Grafana', 'Sentinel', 'Nacos'], icon: 'Activity', admin: false, path: '/system/monitor' },
  { id: 'alerts', label: '活动告警', description: '查看告警事件及其关联工单', keywords: ['告警', '报警', '异常事件', 'alert'], icon: 'Siren', admin: true, path: '/itsm/alerts' },
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
  if (!normalized) return allowed.slice(0, 6);
  return allowed.map((action, order) => ({ action, order, score: matchScore(action, normalized) }))
    .filter(result => result.score > 0)
    .sort((a, b) => b.score - a.score || a.order - b.order)
    .map(result => result.action);
}

export function actionDestination(action: WorkspaceAction, query = ''): WorkspaceActionDestination {
  // Preserve the user's text. Draft length and validation belong to the receiving composer.
  if (action.id === 'rag') {
    const featureNames = ['智能问答', '智能排障', '问答', 'ai', '提问', '排查问题'];
    const draft = featureNames.includes(normalize(query)) ? '' : query;
    return { path: action.path, query: { new: '1', ...(draft ? { draft } : {}) } };
  }
  if (action.id === 'ticket-search' && query) {
    const featureNames = ['工单', '查询工单', '工单中心', '搜索工单', '查工单', '查找工单', '我的工单', 'ticket'];
    if (featureNames.includes(normalize(query))) return { path: action.path };
    const keyword = query.replace(/^\s*(?:查询|搜索|查找|查)(?:一下)?工单[\s:：]*/u, '');
    return { path: action.path, query: { keyword } };
  }
  return { path: action.path, ...(action.query ? { query: { ...action.query } } : {}) };
}
