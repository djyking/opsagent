import type { Approval, Graph, ModelFailure, PendingApproval, RunDetail, RunEvent } from '@/api/automation';

export const automationToolLabels: Record<string, string> = {
  ticket_get: '读取工单', ticket_history: '读取处理历史', demo_target_inspect: '观测业务目标',
  demo_config_restore: '恢复订单连接配置', demo_flow_restore: '恢复订单限流规则',
  knowledge_search: '检索知识证据', ticket_add_analysis: '记录诊断分析', ticket_resolve: '验证并解决工单',
  official_docs_search: '检索官方文档',
  recent_changes: '核对近期变更', demo_queue_restore: '恢复通知队列消费者',
  config_change_apply: '应用已审批配置提案',
};

/** Only confirmed write receipts count as applied; approval or run completion is insufficient. */
export function runWriteSummary(run: RunDetail | undefined, events: RunEvent[]) {
  const writeTools = new Set(['demo_config_restore', 'demo_flow_restore', 'demo_queue_restore', 'config_change_apply']);
  const records = new Map<string, Record<string, unknown> | undefined>();
  const object = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
  for (const event of events) {
    const payload = object(event.payload);
    const call = object(payload.call || payload);
    if (!writeTools.has(String(call.name))) continue;
    const key = String(call.graphTool === true ? event.nodeId : call.id || event.nodeId);
    if (event.type === 'TOOL_OBSERVATION') records.set(key, object(payload.result));
    else if (['TOOL_INTENT', 'TOOL_REQUEST_INTENT'].includes(event.type) && !records.has(key)) records.set(key, undefined);
    const observed = run?.state.observations?.[key];
    if (observed) records.set(key, object(observed));
  }
  for (const node of run?.snapshot.graph.nodes || []) if (writeTools.has(String(node.config?.tool))) {
    const output = run?.state.outputs?.[node.id];
    if (output) records.set(node.id, object(output));
  }
  let applied = 0;
  for (const result of records.values()) if (result && (object(result.operation).status === 'APPLIED'
    || result.actionAccepted === true && result.configurationStatus === 'APPLIED')) applied++;
  const uncertain = records.size - applied;
  return { applied, uncertain, text: applied ? `已确认 ${applied} 项变更${uncertain ? ` · ${uncertain} 项结果待核对` : ''}`
    : uncertain ? `${uncertain} 项写入结果待核对` : '未记录已确认的配置或流控变更' };
}
export const automationEventLabels: Record<string, string> = {
  RUN_CREATED: '运行已创建', NODE_COMPLETED: '节点完成', MODEL_INTENT: '请求模型决策',
  MODEL_OBSERVATION: '模型返回工具计划', TOOL_INTENT: '登记工具调用', TOOL_OBSERVATION: '取得工具证据',
  TOOL_REQUEST_INTENT: '保存具体执行请求', TICKET_TRANSITION_COMMITTED: '确认工单状态推进',
  RECOVERY_WAITING: '等待业务与告警恢复', RECOVERY_WAIT_DEFERRED: '等待下一次恢复观测',
  APPROVAL_REQUESTED: '请求动作审批', APPROVAL_GRANTED: '审批通过', APPROVAL_REJECTED: '审批拒绝',
  RUN_COMPLETED: '工作流结束', RUN_NEEDS_ATTENTION: '需要人工处理', CANCEL_REQUESTED: '请求取消',
  RUN_CANCELLED: '运行已取消', PAUSE_REQUESTED: '请求暂停', RESUME_REQUESTED: '请求继续',
  RUN_PAUSED: '运行已暂停', RUN_EXPIRED: '运行期限已到', RUN_REJECTED: '审批未获准',
  RUN_BUDGET_EXCEEDED: '运行预算限制已触发',
  HUMAN_INTENT: '等待人工审批或补充信息', TOOL_AI_BUDGET_RESERVED: '预留知识检索 AI 预算',
  TOOL_AI_BUDGET_SETTLED: '结算知识检索 AI 用量', MODEL_BUDGET_RESERVED: '预留模型调用预算',
  MODEL_BUDGET_SETTLED: '结算模型调用用量', MODEL_CONTEXT_COMPACTED: '整理模型上下文',
};

export function automationEventLabel(type: string, run?: { state?: { tokenBudgetMode?: string; tokenBudget?: number } }) {
  if (type === 'RUN_BUDGET_EXCEEDED' && runHasUnlimitedTokenBudget(run)) return '执行次数限制已触发';
  return automationEventLabels[type] || (type.includes('BUDGET') ? 'AI 预算记录' : '运行记录');
}

