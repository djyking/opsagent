import type { Approval, Graph, ModelFailure, PendingApproval, RunDetail, RunEvent } from '@/api/automation';

export const automationToolLabels: Record<string, string> = {
  ticket_get: '读取工单', ticket_history: '读取处理历史', demo_target_inspect: '观测业务目标',
  demo_config_restore: '恢复订单连接配置', demo_flow_restore: '恢复订单限流规则',
  knowledge_search: '检索知识证据', ticket_add_analysis: '记录诊断分析', ticket_resolve: '验证并解决工单',
  official_docs_search: '检索官方文档',
  recent_changes: '核对近期变更', demo_queue_restore: '恢复通知队列消费者',
};
export const automationEventLabels: Record<string, string> = {
  RUN_CREATED: '运行已创建', NODE_COMPLETED: '节点完成', MODEL_INTENT: '请求模型决策',
  MODEL_OBSERVATION: '模型返回工具计划', TOOL_INTENT: '登记工具调用', TOOL_OBSERVATION: '取得工具证据',
  TOOL_REQUEST_INTENT: '保存具体执行请求', TICKET_TRANSITION_COMMITTED: '确认工单状态推进',
  RECOVERY_WAITING: '等待业务与告警恢复', RECOVERY_WAIT_DEFERRED: '等待下一次恢复观测',
  APPROVAL_REQUESTED: '请求动作审批', APPROVAL_GRANTED: '审批通过', APPROVAL_REJECTED: '审批拒绝',
  RUN_COMPLETED: '工作流结束', RUN_NEEDS_ATTENTION: '需要人工处理', CANCEL_REQUESTED: '请求取消',
  RUN_CANCELLED: '运行已取消', PAUSE_REQUESTED: '请求暂停', RESUME_REQUESTED: '请求继续',
  RUN_PAUSED: '运行已暂停', RUN_EXPIRED: '运行期限已到', RUN_REJECTED: '审批未获准',
  RUN_BUDGET_EXCEEDED: '运行预算已达上限',
};

export function approvalKey(approval: Approval) { return `${approval.id}:${approval.revision}:${approval.args_hash}`; }
export function runModelFailure(run?: RunDetail): ModelFailure | undefined {
  if (run?.state.modelFailure) return run.state.modelFailure;
  if (run?.status !== 'NEEDS_ATTENTION' || run.state.message !== 'MODEL_OUTCOME_UNKNOWN') return undefined;
  return { code: 'MODEL_OUTCOME_UNKNOWN',
    reason: '本次模型调用未取得可确认的结果，诊断步骤已停止。请核对模型服务与运行轨迹，再启动新的运行；当前记录不能直接继续。',
    canResume: false, retryable: false, recoveryAction: 'NEW_RUN' };
}
export function approvalActionable(approval: PendingApproval, userId: number | undefined, admin: boolean, now: number) {
  return (admin || approval.ownerId === userId) && approval.status === 'PENDING' && !approval.pauseRequested
    && ['WAITING_APPROVAL', 'WAITING_INPUT'].includes(approval.runStatus)
    && new Date(approval.expires_at).getTime() > now && new Date(approval.runDeadline).getTime() > now;
}
export function approvalForRun(approval: Approval, run: RunDetail): PendingApproval {
  return { ...approval, runStatus: run.status, ownerId: run.ownerId, ticketId: run.state.ticketId,
    incidentId: run.state.incidentId, nodeId: run.nodeId,
    nodeLabel: run.snapshot.graph.nodes.find(node => node.id === run.nodeId)?.label || run.nodeId,
    runDeadline: run.state.deadline, pauseRequested: run.pauseRequested === true };
}
export function approvalDescription(approval: PendingApproval) {
  const name = String(approval.payload.name || '');
  const input = name === 'HUMAN_INPUT';
  const descriptions: Record<string, { title: string; change: string; scope: string }> = {
    demo_config_restore: { title: '恢复订单服务连接配置', change: '将隔离订单服务的 Redis 连接端口恢复为 6379，随后重新验证订单请求和告警。',
      scope: '修改隔离订单目标的连接配置，会影响该目标后续真实请求。授权仅适用于本次事件和已观测的配置版本。' },
    demo_flow_restore: { title: '恢复订单服务限流规则', change: '将隔离订单接口的 Sentinel QPS 阈值恢复为 5，随后验证业务请求和限流指标。',
      scope: '修改隔离订单目标的限流阈值，会改变该目标请求的放行情况。授权仅适用于本次事件和已观测的规则版本。' },
    demo_queue_restore: { title: '恢复通知队列消费者', change: '重新订阅隔离通知队列并处理积压消息，随后核对真实消费回执、队列排空和告警恢复。',
      scope: '只恢复本次事件绑定的隔离通知消费者；平台自身的消息队列不在动作范围内。审批绑定已观测版本和当前事件。' },
    APPROVAL: { title: '确认继续工作流', change: '批准后继续当前工作流的后续节点。', scope: '本次授权仅通过当前审批节点，后续需审批的动作仍会单独请求。' },
    HUMAN_INPUT: { title: '补充处理信息', change: '将您填写的信息保存为当前节点结果，供后续工作流使用。', scope: '输入会记录在本次运行的轨迹中，请只填写当前处置所需的信息。' },
  };
  const description = descriptions[name] || { title: automationToolLabels[name] || '确认受控动作',
    change: '执行下方技术详情中登记的当前动作；请核对参数后决定。', scope: '动作影响范围需根据当前工具和参数核对，未提供的效果不能视为保证。' };
  return { ...description, input, prompt: typeof approval.payload.prompt === 'string' ? approval.payload.prompt : '',
    target: name === 'demo_queue_restore' ? '隔离通知服务' : name.startsWith('demo_') ? '隔离订单服务' : approval.nodeLabel || '当前工作流节点' };
}

type EventPhase = { key: string; label: string; events: RunEvent[] };
export type AutomationEventGroup = { id: string; label: string; nodeId: string; events: RunEvent[]; phases: EventPhase[] };
function eventPhase(type: string): [string, string] {
  if (type.startsWith('APPROVAL_') || type === 'HUMAN_INTENT') return ['approval', '人工审批与补充'];
  if (type.startsWith('MODEL_')) return ['model', '模型决策'];
  if (type.startsWith('RECOVERY_') || type === 'TICKET_TRANSITION_COMMITTED') return ['recovery', '恢复验证与工单流转'];
  if (type.startsWith('TOOL_')) return ['tools', '工具执行与证据'];
  return ['state', '节点与运行状态'];
}
export function groupAutomationEvents(events: RunEvent[], graph?: Graph): AutomationEventGroup[] {
  const groups = new Map<string, AutomationEventGroup>();
  for (const event of [...events].sort((left, right) => left.id - right.id)) {
    const nodeId = event.nodeId || '';
    const id = nodeId || '__run__';
    let group = groups.get(id);
    if (!group) {
      group = { id, nodeId, label: graph?.nodes.find(node => node.id === nodeId)?.label || nodeId || '运行控制记录', events: [], phases: [] };
      groups.set(id, group);
    }
    group.events.push(event);
    const [key, label] = eventPhase(event.type);
    let phase = group.phases.find(item => item.key === key);
    if (!phase) { phase = { key, label, events: [] }; group.phases.push(phase); }
    phase.events.push(event);
  }
  return [...groups.values()];
}
