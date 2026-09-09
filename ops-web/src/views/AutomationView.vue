<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Activity, ArrowRight, Bot, Check, ChevronDown, ChevronLeft, ChevronRight, Clock3, Code2, Database, FileCheck2,
  FlaskConical, GitBranch, Layers, Pause, Play, RefreshCw, Square, Wrench, Zap } from '@lucide/vue';
import PageHeader from '@/components/PageHeader.vue';
import InlineError from '@/components/InlineError.vue';
import EmptyState from '@/components/EmptyState.vue';
import ApprovalCard from '@/components/automation/ApprovalCard.vue';
import InspectionRuns from '@/components/automation/InspectionRuns.vue';
import AutomationUsage from '@/components/automation/AutomationUsage.vue';
import PublicCases from '@/components/automation/PublicCases.vue';
import DrillStartConfirm from '@/components/automation/DrillStartConfirm.vue';
import BaseModal from '@/components/BaseModal.vue';
import EventManualRecovery from '@/components/events/EventManualRecovery.vue';
import { ticketApi } from '@/api/modules';
import type { Ticket } from '@/types/api';
import { useAuthStore } from '@/stores/auth';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import { approvalActionable, approvalForRun, approvalKey, groupAutomationEvents, runBudgetSummary, runHasUnlimitedTokenBudget, runModelFailure, runWriteSummary,
  automationEventLabel, automationMessage, automationRunPresentation, automationToolLabels as toolLabels } from '@/utils/automation-presentation';
import { automationApi as api, type PendingApproval, type Definition, type Graph, type Incident, type Model,
  type RunDetail, type RunEvent, type RunRow, type Target } from '@/api/automation';
import '@/styles/pages/automation.css';

const auth = useAuthStore();
const approvalInbox = useApprovalInboxStore();
const route = useRoute();
const router = useRouter();
const publicCases = ref<InstanceType<typeof PublicCases>>();
const tab = ref(auth.isDemo && !route.query.run && !route.query.ticketId && !route.query.incidentId ? 'cases' : 'runs');
const tabs = [{ id: 'cases', label: '公共案例', icon: FileCheck2 }, { id: 'runs', label: '运行与审批', icon: Activity }, { id: 'experience', label: '我的演练', icon: FlaskConical },
  { id: 'workflows', label: '工作流与版本', icon: GitBranch }, { id: 'inspection', label: '巡检计划与执行', icon: Check },
  { id: 'tools', label: '模型与工具', icon: Wrench }];