/** A ticket being PROCESSING says nothing about whether a person or AI is executing it. */
export function automationRunPresentation(run?: { status: string; pauseRequested?: boolean; state?: { tokenBudgetMode?: string; tokenBudget?: number } }, recovery = false) {
  const status = run?.status || '';
  if (status === 'PAUSED' || run?.pauseRequested && ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_INPUT'].includes(status)) return { code: 'PAUSED', title: status === 'PAUSED' ? '流程已暂停' : '正在暂停流程',
    description: status === 'PAUSED' ? '自动流程已暂停，可在运行中心核对后继续。' : '暂停请求已记录，等待当前步骤返回结果。', action: '查看运行状态' };
  if (status === 'WAITING_APPROVAL') return { code: 'APPROVAL', title: '等待人工审批',
    description: '当前动作需要审批，通过后才会继续执行。', action: '查看并审批' };
  if (status === 'WAITING_INPUT') return { code: 'INPUT', title: '等待人工补充',
    description: '当前步骤需要补充信息，提交后继续流程。', action: '补充处理信息' };
  if (['NEEDS_ATTENTION', 'BUDGET_EXCEEDED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(status)) return { code: 'HANDOFF', title: '需要人工接管',
    description: status === 'BUDGET_EXCEEDED' ? runHasUnlimitedTokenBudget(run)
      ? '本次运行触发执行次数或单次调用等限制，已停止推进；流程累计 Token 暂不设上限。'
      : '自动流程因预算限制停止，已有证据和执行结果已保留。'
      : status === 'REJECTED' ? '审批已拒绝，自动流程停止，需人工核对后续处置。'
      : status === 'EXPIRED' ? '运行期限已到，需人工核对现场并继续处置。'
      : status === 'CANCELLED' ? '运行已取消，已发出的动作仍需核对结果。' : '自动流程已停止推进，请核对原因并继续处置。', action: '人工接管并继续处置' };
  if (recovery) return { code: 'VERIFYING', title: '恢复验证', description: '正在核对恢复证据，技术确认与业务确认仍需在事件中完成。', action: '查看恢复验证' };
  if (['QUEUED', 'RUNNING'].includes(status)) return { code: 'AI', title: 'AI 处理中',
    description: status === 'QUEUED' ? '流程已排队，等待执行下一步。' : '正在执行当前步骤，进度和证据会持续更新。', action: '查看执行进度' };
  if (status === 'COMPLETED') return { code: 'COMPLETED', title: '流程已结束',
    description: '请在事件中核对恢复结果，完成确认后再关闭事件。', action: '核对恢复结果' };
  return { code: run ? 'UNKNOWN' : 'NONE', title: run ? '运行状态待核对' : '事件处置',
    description: run ? '尚未识别当前运行状态，请查看执行记录。' : '核对现场并完成处置，提交结果后进入恢复验证。', action: '查看处置过程' };
}

export function automationMessage(raw?: string) {
  const message = raw?.trim() || '';
  if (!message) return '';
  if (/MODEL_HTTP_40[13]|MODEL.*(?:NOT_CONFIGURED|UNVERIFIED|SNAPSHOT|PROTOCOL)|INVALID_NATIVE_TOOL_PAYLOAD/i.test(message))
    return '模型配置或返回格式未通过检查，请核对技术详情后重新发起运行。';
  if (/MODEL_HTTP_429|RATE_LIMIT/i.test(message)) return '模型服务暂时限制请求，本次调用未完成，请核对后续处理方式。';
  if (/MODEL_(?:OUTCOME_UNKNOWN|TRANSPORT|HTTP_5)|TIMEOUT|TIMED_OUT|ECONN|SocketException|Read timed out/i.test(message))
    return '模型或工具调用未取得可确认的结果，请核对连接与执行记录。';
  if (/EVIDENCE.*(?:INVALID|UNKNOWN|MISSING)|(?:UNKNOWN|INVALID).*EVIDENCE/i.test(message))
    return '诊断引用的证据未能核对，当前步骤已停止，请查看证据详情。';
  if (/BUDGET|TOKEN_LIMIT|预算|剩余额度/.test(message)) return '本次运行的可用预算不足，已有证据和执行结果已保留。';
  if (/[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|Exception|\bat [a-z]+\.[a-z]/.test(message) || !/[\u4e00-\u9fff]/.test(message))
    return '当前步骤返回技术信息，请展开详情核对原因与执行结果。';
  return message.length > 160 ? '当前步骤的详细说明已记录，请展开查看。' : message;
}

export function runHasUnlimitedTokenBudget(run?: { state?: { tokenBudgetMode?: string; tokenBudget?: number } }) {
  return run?.state?.tokenBudgetMode === 'UNLIMITED' && run.state.tokenBudget === 0;
}

export function runBudgetSummary(run?: RunDetail) {
  const used = run?.state.tokens;
  const limit = run?.state.tokenBudget;
  const amount = typeof used === 'number' && Number.isFinite(used) && used >= 0 ? used.toLocaleString() : '未取得';
  if (runHasUnlimitedTokenBudget(run)) return `已累计 ${amount} Token · 暂不设流程上限`;
  const ceiling = typeof limit === 'number' && Number.isFinite(limit) && limit > 0 ? limit.toLocaleString() : '上限未记录';
  return `已计入 ${amount} / ${ceiling} Token`;
}

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