const targetCode = ref(route.query.target === 'ops-demo-notification-service' ? 'ops-demo-notification-service' : 'ops-demo-order-service');
const notificationTarget = computed(() => targetCode.value === 'ops-demo-notification-service');
const error = ref('');
const targetError = ref('');
const scenariosError = ref('');
const experienceErrors = computed(() => [...new Set([targetError.value, scenariosError.value].filter(Boolean))]);
const notice = ref('');
const busy = ref('');
const target = ref<Target>();
const incidents = ref<Incident[]>([]);
const models = ref<Model[]>([]);
const runs = ref<RunRow[]>([]);
const total = ref(0);
const page = ref(1);
const detail = ref<RunDetail>();
const runTicketContext = ref<Ticket>();
const runListOpen = ref(!route.query.run);
const tracePanel = ref<HTMLDetailsElement>();
const events = ref<RunEvent[]>([]);
const eventsComplete = ref(true);
const definitions = ref<Definition[]>([]);
const definition = ref<Definition>();
const graphText = ref('');
const definitionId = ref('isolated-recovery');
const provider = ref('');
const runDefinitionId = ref('isolated-recovery');
const ticketId = ref(Number(route.query.ticketId) || 0);
const ticketFilter = ref(String(route.query.ticketId || ''));
const appliedTicketFilter = ref(Number(route.query.ticketId) || undefined);
const trackedIncidentId = ref(String(route.query.incidentId || ''));
const trackedRuns = ref<RunRow[]>([]);
const trackedError = ref('');
const startScenario = ref('');
const startError = ref('');
const trackingOwner = ref<number | undefined>(auth.user?.userId);
const takeoverOpen = ref(false);
const takeoverReason = ref('');
const takeoverPreview = ref<Awaited<ReturnType<typeof api.takeoverPreview>>>();
const takeoverAttempt = ref<{ runId: string; input: Parameters<typeof api.takeover>[1] }>();
const canSubmitTakeover = computed(() => {
  if (!auth.isAdmin || !detail.value || busy.value || !takeoverReason.value.trim() || takeoverReason.value.trim().length > 500) return false;
  if (takeoverAttempt.value) return takeoverAttempt.value.runId === detail.value.id;
  const preview = takeoverPreview.value;
  return detail.value.authorization?.canTakeover === true && preview?.canTakeover === true
    && preview.sourceRunId === detail.value.id && preview.incidentId === detail.value.state.incidentId
    && !!preview.incidentId && !!preview.expectedRevision && !!preview.targetCode;
});
const toolCatalog = ref<Awaited<ReturnType<typeof api.tools>>>();
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;
let disposed = false;
let polling = false;
let mountedReady = false;
let initialScope = '';
let nextPollAt = 0;
let refreshPending: { key: string; promise: Promise<void> } | undefined;
let catalogEpoch = 0;
const catalogLoadedAt = { models: 0, definitions: 0, tools: 0 };
const catalogPending = new Map<string, Promise<void>>();
let selectedEpoch = 0;
let refreshEpoch = 0;
let trackedEpoch = 0;
let terminalReadAt = 0;
let createAttempt: { fingerprint: string; requestId: string } | undefined;
const terminal = new Set(['COMPLETED', 'CANCELLED', 'EXPIRED', 'REJECTED', 'NEEDS_ATTENTION', 'BUDGET_EXCEEDED']);
const needsHandoff = computed(() => ['NEEDS_ATTENTION', 'BUDGET_EXCEEDED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(detail.value?.status || '') && !verifiedCompletion.value);
const ownRun = computed(() => detail.value?.authorization?.canOperate ?? (auth.isAdmin || detail.value?.ownerId === auth.user?.userId));
const modelFailure = computed(() => runModelFailure(detail.value));
const rawRunMessage = computed(() => modelFailure.value?.reason || detail.value?.state.message || '');
const runMessage = computed(() => runHasUnlimitedTokenBudget(detail.value) && detail.value?.status === 'BUDGET_EXCEEDED'
  ? automationRunPresentation(detail.value).description : automationMessage(rawRunMessage.value));
const writeSummary = computed(() => runWriteSummary(detail.value, events.value));
const modelApprovalHint = computed(() => modelFailure.value && detail.value?.approvals.length === 0
  && !events.value.some(event => event.type.startsWith('APPROVAL_'))
  ? '本次运行尚未产生审批请求。模型需要先返回可执行的处置计划，才会进入相应的动作审批。' : '');
const canPause = computed(() => ownRun.value && !detail.value?.pauseRequested
  && ['QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'WAITING_INPUT'].includes(detail.value?.status || ''));
const canResume = computed(() => ownRun.value && detail.value
  && modelFailure.value?.canResume !== false
  && new Date(detail.value.state.deadline).getTime() > now.value
  && (['PAUSED', 'NEEDS_ATTENTION'].includes(detail.value.status)
    || (detail.value.pauseRequested && ['WAITING_APPROVAL', 'WAITING_INPUT'].includes(detail.value.status))));
const canOperate = computed(() => auth.isAdmin || auth.isOps || auth.isDemo);
const activeIncident = computed(() => incidents.value.find(item => item.incidentId === target.value?.incidentId));
const canStart = computed(() => canOperate.value && target.value?.configured === true && target.value.canStart
  && target.value.status === 'BASELINE' && target.value.business.httpStatus === 200);
const canRestore = computed(() => canOperate.value && !!target.value?.incidentId && !!target.value.expectedRevision
  && (!trackedIncidentId.value || target.value.incidentId === trackedIncidentId.value)
  && target.value.status === 'FAULT_ACTIVE'
  && (auth.isAdmin || auth.isOps || target.value.ownedByCurrentActor === true
    || activeIncident.value?.ownerId === auth.user?.userId)
  && ['FAULT_ACTIVE', 'INJECTING', 'INJECTION_UNCONFIRMED'].includes(activeIncident.value?.status || ''));
const trackedIncident = computed(() => incidents.value.find(item => item.incidentId === trackedIncidentId.value));
const identityChanged = computed(() => trackingOwner.value != null && trackingOwner.value !== auth.user?.userId);
const latestTrackedRun = computed(() => trackedRuns.value[0]);
const activePersonalRun = computed(() => runs.value.find(run => run.owner_id === auth.user?.userId && !terminal.has(run.status)));
const selectedModel = computed(() => models.value.find(item => item.provider === provider.value));
const canCreate = computed(() => canOperate.value && Number.isSafeInteger(ticketId.value) && ticketId.value > 0
  && selectedModel.value?.configured && selectedModel.value.toolCalling
  && (!auth.isDemo || runDefinitionId.value === 'isolated-recovery')
  && definitions.value.some(item => item.id === runDefinitionId.value && item.published_version > 0));
const startHint = computed(() => {
  if (!target.value) return targetError.value || '正在读取演练目标状态。';
  if (!canOperate.value) return '当前账号可以查看演练，发起操作需要运维、管理员或访客身份。';
  if (!target.value.configured) return '演练目标尚未连接，请检查目标服务配置。';
  if (target.value.nextAvailableAt && new Date(target.value.nextAvailableAt).getTime() > now.value)
    return `目标恢复后的冷却期，${date(target.value.nextAvailableAt)} 后可再次发起。`;
  if (!canStart.value) return '目标正在演练或等待健康基线恢复，恢复并完成冷却后可再次发起。';
  return '演练会改变隔离目标的真实配置；监控发现持续故障后创建工单并触发 Agent。';
});
const pendingApprovals = computed(() => detail.value?.approvals.filter(item => item.status === 'PENDING')
  .map(item => approvalForRun(item, detail.value!)) || []);
const eventGroups = computed(() => groupAutomationEvents(events.value, detail.value?.snapshot.graph));
const graph = computed<Graph | undefined>(() => tab.value === 'workflows' ? definition.value?.graph : detail.value?.snapshot.graph);
const linearGraph = computed(() => !!graph.value && graph.value.edges.length === graph.value.nodes.length - 1
  && graph.value.nodes.slice(0, -1).every((node, index) => graph.value!.edges.some(edge =>
    edge.from === node.id && edge.to === graph.value!.nodes[index + 1]?.id && !edge.when)));
const statusNames: Record<string, string> = { QUEUED: '准备执行', RUNNING: '执行中', WAITING_APPROVAL: '等待审批', WAITING_INPUT: '等待补充',
  COMPLETED: '运行结束', PAUSED: '已暂停', CANCELLED: '已取消', EXPIRED: '已到期', REJECTED: '审批拒绝', NEEDS_ATTENTION: '需要处理', BUDGET_EXCEEDED: '预算限制已触发',
  ACTIVE: '故障进行中', FAULT_ACTIVE: '故障进行中', INJECTING: '注入故障中', INJECTION_UNCONFIRMED: '注入结果待确认',
  BASELINE: '健康基线', RECOVERED: '已恢复', EXPIRED_RECOVERED: '到期保护已恢复', EXPIRED_NOT_APPLIED: '到期未生效',
  VERIFIED: '已验证', UNKNOWN: '尚未验证', UNSUPPORTED: '不支持工具', UNAVAILABLE: '暂不可用' };
const latestRecoveryVerified = computed(() => detail.value?.state.ticketResolved === true
  && detail.value.state.recoveryVerification?.resolved === true
  && ['RESOLVED', 'CLOSED'].includes(detail.value.state.recoveryVerification.toStatus || ''));
const verifiedCompletion = computed(() => latestRecoveryVerified.value && detail.value?.status === 'COMPLETED');
const verificationStatus = computed(() => {
  if (verifiedCompletion.value) return '已验证解决';
  if (!detail.value?.state.ticketResolved) return '尚未验证解决';
  return latestRecoveryVerified.value && !terminal.has(detail.value.status)
    ? '恢复已验证 · 流程未结束' : '已记录解决 · 待复核';
});
const recoveryOutcome = computed(() => {
  const run = detail.value;
  if (!run || !terminal.has(run.status) || verifiedCompletion.value) return '';
  if (!run.state.ticketResolved) {
    const progress = run.status === 'NEEDS_ATTENTION' ? '本次运行已停止自动推进，等待人工处理'
      : run.status === 'COMPLETED' ? '本次运行已结束' : `本次运行状态为“${label(run.status)}”`;
    return `${progress}，尚无工单验证解决记录。业务是否恢复，请核对真实探针和恢复来源。`;
  }
  if (latestRecoveryVerified.value)
    return `工单已有验证解决记录，但本次运行当前为“${label(run.status)}”，尚未完成全部流程。`;
  const reason = run.state.recoveryVerification?.reason;
  return `工单曾记录为解决状态，但本次运行的最新恢复验证尚未通过${reason ? `：${reason}` : ''}。请核对最新探针、告警和工单状态。`;
});
const recoveryProgress = computed(() => {
  const run = detail.value;
  if (!run || run.pauseRequested || terminal.has(run.status)) return '';
  const signals = ['RECOVERY_WAITING', 'RECOVERY_WAIT_DEFERRED', 'TOOL_REQUEST_INTENT', 'TOOL_OBSERVATION',
    'TICKET_TRANSITION_COMMITTED', 'NODE_COMPLETED', 'MODEL_INTENT'];
  const latest = [...events.value].reverse().find(event => event.nodeId === run.nodeId && signals.includes(event.type));
  if (latest && ['RECOVERY_WAITING', 'RECOVERY_WAIT_DEFERRED'].includes(latest.type)) {
    const payload = latest.payload as { observation?: { reason?: string }; notBefore?: string } | null;
    const reason = payload?.observation?.reason || run.state.recoveryVerification?.reason || '等待下一次业务探针与告警恢复观测';
    const next = payload?.notBefore && new Date(payload.notBefore).getTime() > now.value
      ? `下一次检查不早于 ${date(payload.notBefore)}。` : '系统将在有界等待时间内再次检查。';
    return `${reason}。${run.state.ticketResolved ? '工单已有解决记录，本次恢复复核尚未通过。' : '工单尚未验证解决。'}${next}`;
  }
  const node = run.snapshot.graph.nodes.find(item => item.id === run.nodeId);
  return node?.type === 'TOOL' && node.config?.tool === 'ticket_resolve'
    ? run.state.ticketResolved
      ? '工单已有解决记录，正在重新核对真实业务探针、告警恢复事件和工单状态；复核通过后才能完成本次运行。'
      : '正在核对真实业务探针、告警恢复事件和工单状态；验证通过后才能确认工单已解决。' : '';
});
function label(value: string) { return statusNames[value] || value; }
function date(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function pretty(value: unknown) { return JSON.stringify(value, null, 2); }
function requestId() { return crypto.randomUUID(); }
function scenarioLabel(code?: string) { return ({ NACOS_REDIS_CONFIG_DRIFT: 'Nacos Redis 配置漂移', SENTINEL_RULE_REGRESSION: 'Sentinel 限流规则回退', RABBITMQ_CONSUMER_PAUSED: 'RabbitMQ 消费暂停与消息积压' } as Record<string, string>)[code || ''] || '健康基线 / 等待选择场景'; }
function source(value?: string) { return ({ AGENT_TOOL: 'Agent 工具', MANUAL: '人工恢复', TTL_GUARD: '到期保护机制', NOT_APPLIED: '故障未生效' } as Record<string, string>)[value || ''] || '尚无恢复动作'; }
function runTicket(run: RunRow) { return run.ticketId ?? run.ticket_id; }
function outgoing(id: string) { return graph.value?.edges.filter(edge => edge.from === id) || []; }
function nodeName(id: string) { return graph.value?.nodes.find(node => node.id === id)?.label || id; }
function branchName(when?: string) { return when === 'true' ? '条件成立' : when === 'false' ? '条件不成立' : when || '继续'; }
function nodeDone(id: string) { return Object.prototype.hasOwnProperty.call(detail.value?.state.outputs || {}, id); }
function approvalAvailable(approval: PendingApproval) {
  return ownRun.value && approvalActionable(approval, auth.user?.userId, auth.isAdmin, now.value) && approvalInbox.isCurrent(approval);
}
function approvalUnavailableReason(approval: PendingApproval) {
  if (!ownRun.value) return detail.value?.authorization?.hint || '当前身份不能执行此运行，请由管理员核对接管条件。';
  if (approval.pauseRequested) return '运行已请求暂停。请先继续运行，再处理此审批。';
  if (new Date(approval.expires_at).getTime() <= now.value || new Date(approval.runDeadline).getTime() <= now.value)
    return '此审批或运行已到期，不能继续提交。';
  return approvalInbox.error || '正在核对当前待审批状态；如已处理或状态已变化，请查看最新运行记录。';
}
function message(cause: unknown) { return cause instanceof Error ? cause.message : '请求失败'; }
function experienceMessage(cause: unknown, resource: string) {
  const reason = message(cause);
  return reason.trim() === 'DEMO_TARGET_NOT_CONFIGURED'
    ? '演练目标尚未配置，请联系管理员配置后重试。' : `${resource}读取失败：${reason}`;
}
async function action(key: string, task: () => Promise<void>) {
  if (busy.value) return;
  busy.value = key; error.value = ''; notice.value = '';
  try { await task(); } catch (cause) { error.value = message(cause); }
  finally { busy.value = ''; }
}
function refresh(scope = tab.value): Promise<void> {
  if (!['cases', 'runs', 'experience'].includes(scope)) return Promise.resolve();
  const key = JSON.stringify([auth.user?.userId, scope, targetCode.value, page.value, appliedTicketFilter.value]);
  if (refreshPending?.key === key) return refreshPending.promise;
  const epoch = ++refreshEpoch;
  const task: Promise<void> = (async () => {
    if (scope !== 'experience') {
      const list = await api.runs(scope === 'cases' ? 1 : page.value, scope === 'cases' ? {} : { ticketId: appliedTicketFilter.value });
      if (disposed || epoch !== refreshEpoch) return;
      runs.value = list.items; total.value = list.total; now.value = Date.now();
      return;
    }
    const results = await Promise.allSettled([api.target(targetCode.value), api.scenarios(targetCode.value)]);
    if (disposed || epoch !== refreshEpoch) return;
    now.value = Date.now();
    target.value = results[0].status === 'fulfilled' ? results[0].value : undefined;
    targetError.value = results[0].status === 'fulfilled' ? '' : experienceMessage(results[0].reason, '演练目标状态');
    incidents.value = results[1].status === 'fulfilled' ? results[1].value.incidents : [];
    scenariosError.value = results[1].status === 'fulfilled' ? '' : experienceMessage(results[1].reason, '演练场景');
    if (!trackedIncidentId.value) trackedIncidentId.value = (auth.isDemo
      ? incidents.value.find(item => item.ownerId === auth.user?.userId)?.incidentId
      : activeIncident.value?.incidentId || incidents.value[0]?.incidentId) || '';
    await refreshTrackedRuns();
  })().catch(cause => { if (!disposed && epoch === refreshEpoch) throw cause; })
    .finally(() => { if (refreshPending?.promise === task) refreshPending = undefined; });
  refreshPending = { key, promise: task };
  return task;
}

async function refreshTrackedRuns() {
  const id = trackedIncidentId.value;
  const epoch = ++trackedEpoch;
  if (!id || identityChanged.value) { trackedRuns.value = []; return; }
  try {
    const result = await api.runs(1, { incidentId: id });
    if (!disposed && epoch === trackedEpoch && trackedIncidentId.value === id) { trackedRuns.value = result.items; trackedError.value = ''; }
  } catch (cause) {
    if (!disposed && epoch === trackedEpoch && trackedIncidentId.value === id) { trackedError.value = message(cause); throw cause; }
  }
}
async function selectRun(id: string) {
  const epoch = ++selectedEpoch;
  try {
    const [run, history] = await Promise.all([api.run(id), readRunEvents(id, 0, epoch)]);
    if (disposed || epoch !== selectedEpoch) return;
    detail.value = run; events.value = history.items; eventsComplete.value = history.complete; tab.value = 'runs';
    runTicketContext.value = undefined;
    void ticketApi.detail(run.state.ticketId).then(ticket => {
      if (!disposed && epoch === selectedEpoch && ticket.id === run.state.ticketId
        && ticket.incidentId === run.state.incidentId) runTicketContext.value = ticket;
    }).catch(() => { /* The event link remains available when its current context cannot be read. */ });
    runListOpen.value = false;
    now.value = Date.now();
    void router.replace({ query: { ...route.query, tab: 'runs', run: id } });
  } catch (cause) {
    if (!disposed && epoch === selectedEpoch) throw cause;
  }
}
async function readRunEvents(id: string, after: number, epoch: number) {
  const items: RunEvent[] = [];
  let cursor = after;
  for (let pageIndex = 0; pageIndex < 20; pageIndex++) {
    const page = await api.events(id, cursor);
    if (disposed || epoch !== selectedEpoch) return { items: [], complete: false };
    const next = page.filter(event => event.id > cursor).sort((a, b) => a.id - b.id);
    items.push(...next);
    if (page.length < 200) return { items, complete: true };
    if (!next.length) return { items, complete: false };
    cursor = next.at(-1)!.id;
  }
  return { items, complete: false };
}
function pollingDelay() {
  if (tab.value === 'experience') return target.value?.status !== 'BASELINE'
    || trackedRuns.value.some(run => !terminal.has(run.status)) ? 4000 : 30_000;
  if (tab.value === 'runs') return detail.value && !terminal.has(detail.value.status)
    || runs.value.some(run => !terminal.has(run.status)) || writeSummary.value.uncertain ? 4000 : 30_000;
  return activePersonalRun.value ? 4000 : 30_000;
}
async function poll(force = false) {
  if (polling || busy.value || disposed || document.hidden || !['cases', 'runs', 'experience'].includes(tab.value)) return;
  if (!force && Date.now() < nextPollAt) return;
  polling = true;
  const scope = tab.value, startedAt = Date.now();
  try {
    const results = await Promise.allSettled([refresh(scope), scope === 'runs' ? refreshSelectedRun() : Promise.resolve()]);
    const failed = results.find(item => item.status === 'rejected');
    if (failed?.status === 'rejected') throw failed.reason;
    if (!disposed && tab.value === scope) nextPollAt = startedAt + pollingDelay();
  } catch (cause) {
    if (!disposed && tab.value === scope) { error.value = message(cause); nextPollAt = Date.now() + 15_000; }
  } finally { polling = false; }
}

async function refreshSelectedRun(force = false) {
  const id = detail.value?.id;
  const epoch = selectedEpoch;
  if (!id) return;
  if (!force && terminal.has(detail.value!.status) && !writeSummary.value.uncertain && eventsComplete.value) {
    // A stopped visitor run can receive an administrator recovery summary later.
    if (Date.now() - terminalReadAt < 15_000) return;
    terminalReadAt = Date.now();
  }
  const [run, more] = await Promise.all([api.run(id), readRunEvents(id, events.value.at(-1)?.id || 0, epoch)]);
  if (!disposed && detail.value?.id === id && epoch === selectedEpoch) {
    detail.value = run;
    const known = new Set(events.value.map(event => event.id));
    events.value.push(...more.items.filter(event => !known.has(event.id)));
    eventsComplete.value = more.complete;
  }
}
async function refreshAll() {
  await Promise.all([refresh(), tab.value === 'runs' ? refreshSelectedRun(true) : Promise.resolve(), loadTabCatalog(tab.value, true)]);
}

async function start(code: string) {
  if (!canStart.value) return;
  startError.value = '';
  await action(code, async () => {
    let incident: Incident;
    try { incident = await api.start(code); }
    catch (cause) { startError.value = `${message(cause)}。请刷新当前现场确认是否已创建，避免重复发起。`; await refresh().catch(() => {}); throw cause; }
    trackingOwner.value = incident.ownerId; startScenario.value = ''; tab.value = 'experience';
    trackedIncidentId.value = incident.incidentId; trackedRuns.value = [];
    incidents.value = [incident, ...incidents.value.filter(item => item.incidentId !== incident.incidentId)];
    await router.replace({ query: { ...route.query, tab: 'experience', target: targetCode.value, incidentId: incident.incidentId, scenario: undefined } });
    notice.value = '演练已创建，下方会持续显示本次演练的工单和 Agent 运行。监控需先确认持续故障。';
    await refresh();
  });
}
async function prepareStart(code: string, codeTarget = targetCode.value) {
  if (busy.value) return;
  if (codeTarget !== targetCode.value) await selectTarget(codeTarget);
  tab.value = 'experience'; startError.value = ''; startScenario.value = code;
  await router.replace({ query: { ...route.query, tab: 'experience', target: codeTarget, scenario: undefined, run: undefined } });
  await refresh('experience');
}
async function takeover() {
  if (!canSubmitTakeover.value) return;
  const id = detail.value!.id;
  if (!takeoverAttempt.value) takeoverAttempt.value = { runId: id, input: {
    requestId: requestId(), reason: takeoverReason.value.trim(),
    incidentId: takeoverPreview.value!.incidentId, expectedRevision: takeoverPreview.value!.expectedRevision,
  } };
  await action('takeover', async () => {
    const run = await api.takeover(id, { ...takeoverAttempt.value!.input });
    takeoverOpen.value = false; takeoverAttempt.value = undefined; takeoverReason.value = '';
    await selectRun(run.id); notice.value = '已创建管理员接管的独立恢复运行。原运行和审批保留，新修复动作仍需重新审批。';
  });
}
async function prepareTakeover() {
  if (!auth.isAdmin || !detail.value || busy.value) return;
  if (takeoverAttempt.value?.runId === detail.value.id) {
    takeoverReason.value = takeoverAttempt.value.input.reason; takeoverOpen.value = true; return;
  }
  if (!detail.value.authorization?.canTakeover) return;
  takeoverOpen.value = true; takeoverPreview.value = undefined; takeoverReason.value = ''; takeoverAttempt.value = undefined;
  await action('takeover-preview', async () => { takeoverPreview.value = await api.takeoverPreview(detail.value!.id); });
}
async function restore() {
  if (!target.value || !canRestore.value) return;
  await action('restore', async () => {
    const result = await api.restore(target.value!);
    notice.value = result.recoveryVerified ? '人工恢复已通过当前业务探针验证，恢复来源记为人工操作。'
      : result.actionAccepted ? '恢复动作已受理，业务恢复仍待探针确认。' : '恢复结果尚未确认，请查看目标状态。';
    await refresh();
  });
}
async function decision(approval: PendingApproval, decision: { approved: boolean; reason: string }) {
  if (!approvalAvailable(approval)) return;
  await action(approval.id, async () => {
    try {
      await approvalInbox.decide(approval, decision.approved, decision.reason);
      notice.value = approvalInbox.notice;
    } finally { await selectRun(approval.run_id); }
  });
}
async function filterRuns() {
  const value = ticketFilter.value.trim();
  const id = value ? Number(value) : undefined;
  if (id !== undefined && (!Number.isSafeInteger(id) || id < 1)) throw new Error('请输入有效的正整数工单 ID。');
  appliedTicketFilter.value = id; page.value = 1;
  await router.replace({ query: { ...route.query, ticketId: id ? String(id) : undefined } });
  await refresh('runs');
}
async function createRun() {
  if (!canCreate.value) return;
  const fingerprint = JSON.stringify([ticketId.value, runDefinitionId.value, provider.value]);
  if (createAttempt?.fingerprint !== fingerprint) createAttempt = { fingerprint, requestId: requestId() };
  const run = await api.create(ticketId.value, runDefinitionId.value, provider.value, createAttempt.requestId);
  await selectRun(run.id);
  ticketFilter.value = String(ticketId.value);
  await filterRuns();
  createAttempt = undefined;
}
async function loadDefinition(id: string) {
  const epoch = catalogEpoch;
  const value = await api.definition(id);
  if (disposed || epoch !== catalogEpoch) return;
  definition.value = value; graphText.value = pretty(value.graph); definitionId.value = id;
}
function readCatalog(kind: 'models' | 'definitions' | 'tools', force = false): Promise<void> {
  if (!force && Date.now() - catalogLoadedAt[kind] < 60_000) return Promise.resolve();
  const cached = catalogPending.get(kind); if (cached) return cached;
  const epoch = catalogEpoch;
  const task: Promise<void> = (async () => {
    if (kind === 'models') {
      const value = await api.models();
      if (disposed || epoch !== catalogEpoch) return;
      models.value = value.models; chooseModel();
    } else if (kind === 'definitions') {
      const value = await api.definitions();
      if (disposed || epoch !== catalogEpoch) return;
      definitions.value = value;
    } else {
      const value = await api.tools();
      if (disposed || epoch !== catalogEpoch) return;
      toolCatalog.value = value;
    }
    catalogLoadedAt[kind] = Date.now();
  })().catch(cause => { if (!disposed && epoch === catalogEpoch) throw cause; })
    .finally(() => { if (catalogPending.get(kind) === task) catalogPending.delete(kind); });
  catalogPending.set(kind, task);
  return task;
}
async function loadTabCatalog(scope: string, force = false) {
  const jobs: Promise<void>[] = [];
  if (['runs', 'tools'].includes(scope)) jobs.push(readCatalog('models', force), readCatalog('tools', force));
  if (['runs', 'workflows'].includes(scope)) jobs.push(readCatalog('definitions', force));
  await Promise.all(jobs);
  if (scope !== 'workflows' || tab.value !== scope || disposed) return;
  const requested = String(route.query.definition || definitionId.value);
  const selected = definitions.value.find(item => item.id === requested)
    || definitions.value.find(item => item.id === 'isolated-recovery') || definitions.value[0];
  if (selected && (!definition.value || definition.value.id !== selected.id
    || force && graphText.value === pretty(definition.value.graph))) await loadDefinition(selected.id);
}
async function refreshCurrentTab() {
  const scope = tab.value;
  try {
    await Promise.all([refresh(scope), loadTabCatalog(scope)]);
    if (!disposed && scope === tab.value) nextPollAt = Date.now() + pollingDelay();
  } catch (cause) { if (!disposed && scope === tab.value) throw cause; }
}
async function initial() {
  await action('load', async () => {
    if (tabs.some(item => item.id === route.query.tab)) tab.value = String(route.query.tab);
    else if (route.query.ticketId) tab.value = 'runs';
    initialScope = tab.value;
    await Promise.all([refreshCurrentTab(), tab.value === 'runs' && route.query.run
      ? selectRun(String(route.query.run)) : Promise.resolve()]);
  });
  const scenario = String(route.query.scenario || '');
  if (['NACOS_REDIS_CONFIG_DRIFT', 'SENTINEL_RULE_REGRESSION', 'RABBITMQ_CONSUMER_PAUSED'].includes(scenario))
    await prepareStart(scenario);
}

function modelStatus(model: Model) {
  const configuration = { MISSING_API_KEY: '缺少 API 密钥', MISSING_MODEL: '未配置模型名称',
    INVALID_ENDPOINT: '模型地址无效', DISABLED: '未启用', CONFIGURED: '' }[model.configurationStatus || 'CONFIGURED'];
  return configuration || (model.configured ? label(model.verificationStatus) : '尚未配置');
}
function chooseModel() {
  if (models.value.some(item => item.provider === provider.value && item.toolCalling && item.configured)) return;
  provider.value = models.value.find(item => item.provider.toUpperCase() === 'DEEPSEEK' && item.toolCalling && item.configured)?.provider
    || models.value.find(item => item.toolCalling && item.configured)?.provider || '';
}
function selectTab(id: string) {
  if (!tabs.some(item => item.id === id)) return;
  selectedEpoch++;
  tab.value = id;
  void router.replace({ query: { ...route.query, tab: id, run: id === 'runs' ? route.query.run : undefined } });
}
async function selectTarget(value: string) {
  if (busy.value || !['ops-demo-order-service', 'ops-demo-notification-service'].includes(value)) return;
  await action('target', async () => {
    refreshEpoch++; trackedEpoch++; targetCode.value = value; target.value = undefined;
    targetError.value = ''; scenariosError.value = '';
    incidents.value = []; trackedIncidentId.value = ''; trackedRuns.value = [];
    await router.replace({ query: { ...route.query, target: value, incidentId: undefined } });
    await refresh('experience');
  });
}
watch(tab, () => {
  refreshEpoch++; trackedEpoch++; refreshPending = undefined; nextPollAt = 0;
  if (mountedReady) void refreshCurrentTab().catch(cause => { if (!disposed) error.value = message(cause); });
});
watch(() => route.query.tab, value => {
  if (tabs.some(item => item.id === value) && tab.value !== value) {
    selectedEpoch++; tab.value = String(value);
  }
});
watch(() => route.query.definition, value => {
  if (tab.value === 'workflows' && typeof value === 'string' && value !== definitionId.value && definitions.value.some(item => item.id === value))
    void action('definition', () => loadDefinition(value));
});
const runPresentation = computed(() => automationRunPresentation(detail.value, !!recoveryProgress.value || verifiedCompletion.value));
const awaitingDecision = computed(() => ['APPROVAL', 'INPUT'].includes(runPresentation.value.code));
function showCurrentApproval() {
  const approval = pendingApprovals.value.find(item => approvalAvailable(item));
  if (approval) approvalInbox.select(approval); else approvalInbox.show();
}
function showTrace() {
  if (!tracePanel.value) return;
  tracePanel.value.open = true;
  tracePanel.value.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
watch(() => [route.query.target, route.query.incidentId], ([code, incident]) => {
  const nextCode = ['ops-demo-order-service', 'ops-demo-notification-service'].includes(String(code)) ? String(code) : targetCode.value;
  const nextIncident = String(incident || '');
  if (nextCode === targetCode.value && nextIncident === trackedIncidentId.value) return;
  refreshEpoch++; trackedEpoch++;
  if (nextCode !== targetCode.value) { target.value = undefined; incidents.value = []; targetError.value = ''; scenariosError.value = ''; }
  targetCode.value = nextCode; trackedIncidentId.value = nextIncident; trackedRuns.value = [];
  if (tab.value === 'experience') void refresh().catch(cause => { if (!disposed) error.value = message(cause); });
});
watch(() => route.query.run, value => {
  const explicitTab = tabs.some(item => item.id === route.query.tab);
  if (value && (!explicitTab || route.query.tab === 'runs') && String(value) !== detail.value?.id)
    void action('run', () => selectRun(String(value)));
});
watch(() => approvalInbox.decisionVersion, () => {
  if (tab.value === 'runs' && detail.value && !busy.value)
    void action('approval-refresh', () => selectRun(detail.value!.id));
});
watch(() => route.query.ticketId, value => {
  const next = Number(value) || undefined;
  if (next !== appliedTicketFilter.value) {
    ticketFilter.value = String(value || ''); ticketId.value = next || 0;
    if (!tabs.some(item => item.id === route.query.tab)) tab.value = 'runs';
    void action('filter', filterRuns);
  }
});
watch(() => auth.user?.userId, () => {
  selectedEpoch++; refreshEpoch++; trackedEpoch++; catalogEpoch++;
  refreshPending = undefined; catalogPending.clear(); nextPollAt = 0;
  catalogLoadedAt.models = 0; catalogLoadedAt.definitions = 0; catalogLoadedAt.tools = 0;
  models.value = []; definitions.value = []; toolCatalog.value = undefined; definition.value = undefined; graphText.value = '';
  detail.value = undefined; runTicketContext.value = undefined; events.value = []; runs.value = []; trackedRuns.value = []; incidents.value = [];
  target.value = undefined; startScenario.value = ''; takeoverOpen.value = false; takeoverAttempt.value = undefined;
  if (auth.user) void refreshCurrentTab().catch(cause => { if (!disposed) error.value = message(cause); });
});
function visibleRefresh() {
  if (!document.hidden) { now.value = Date.now(); nextPollAt = 0; void poll(true); }
}
onMounted(() => {
  void initial().finally(() => { if (disposed) return; mountedReady = true; if (tab.value !== initialScope) void refreshCurrentTab().catch(cause => { if (!disposed) error.value = message(cause); }); });
  timer = setInterval(() => void poll(), 4000);
  document.addEventListener('visibilitychange', visibleRefresh);
});
onBeforeUnmount(() => {
  disposed = true; selectedEpoch++; refreshEpoch++; trackedEpoch++; catalogEpoch++;
  if (timer) clearInterval(timer); document.removeEventListener('visibilitychange', visibleRefresh);
});
</script>

<template>
  <div class="automation-page">
    <PageHeader title="自动化中心" :icon="GitBranch">
      <template #actions><button class="button secondary" :disabled="!!busy || publicCases?.loading" @click="tab === 'cases' ? publicCases?.refresh() : action('refresh', refreshAll)"><RefreshCw :size="16" />刷新</button></template>
      <template #tabs><nav class="automation-tabs" aria-label="自动化工作区"><button v-for="item in tabs" :key="item.id" :class="{ active: tab === item.id }" :aria-current="tab === item.id ? 'page' : undefined" @click="selectTab(item.id)"><component :is="item.icon" :size="16" />{{ item.label }}</button></nav></template>
    </PageHeader>
    <InlineError v-if="error" :message="error" />
    <p v-if="notice" class="automation-notice" role="status"><Check :size="18" />{{ notice }}</p>
    <InspectionRuns v-if="tab === 'inspection'" />
    <section v-if="tab === 'cases' && activePersonalRun" class="panel automation-manual-next"><strong>你有一条正在进行的演练</strong><p>{{ label(activePersonalRun.status) }} · {{ date(activePersonalRun.created_at) }}</p><button class="button primary" :disabled="!!busy" @click="action(activePersonalRun.id, () => selectRun(activePersonalRun!.id))">继续我的演练<ArrowRight :size="16" /></button></section>
    <PublicCases v-if="tab === 'cases'" ref="publicCases" @start="prepareStart" />
    <DrillStartConfirm v-if="startScenario" :key="startScenario" :scenario="startScenario" :target="target" :busy="!!busy" :can-start="canStart" :error="startError" @close="startScenario = ''" @confirm="start(startScenario)" />
    <BaseModal v-if="takeoverOpen" title="管理员接管恢复" @close="!busy && (takeoverOpen = false)"><form class="event-dialog-body" @submit.prevent="takeover"><p>保留原运行及审批历史，为当前故障创建独立关联恢复运行。新恢复动作需要重新核对版本并审批。</p><InlineError v-if="error" :message="error" /><p v-if="!takeoverPreview">正在核对当前目标与接管条件…</p><template v-else><p>目标：{{ takeoverPreview.targetCode }} · 当前版本：{{ takeoverPreview.expectedRevision }}</p><p>{{ takeoverPreview.hint }}</p></template><label>接管原因<textarea v-model.trim="takeoverReason" rows="4" maxlength="500" required :disabled="!!busy || !!takeoverAttempt" /></label><button class="button primary" :disabled="!canSubmitTakeover">{{ busy ? '正在处理…' : takeoverAttempt ? '使用同一请求重试' : '创建接管恢复运行' }}</button></form></BaseModal>

    <template v-if="tab === 'experience'">
      <section class="panel automation-target-picker"><div><h3>演练目标</h3><small>仅作用于已登记的隔离业务</small></div><select :value="targetCode" :disabled="!!busy" aria-label="演练业务目标" @change="selectTarget(($event.target as HTMLSelectElement).value)"><option value="ops-demo-order-service">订单服务 · Redis / Sentinel</option><option value="ops-demo-notification-service">通知服务 · RabbitMQ / 消费回执</option></select></section>
      <InlineError v-for="item in experienceErrors" :key="item" :message="item" />
      <section class="panel automation-target-state" :data-state="targetError ? 'unavailable' : !target ? 'loading' : target.business.httpStatus === 200 ? 'healthy' : 'fault'">
        <Activity :size="23" /><div><strong>{{ targetError ? '目标暂不可用' : !target ? '正在读取目标状态' : target.business.httpStatus === 200 ? '业务请求正常' : `业务返回 ${target.business.httpStatus || '未知'}` }}</strong><p>{{ targetError ? '配置完成后刷新，取得目标与健康基线才能发起演练。' : target ? target.business.reasonCode || '以实际业务探针为准' : '等待本次读取结果' }}</p></div><RouterLink v-if="targetError" class="button secondary" :to="{ path: '/observability/config', query: { ciCode: targetCode } }">查看目标配置</RouterLink><small v-else>{{ date(target?.observedAt || target?.business.observedAt) }}</small>
      </section>
      <div v-if="!notificationTarget" class="automation-scenarios">
        <article class="panel automation-scenario"><span class="automation-icon"><Database :size="25" /></span><div><span class="automation-kicker">配置诊断</span><h3>Nacos Redis 配置漂移</h3><details><summary>故障机制与验证依据</summary><p>专用配置指向无监听端口，实际 Redis 查询失败，订单预览返回 503。Agent 读取配置证据并申请恢复基线。</p></details></div><div class="automation-tags"><span>Nacos 配置</span><span>Redis 依赖</span><span>HTTP 503</span></div><button class="button primary automation-scenario-action" :disabled="!!busy || !canStart" aria-describedby="automation-start-hint" @click="prepareStart('NACOS_REDIS_CONFIG_DRIFT')"><Play :size="16" />{{ busy === 'NACOS_REDIS_CONFIG_DRIFT' ? '正在发起…' : '发起真实演练' }}</button></article>
        <article class="panel automation-scenario"><span class="automation-icon violet"><Zap :size="25" /></span><div><span class="automation-kicker">流量治理</span><h3>Sentinel 限流规则回退</h3><details><summary>故障机制与验证依据</summary><p>专用资源的 QPS 阈值降为零，真实请求被 Sentinel 拦截并返回 429。Agent 比较规则与指标后申请修复。</p></details></div><div class="automation-tags"><span>Sentinel 规则</span><span>真实请求</span><span>HTTP 429</span></div><button class="button primary automation-scenario-action" :disabled="!!busy || !canStart" aria-describedby="automation-start-hint" @click="prepareStart('SENTINEL_RULE_REGRESSION')"><Play :size="16" />{{ busy === 'SENTINEL_RULE_REGRESSION' ? '正在发起…' : '发起真实演练' }}</button></article>
      </div>
      <div v-else class="automation-scenarios single"><article class="panel automation-scenario"><span class="automation-icon violet"><Layers :size="25" /></span><div><span class="automation-kicker">异步业务诊断</span><h3>RabbitMQ 消费暂停与消息积压</h3><details><summary>故障机制与验证依据</summary><p>暂停独立通知消费者，真实消息继续入队并等待消费回执。Agent 核对队列深度与消费者状态，申请恢复订阅并验证消息排空。</p></details></div><div class="automation-tags"><span>真实消息队列</span><span>消费回执</span><span>独立业务目标</span></div><button class="button primary automation-scenario-action" :disabled="!!busy || !canStart" aria-describedby="automation-start-hint" @click="prepareStart('RABBITMQ_CONSUMER_PAUSED')"><Play :size="16" />{{ busy === 'RABBITMQ_CONSUMER_PAUSED' ? '正在发起…' : '发起真实演练' }}</button></article></div>
      <p id="automation-start-hint" class="automation-help">{{ startHint }}</p>
      <p v-if="identityChanged" class="automation-notice" role="status">当前体验身份已变化，原演练不会转给新身份。请使用原浏览器体验身份续期，或进入公共案例查看示例。</p>
      <InlineError v-if="trackedError" :message="`本次演练记录读取失败：${trackedError}。请刷新重试，不能据此判断记录不存在。`" />
      <section v-if="trackedIncidentId && !identityChanged" class="panel automation-tracker" aria-label="本次演练进度">
        <header class="panel-header"><div><h3>演练进度与对应运行</h3><p>按照同一演练 ID 关联工单和运行，运行结束后仍需确认业务恢复。</p></div>
          <select :value="trackedIncidentId" aria-label="选择要跟踪的演练" :disabled="!!busy" @change="action('track', async () => { trackedIncidentId = ($event.target as HTMLSelectElement).value; trackedRuns = []; await router.replace({ query: { ...route.query, incidentId: trackedIncidentId } }); await refreshTrackedRuns(); })"><option v-for="incident in incidents" :key="incident.incidentId" :value="incident.incidentId">{{ date(incident.startedAt) }} · {{ scenarioLabel(incident.scenarioCode) }}</option></select>
        </header>
        <div class="automation-tracker-steps">
          <div><span class="automation-step-number">1</span><strong>{{ trackedIncident ? label(trackedIncident.status) : '演练状态待确认' }}</strong><small>{{ scenarioLabel(trackedIncident?.scenarioCode) }}</small></div>
          <div><span class="automation-step-number">2</span><strong>{{ latestTrackedRun ? '工单已关联' : '等待监控与工单' }}</strong><router-link v-if="latestTrackedRun && runTicket(latestTrackedRun)" :to="`/tickets/${runTicket(latestTrackedRun)}`">查看工单 #{{ runTicket(latestTrackedRun) }}<ArrowRight :size="13" /></router-link><small v-else>监控确认持续故障后触发</small></div>
          <div><span class="automation-step-number">3</span><strong>{{ latestTrackedRun ? label(latestTrackedRun.status) : '等待 Agent 运行' }}</strong><button v-if="latestTrackedRun" class="button text" :disabled="!!busy" @click="action(latestTrackedRun.id, () => selectRun(latestTrackedRun!.id))">查看对应运行<ArrowRight :size="13" /></button><small v-else>创建后将在此显示</small></div>
          <div><span class="automation-step-number">4</span><strong>{{ trackedIncident?.status === 'RECOVERED' || trackedIncident?.status === 'EXPIRED_RECOVERED' ? '业务已恢复' : '业务恢复待确认' }}</strong><small>{{ source(trackedIncident?.recoverySource) }}</small></div>
        </div>
        <footer class="automation-tracker-footer"><code>演练 {{ trackedIncidentId }}</code><span v-if="trackedIncident">保护到期 {{ date(trackedIncident.expiresAt) }}</span></footer>
        <p v-if="!latestTrackedRun" class="automation-help">尚未查询到本次演练的运行。页面会自动刷新；若持续没有记录，请在告警和工单中心检查监控链路。</p>
      </section>
      <section v-if="latestTrackedRun && ['NEEDS_ATTENTION', 'BUDGET_EXCEEDED', 'REJECTED', 'EXPIRED', 'CANCELLED'].includes(latestTrackedRun.status) && runTicket(latestTrackedRun)" class="panel automation-manual-next"><strong>自动流程已停止，人工继续处置</strong><p>先核对当前现场并执行恢复，再提交处理结果、等待观察与确认。</p><div class="automation-handoff-actions"><RouterLink class="button primary" :to="{ path: `/tickets/${runTicket(latestTrackedRun)}`, query: { handoff: '1' } }">人工接管</RouterLink><EventManualRecovery :key="trackedIncidentId" :ticket-id="runTicket(latestTrackedRun)!" :target-code="targetCode" :incident-id="trackedIncidentId" @restored="action('refresh-recovery', refresh)" /><RouterLink v-if="!auth.isDemo" class="button secondary" :to="{ path: `/tickets/${runTicket(latestTrackedRun)}`, query: { record: '1' } }">记录人工处理</RouterLink></div></section>
      <section class="panel automation-status-panel"><header class="panel-header"><div><h3>当前观测与恢复来源</h3><p>故障最长保留 15 分钟；到期保护恢复会单独标记。</p></div><button v-if="canRestore" class="button secondary" :disabled="!!busy" @click="restore"><Wrench :size="16" />执行当前演练恢复</button></header><div class="automation-facts"><div><span>当前场景</span><strong>{{ scenarioLabel(target?.scenarioCode) }}</strong></div><div v-if="notificationTarget"><span>队列待消费 / 消费者</span><strong>{{ target?.queue?.messagesReady ?? '—' }} / {{ target?.queue?.consumerCount ?? '—' }}</strong></div><div v-else><span>Sentinel QPS 阈值</span><strong>{{ target?.sentinel?.qps ?? '—' }}</strong></div><div><span>连续成功探针</span><strong>{{ target?.business.consecutiveSuccesses ?? '—' }}</strong></div><div><span>恢复来源</span><strong>{{ source(target?.recoverySource) }}</strong></div></div><p v-if="notificationTarget" class="automation-help">消费验证基于真实消息回执与队列排空；进程累计计数不能单独证明本次恢复。</p></section>
      <section class="panel"><header class="panel-header"><div><h3>{{ trackedIncidentId ? '本次演练的 Agent 运行' : '最近的 Agent 运行' }}</h3><p>点开查看模型决策、具体动作审批和恢复证据。</p></div><button class="button text" @click="tab = 'runs'">运行中心<ArrowRight :size="16" /></button></header><div v-if="(trackedIncidentId ? trackedRuns : runs).length" class="automation-recent"><button v-for="run in (trackedIncidentId ? trackedRuns : runs).slice(0, 4)" :key="run.id" :disabled="!!busy" @click="action(run.id, () => selectRun(run.id))"><span class="automation-run-dot" :data-status="run.status"></span><div><strong>{{ label(run.status) }} · 工单 #{{ runTicket(run) || '待关联' }}</strong><small>{{ date(run.created_at) }}</small></div><code>{{ run.id.slice(0, 8) }}</code><ArrowRight :size="16" /></button></div><EmptyState v-else title="等待对应的运行记录" description="持续故障进入监控后，系统会创建工单并启动诊断。" /></section>
    </template>

    <template v-if="tab === 'runs'">

      <div class="automation-run-layout">
        <details class="panel automation-run-list automation-fold" :open="runListOpen" @toggle="runListOpen = ($event.target as HTMLDetailsElement).open"><summary>选择运行记录 <span>{{ total }} 次 · {{ detail ? `当前 ${detail.id.slice(0, 8)}` : '尚未选择' }}</span></summary>
          <div class="automation-run-filter-body">
            <form class="automation-run-filter" @submit.prevent="action('filter', filterRuns)">
              <label for="automation-ticket-filter">按工单 ID 查找</label>
              <div><input id="automation-ticket-filter" v-model="ticketFilter" inputmode="numeric" placeholder="全部可见工单" /><button class="button secondary" :disabled="!!busy" type="submit">查找</button></div>
              <button v-if="appliedTicketFilter" type="button" class="button text" :disabled="!!busy" @click="action('clear-filter', async () => { ticketFilter = ''; await filterRuns(); })">清除工单 #{{ appliedTicketFilter }} 筛选</button>
            </form>
          </div>
          <button v-for="run in runs" :key="run.id" class="automation-run-item" :class="{ selected: detail?.id === run.id }" :disabled="!!busy" @click="action(run.id, () => selectRun(run.id))"><span class="automation-run-dot" :data-status="run.status"></span><div><strong>{{ label(run.status) }}</strong><small>工单 #{{ runTicket(run) || '待关联' }} · {{ date(run.created_at) }}</small><code>{{ run.id.slice(0, 8) }} · v{{ run.version }}</code></div><ArrowRight :size="14" /></button><EmptyState v-if="!runs.length" :title="appliedTicketFilter ? '该工单暂无可见运行' : '暂无执行记录'" :description="appliedTicketFilter ? '可清除筛选查看其他记录，或等待演练告警触发运行。' : '先发起真实故障演练。'" /><footer class="automation-pagination"><span>共 {{ total }} 条</span><template v-if="total > 10"><button class="button secondary" :disabled="page <= 1 || !!busy" aria-label="上一页" @click="action('page', async () => { page--; await refresh(); })"><ChevronLeft :size="16" /></button><span>{{ page }} / {{ Math.ceil(total / 10) }}</span><button class="button secondary" :disabled="page * 10 >= total || !!busy" aria-label="下一页" @click="action('page', async () => { page++; await refresh(); })"><ChevronRight :size="16" /></button></template></footer></details>
        <section v-if="detail" :key="detail.id" class="automation-run-detail">
          <section class="panel"><header class="panel-header"><div><h3>运行 {{ detail.id.slice(0, 8) }}</h3><p><router-link :to="`/tickets/${detail.state.ticketId}`">工单 #{{ detail.state.ticketId }}</router-link> · {{ detail.snapshot.model.model }}</p></div><div class="row-actions"><button v-if="canPause" class="button secondary" :disabled="!!busy" @click="action('pause', async () => { await api.pause(detail!.id); await selectRun(detail!.id); notice = '已请求在步骤边界暂停；已发出的远程动作仍会返回执行结果。'; })"><Pause :size="14" />暂停</button><button v-if="canResume" class="button secondary" :disabled="!!busy" @click="action('resume', async () => { await api.resume(detail!.id); await selectRun(detail!.id); notice = '已请求继续同一运行，原有预算、审批要求和期限继续生效。'; })"><Play :size="14" />继续运行</button><button v-if="ownRun && !terminal.has(detail.status)" class="button secondary" :disabled="!!busy" @click="action('cancel', async () => { await api.cancel(detail!.id); await selectRun(detail!.id); notice = '已请求取消运行，已发出的远程动作仍需查看最终结果。'; })"><Square :size="14" />取消</button></div></header>
            <section v-if="detail.takeoverRecovery" class="automation-notice" role="status"><div><strong>管理员接管恢复 · {{ label(detail.takeoverRecovery.status) }}</strong><p>{{ detail.takeoverRecovery.message }}</p><small>更新于 {{ date(detail.takeoverRecovery.updatedAt) }}</small><RouterLink :to="`/tickets/${detail.state.ticketId}`">查看原事件的恢复记录与确认 →</RouterLink></div></section><p v-if="detail.authorization?.hint" class="automation-help" role="status">{{ detail.authorization.hint }}</p><button v-if="detail.authorization?.canTakeover || takeoverAttempt?.runId === detail.id" class="button primary" :disabled="!!busy" @click="prepareTakeover">管理员接管恢复</button><div class="automation-result-main" role="status"><span class="automation-kicker">{{ terminal.has(detail.status) ? '运行结果' : '当前任务' }}</span><h2>{{ verifiedCompletion ? '恢复验证已通过' : runPresentation.title }}</h2><p>{{ needsHandoff && runMessage ? runMessage : runPresentation.description }}</p><small class="automation-current-step">当前步骤 · {{ nodeName(detail.nodeId) }}</small></div>
            <div class="automation-result-next"><span>{{ needsHandoff ? '执行恢复 → 记录结果 → 观察与确认' : awaitingDecision ? '核对本次动作后决定是否继续' : '依据实际执行结果继续下一步' }}</span><div class="automation-handoff-actions"><button v-if="awaitingDecision" class="button primary" :disabled="!!busy" @click="showCurrentApproval">{{ runPresentation.action }}</button><button v-else-if="!terminal.has(detail.status) && runPresentation.code !== 'VERIFYING'" class="button primary" @click="showTrace">查看执行进度</button><RouterLink :class="['button', !terminal.has(detail.status) && runPresentation.code !== 'VERIFYING' ? 'secondary' : 'primary']" :to="{ path: `/tickets/${detail.state.ticketId}`, query: needsHandoff ? { handoff: '1' } : {} }">{{ needsHandoff ? '人工接管并继续处置' : runPresentation.code === 'VERIFYING' || terminal.has(detail.status) ? '核对恢复结果' : '查看关联事件' }} <ArrowRight :size="16" /></RouterLink></div></div>
            <details class="automation-result-disclosure"><summary>步骤、变更与恢复证据</summary><dl class="automation-result-facts"><div><dt>当前步骤</dt><dd>{{ nodeName(detail.nodeId) }}</dd></div><div><dt>实际变更</dt><dd>{{ eventsComplete ? writeSummary.text : `证据尚未读完 · ${writeSummary.text}` }}<small>只统计本次运行的写入回执。</small></dd></div><div><dt>恢复验证</dt><dd>{{ verificationStatus }}</dd></div><div><dt>流程 Token 记录</dt><dd>{{ runBudgetSummary(detail) }}<small>流程记账可能含预留，不代表 DeepSeek 实际计费用量。</small></dd></div></dl><p v-if="recoveryProgress">{{ recoveryProgress }}</p><p v-if="recoveryOutcome">{{ recoveryOutcome }}</p><p v-if="detail.status === 'BUDGET_EXCEEDED' && !runHasUnlimitedTokenBudget(detail)">下一次调用的输入与回复预留也需在剩余额度内，预算限制不等于实际用量已达到上限。</p><template v-if="needsHandoff"><p>执行恢复会改变当前演练配置；记录处理只保存说明。技术确认、业务确认和关闭仍需在事件中分别完成。</p><div class="automation-handoff-actions"><EventManualRecovery v-if="runTicketContext" :key="detail.id" :ticket-id="detail.state.ticketId" :target-code="runTicketContext.affectedCiCode" :incident-id="runTicketContext.incidentId" @restored="action('refresh-recovery', refresh)" /><RouterLink v-if="!auth.isDemo" class="button secondary" :to="{ path: `/tickets/${detail.state.ticketId}`, query: { record: '1' } }">记录人工处理</RouterLink></div></template></details>
            <details v-if="rawRunMessage || modelFailure || modelApprovalHint" class="automation-failure-detail"><summary>技术详情与审批说明</summary><p v-if="modelApprovalHint">{{ modelApprovalHint }}</p><pre v-if="rawRunMessage">{{ rawRunMessage }}</pre><code v-if="modelFailure">{{ modelFailure.code }}</code></details>
          </section>
          <section v-for="approval in pendingApprovals" :key="approvalKey(approval)" class="panel automation-approval"><ApprovalCard :approval="approval" :available="approvalAvailable(approval)" :busy="!!busy || !!approvalInbox.busy" :unavailable-reason="approvalUnavailableReason(approval)" @decision="decision(approval, $event)" /></section>
          <AutomationUsage :run="detail" />
          <details class="panel automation-fold"><summary>AI 决策与审批记录<span>{{ detail.approvals.length }} 项审批</span></summary><div class="automation-fold-body"><p v-if="detail.state.summary">{{ detail.state.summary }}</p><p v-else>暂无 AI 摘要。</p><div v-for="approval in detail.approvals" :key="approval.id" class="automation-approval-history"><strong>{{ ({ APPROVED: '已批准', REJECTED: '已拒绝', PENDING: '待决定', EXPIRED: '已到期' } as Record<string,string>)[approval.status] || approval.status }}</strong><span>{{ toolLabels[String(approval.payload.name)] || approval.payload.name }}</span><p>{{ approval.reason || '未填写说明' }}</p></div></div></details>
          <details ref="tracePanel" class="panel automation-trace automation-fold"><summary>执行步骤与证据<span>{{ events.length }} 条记录</span></summary>            <div class="automation-graph" :class="{ branched: !linearGraph }" role="list" aria-label="工作流执行图"><template v-for="(node, index) in graph?.nodes" :key="node.id"><ArrowRight v-if="linearGraph && index" class="automation-graph-arrow" :size="18" aria-hidden="true" /><div role="listitem" class="automation-node" :class="{ current: detail.nodeId === node.id && !terminal.has(detail.status), done: nodeDone(node.id) }"><component :is="node.type === 'AGENT' ? Bot : node.type === 'END' ? FileCheck2 : node.type === 'CONDITION' ? GitBranch : Play" :size="20" /><strong>{{ node.label || node.id }}</strong><small>{{ node.type }} · {{ node.id }}</small><div v-if="!linearGraph" class="automation-node-edges"><span v-for="edge in outgoing(node.id)" :key="`${edge.to}-${edge.when}`"><em>{{ branchName(edge.when) }}</em><ArrowRight :size="12" />{{ nodeName(edge.to) }}</span><span v-if="!outgoing(node.id).length">流程终点</span></div></div></template></div>
            <div class="automation-facts compact"><div><span>模型决策</span><strong>{{ detail.state.turns }} / {{ toolCatalog?.limits.maxModelTurns || 12 }}</strong></div><div><span>工具调用</span><strong>{{ detail.state.toolCount }} / {{ toolCatalog?.limits.maxToolCalls || 18 }}</strong></div><div><span>流程 Token 记录</span><strong>{{ runBudgetSummary(detail) }}</strong><small>记账可能含预留，实际消费以供应商回执为准</small></div><div><span>工单验证</span><strong>{{ verificationStatus }}</strong></div></div>

            <div class="automation-event-groups">
              <details v-for="group in eventGroups" :key="group.id" class="automation-event-group">
                <summary><span class="automation-group-icon"><GitBranch :size="18" /></span><span class="automation-group-title"><strong>{{ group.label }}</strong><small>{{ group.events.length }} 条记录 · {{ automationEventLabel(group.events.at(-1)!.type, detail) }}</small></span><time>{{ date(group.events.at(-1)?.createdAt) }}</time><ChevronDown :size="16" class="automation-disclosure" /></summary>
                <div class="automation-event-body"><div class="automation-phase-counts" aria-label="阶段记录数量"><span v-for="phase in group.phases" :key="phase.key">{{ phase.label }} {{ phase.events.length }}</span></div><ol class="automation-events"><li v-for="event in group.events" :key="event.id"><span class="automation-event-dot"></span><div><div class="automation-event-title"><strong>{{ automationEventLabel(event.type, detail) }}</strong><time>{{ date(event.createdAt) }}</time></div><small>事件 #{{ event.id }}</small><details class="automation-event-evidence"><summary>查看原始参数与证据</summary><code>{{ event.type }}</code><pre>{{ pretty(event.payload) }}</pre></details></div></li></ol></div>
              </details>
              <p v-if="!eventGroups.length" class="automation-help">等待第一条执行记录。</p>
            </div>
          </details>
        </section><section v-else class="panel automation-no-selection"><Bot :size="42" /><h3>选择一次运行</h3><p>查看模型决策、工具参数、审批与恢复证据。</p></section>
      </div>
      <details class="panel automation-start-disclosure"><summary>手动启动已有事件的诊断流程</summary><div class="automation-run-form"><div><strong>为已有事件启动运行</strong><p>通常由告警自动触发；手动启动需要可操作的隔离演练工单和已验证模型。</p></div><label>事件 ID<input v-model.number="ticketId" type="number" min="1" step="1" /></label><label>工作流<select v-model="runDefinitionId" :disabled="auth.isDemo"><option v-for="item in definitions" :key="item.id" :value="item.id" :disabled="!item.published_version">{{ item.name }} · v{{ item.published_version || '未发布' }}</option></select></label><label>模型<select v-model="provider"><option value="" disabled>请选择已验证模型</option><option v-for="model in models" :key="model.provider" :value="model.provider" :disabled="!model.configured || !model.toolCalling">{{ model.model || model.provider }}{{ !model.configured ? ` · ${modelStatus(model)}` : model.toolCalling ? '' : ' · 未验证' }}</option></select></label><button class="button primary" :disabled="!!busy || !canCreate" @click="action('create', createRun)"><Play :size="16" />{{ busy === 'create' ? '正在启动…' : '启动运行' }}</button></div></details>
    </template>

    <template v-if="tab === 'workflows'">
      <section class="panel"><header class="panel-header"><div><h3>工作流定义与不可变版本</h3><p>先校验草稿，再发布新版本。已有运行继续使用启动时的完整快照。</p></div><select :value="definitionId" aria-label="选择工作流" :disabled="!!busy" @change="action('definition', () => loadDefinition(($event.target as HTMLSelectElement).value))"><option v-for="item in definitions" :key="item.id" :value="item.id">{{ item.name }}</option></select></header><div class="automation-graph" :class="{ branched: !linearGraph }" role="list" aria-label="已保存草稿的真实节点连接"><template v-for="(node, index) in graph?.nodes" :key="node.id"><ArrowRight v-if="linearGraph && index" class="automation-graph-arrow" :size="18" aria-hidden="true" /><div class="automation-node" role="listitem"><component :is="node.type === 'AGENT' ? Bot : GitBranch" :size="20" /><strong>{{ node.label || node.id }}</strong><small>{{ node.type }} · {{ node.id }}</small><div v-if="!linearGraph" class="automation-node-edges"><span v-for="edge in outgoing(node.id)" :key="`${edge.to}-${edge.when}`"><em>{{ branchName(edge.when) }}</em><ArrowRight :size="12" />{{ nodeName(edge.to) }}</span><span v-if="!outgoing(node.id).length">流程终点</span></div></div></template></div><div class="automation-version"><span>已发布 v{{ definition?.published_version }}</span><span>已保存草稿修订 {{ definition?.draft_revision }}</span><span>图中连接来自已保存的 edges，保存后更新</span></div></section>
      <details class="panel automation-editor"><summary class="automation-editor-summary">编辑工作流定义与发布</summary><header class="panel-header"><div><h3><Code2 :size="19" />结构化定义</h3><p>当前提供 JSON 编辑、后端校验和发布。条件采用结构化值比较，工具可从节点输出引用参数。</p></div></header><textarea v-model="graphText" :readonly="!auth.isAdmin" rows="22" aria-label="工作流 JSON 定义" spellcheck="false"></textarea><div v-if="auth.isAdmin" class="row-actions"><button class="button secondary" :disabled="!!busy || !definition" @click="action('validate', async () => { await api.validate(JSON.parse(graphText)); notice = '工作流校验通过。'; })">校验草稿</button><button class="button secondary" :disabled="!!busy || !definition" @click="action('save', async () => { await api.save(definitionId, definition!.name, definition!.draft_revision, JSON.parse(graphText)); await loadDefinition(definitionId); notice = '草稿已保存。'; })">保存草稿</button><button class="button primary" :disabled="!!busy || !definition || graphText !== pretty(definition.graph)" @click="action('publish', async () => { await api.publish(definitionId, definition!.draft_revision); await loadDefinition(definitionId); definitions = await api.definitions(); notice = '新版本已发布。'; })">发布已保存草稿</button></div><p v-if="auth.isAdmin && definition && graphText !== pretty(definition.graph)">当前编辑尚未保存。保存后可发布对应修订。</p><p v-else-if="!auth.isAdmin">当前账号可以查看完整定义和版本，发布由管理员管理。</p></details>
    </template>

    <template v-if="tab === 'tools'">
      <section class="panel"><header class="panel-header"><div><h3>原生工具调用能力</h3><p>只有实际验证通过的模型能启动 Agent。“验证能力”会发送一次真实模型请求并使用 AI 预算。</p></div></header><div class="automation-models"><article v-for="model in models" :key="model.provider"><span class="automation-icon"><Bot :size="23" /></span><div><strong>{{ model.model }}</strong><small>{{ model.provider }} · {{ modelStatus(model) }}</small></div><span :class="['automation-pill', { good: model.configured && model.toolCalling }]">{{ !model.configured ? '未配置' : model.toolCalling ? '工具已验证' : '待验证' }}</span><button v-if="auth.isAdmin && model.configured" class="button secondary" :disabled="!!busy" @click="action('probe', async () => { await api.probe(model.provider); models = (await api.models()).models; chooseModel(); notice = '模型能力验证结果已更新。'; })">验证能力</button></article></div></section>
      <details class="panel automation-tool-disclosure"><summary>工具目录与参数 · {{ toolCatalog?.tools.length || 0 }} 项</summary><div class="automation-tools"><article v-for="tool in toolCatalog?.tools" :key="tool.function.name" class="panel"><header><span class="automation-icon"><Wrench :size="20" /></span><span class="automation-pill" :class="{ approval: toolCatalog?.approvalRequired.includes(tool.function.name) }">{{ toolCatalog?.approvalRequired.includes(tool.function.name) ? '精确动作审批' : '受控工具' }}</span></header><h3>{{ toolLabels[tool.function.name] || tool.function.name }}</h3><code>{{ tool.function.name }}</code><p>{{ tool.function.description }}</p><details><summary>参数协议</summary><pre>{{ pretty(tool.function.parameters) }}</pre></details></article></div></details>
    </template>
  </div>
</template>

<style scoped>
.automation-handoff-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.automation-manual-next { padding: 20px; border-color: #c5d9fa; background: #f6f9ff; margin-bottom: 18px; }
.automation-manual-next > strong { font-size: 17px; color: #214b8c; }
.automation-manual-next > p { margin: 8px 0 16px; color: #64748b; }
</style>
