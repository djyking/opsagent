import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const pinia = require('pinia');
const compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
function evaluate(source, imports = {}) {
  const js = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true,
  } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
function load(path, imports) { return evaluate(readFileSync(new URL('../src/' + path, import.meta.url), 'utf8'), imports); }
const presentation = load('utils/automation-presentation.ts');
const deadline = () => new Date(Date.now() + 600000).toISOString();
function approval(id = 'approval-1', overrides = {}) {
  return { id, run_id: 'run-1', status: 'PENDING', args_hash: 'a'.repeat(64), revision: 1,
    expires_at: deadline(), payload: { id: 'diagnose:3:0', name: 'demo_config_restore', arguments: { expectedRevision: 'b'.repeat(64) } },
    runStatus: 'WAITING_APPROVAL', ownerId: 7, ticketId: 2057, incidentId: 'incident-1', nodeId: 'diagnose',
    nodeLabel: 'AI 诊断与处置', runDeadline: deadline(), pauseRequested: false, ...overrides };
}

// Ownership, lifecycle and exact approval identity are independent conditions.
{
  const item = approval();
  assert.equal(presentation.approvalActionable(item, 7, false, Date.now()), true);
  assert.equal(presentation.approvalActionable(item, 8, false, Date.now()), false);
  assert.equal(presentation.approvalActionable(item, 8, true, Date.now()), true);
  for (const change of [{ pauseRequested: true }, { runStatus: 'COMPLETED' }, { status: 'APPROVED' },
    { expires_at: new Date(0).toISOString() }, { runDeadline: new Date(0).toISOString() }, { runDeadline: 'invalid' }]) {
    assert.equal(presentation.approvalActionable({ ...item, ...change }, 7, true, Date.now()), false);
  }
  assert.notEqual(presentation.approvalKey(item), presentation.approvalKey({ ...item, revision: 2 }));
  assert.notEqual(presentation.approvalKey(item), presentation.approvalKey({ ...item, args_hash: 'c'.repeat(64) }));
  assert.match(presentation.approvalDescription(item).change, /6379/);
  assert.match(presentation.approvalDescription(approval('flow', { payload: { name: 'demo_flow_restore' } })).change, /QPS.*5/);
}
console.log('PASS approval identity, owner/admin, deadlines, pause and concrete action summaries');

// Grouping must never reorder alternating model/tool events within a real node.
const graph = { nodes: [{ id: 'diagnose', type: 'AGENT', label: 'AI 诊断与处置' }, { id: 'verify', type: 'TOOL', label: '恢复复核' }], edges: [] };
const records = [
  { id: 5, type: 'TOOL_OBSERVATION', nodeId: 'diagnose' },
  { id: 1, type: 'RUN_CREATED', nodeId: '' },
  { id: 4, type: 'MODEL_INTENT', nodeId: 'diagnose' },
  { id: 2, type: 'MODEL_INTENT', nodeId: 'diagnose' },
  { id: 3, type: 'TOOL_OBSERVATION', nodeId: 'diagnose' },
  { id: 6, type: 'RECOVERY_WAITING', nodeId: 'verify' },
].map(event => ({ ...event, createdAt: deadline(), payload: { rawEvidence: `ORIGINAL-${event.id}` } }));
const originalRecords = JSON.stringify(records);
const groups = presentation.groupAutomationEvents(records, graph);
assert.deepEqual(groups.map(group => group.label), ['运行控制记录', 'AI 诊断与处置', '恢复复核']);
assert.deepEqual(groups[1].events.map(event => event.id), [2, 3, 4, 5]);
assert.deepEqual(groups[1].phases.map(phase => [phase.key, phase.events.length]), [['model', 2], ['tools', 2]]);
assert.equal(groups.flatMap(group => group.events).length, records.length);
assert.equal(JSON.stringify(records), originalRecords);
console.log('PASS real node grouping, interleaved chronological events, complete unchanged evidence');

const memory = new Map();
globalThis.sessionStorage = { getItem: key => memory.get(key) || null, setItem: (key, value) => memory.set(key, value) };
globalThis.document = { hidden: false, querySelector: () => null };
const auth = vue.reactive({ user: { userId: 7 }, isAdmin: false, isAuthenticated: true });
let pool = [approval()];
let apiError = false;
let pendingImpl = async () => ({ items: structuredClone(pool), total: pool.length });
const decisions = [];
const api = {
  pendingApprovals: async () => { if (apiError) throw new Error('offline'); return pendingImpl(); },
  decide: async (item, approved, reason) => {
    decisions.push({ id: item.id, revision: item.revision, hash: item.args_hash, approved, reason });
    pool = pool.filter(candidate => candidate.id !== item.id);
  },
};
const useInbox = load('stores/approval-inbox.ts', {
  '@/api/automation': { automationApi: api }, '@/utils/automation-presentation': presentation,
  '@/stores/auth': { useAuthStore: () => auth },
}).useApprovalInboxStore;
const piniaInstance = pinia.createPinia();
pinia.setActivePinia(piniaInstance);
const inbox = useInbox();
try {
  await inbox.refresh();
  assert.equal(inbox.count, 1);
  assert.equal(inbox.open, true);
  assert.equal(inbox.selected.id, 'approval-1');
  inbox.later();
  await inbox.refresh();
  assert.equal(inbox.open, false, 'Repeated polling must not reopen a deferred approval');
  pool.push(approval('approval-2'));
  await inbox.refresh();
  assert.equal(inbox.selected.id, 'approval-2');
  assert.equal(inbox.open, true);
  inbox.later();
  await inbox.refresh();
  assert.equal(inbox.open, false);

  pool[0] = { ...pool[0], revision: 2, args_hash: 'c'.repeat(64) };
  document.querySelector = () => ({});
  await inbox.refresh();
  assert.equal(inbox.open, false, 'Do not interrupt another modal');
  document.querySelector = () => null;
  await inbox.refresh();
  assert.equal(inbox.selected.revision, 2, 'A new exact revision needs a new review');
  inbox.later();

  const stale = inbox.availableItems[0];
  pool[0] = { ...pool[0], revision: 3, args_hash: 'd'.repeat(64) };
  await assert.rejects(() => inbox.decide(stale, true, '核对目标'), /状态|变化/);
  assert.equal(decisions.length, 0, 'Never submit a changed revision');
  const current = inbox.availableItems[0];
  apiError = true;
  await assert.rejects(() => inbox.decide(current, true, '核对目标'), /未能核对/);
  assert.equal(decisions.length, 0, 'A failed fresh read must fail closed');
  apiError = false;
  pool = [{ ...pool[0], pauseRequested: true }];
  await assert.rejects(() => inbox.decide(current, true, '核对目标'), /状态|变化/);
  assert.equal(inbox.count, 0);
  assert.equal(decisions.length, 0);

  pool = [approval('exact', { revision: 4, args_hash: 'e'.repeat(64) })];
  await inbox.refresh(false);
  await inbox.decide(inbox.availableItems[0], false, '  目标尚需确认  ');
  assert.deepEqual(decisions, [{ id: 'exact', revision: 4, hash: 'e'.repeat(64), approved: false, reason: '目标尚需确认' }]);
  assert.equal(inbox.count, 0);
  assert.equal(inbox.decisionVersion, 1);

  pool = [approval('expired', { expires_at: new Date(Date.now() + 1000).toISOString() })];
  await inbox.refresh(false);
  assert.equal(inbox.count, 1);
  inbox.now = Date.now() + 2000;
  assert.equal(inbox.count, 0, 'Expiry must update even without another server response');

  let release;
  pendingImpl = () => new Promise(resolve => { release = resolve; });
  const oldFetch = inbox.refresh();
  auth.user = null; auth.isAuthenticated = false;
  await vue.nextTick();
  release({ items: [approval('old-user')], total: 1 });
  await oldFetch;
  assert.equal(inbox.count, 0);
  assert.equal(inbox.open, false, 'A response from the logged-out actor cannot reopen a popup');
  assert.equal(inbox.selected, undefined);
} finally { inbox.stop(); pinia.disposePinia(piniaInstance); }
console.log('PASS global inbox polling dedupe, later, modal courtesy, stale revisions, offline/pause/expiry, exact rejection, logout race');

const Stub = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.actions?.(), slots.tabs?.()]) };
const icons = new Proxy({}, { get: () => Stub });
const route = vue.reactive({ query: {}, fullPath: '/automation', path: '/automation' });
function componentFixture(path, props = {}, extra = {}) {
  const filename = path.split('/').at(-1);
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
  const { descriptor, errors } = compiler.parse(source, { filename });
  assert.deepEqual(errors, []);
  const script = compiler.compileScript(descriptor, { id: 'approval-component-test' });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename, id: 'approval-component-test', compilerOptions: { bindingMetadata: script.bindings } });
  assert.deepEqual(template.errors, []);
  const imports = {
    vue: { ...vue, useId: () => 'approval-test-input', onMounted() {}, onBeforeUnmount() {} },
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace: async () => {}, push: async () => {} }) },
    '@lucide/vue': icons, '@/api/automation': { automationApi: api },
    '@/utils/automation-presentation': presentation, '@/stores/auth': { useAuthStore: () => ({ user: { userId: 7 }, isAdmin: true }) },
    '@/stores/approval-inbox': { useApprovalInboxStore: () => ({ isCurrent: () => true, decisionVersion: 0 }) },
    '@/components/PageHeader.vue': Stub, '@/components/InlineError.vue': Stub, '@/components/EmptyState.vue': Stub,
    '@/components/automation/ApprovalCard.vue': Stub, '@/components/automation/InspectionRuns.vue': Stub,
    '@/styles/pages/automation.css': {}, '@/styles/components/automation-approval.css': {}, ...extra,
  };
  const component = evaluate(script.content, imports).default;
  const render = evaluate(template.code, { vue }).render;
  const scope = vue.effectScope();
  const emitted = [];
  const reactiveProps = vue.reactive(props);
  const state = scope.run(() => component.setup(reactiveProps, { expose() {}, emit: (...args) => emitted.push(args) }));
  return { state, emitted, props: reactiveProps, component: { ...component, render }, stop: () => scope.stop(), async html() {
    const context = vue.proxyRefs({ ...state, ...reactiveProps });
    const app = vue.createSSRApp({ render: () => render(context, [], reactiveProps, context, {}, {}) });
    app.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', { href: p.to }, slots.default?.()) });
    return renderToString(app);
  } };
}
{
  const item = approval();
  const app = componentFixture('components/automation/ApprovalCard.vue', { approval: item, available: true, busy: false });
  try {
    const html = await app.html();
    for (const text of ['操作目标', '预期变化', '影响范围', '有效期至', '6379']) assert.ok(html.includes(text));
    assert.match(html, /<details class="approval-technical">/);
    assert.ok(html.indexOf('技术详情与原始参数') < html.indexOf('expectedRevision'));
    app.state.reason.value = '  已核对目标和影响范围  ';
    app.state.decide(true);
    assert.deepEqual(app.emitted, [['decision', { approved: true, reason: '已核对目标和影响范围' }]]);
    app.props.available = false;
    app.state.decide(false);
    assert.equal(app.emitted.length, 1, 'An unavailable action cannot emit a decision');
    assert.ok(!(await app.html()).includes('<textarea'));
  } finally { app.stop(); }
}
console.log('PASS shared real ApprovalCard render: human summary, closed technical evidence and gated actions');
{
  const app = componentFixture('views/AutomationView.vue');
  try {
    assert.equal(app.state.tab.value, 'runs', 'Automation opens the operational queue by default');
    app.state.tab.value = 'experience';
    const scenarioHtml = await app.html();
    const buttons = [...scenarioHtml.matchAll(/<button class="([^"]*automation-scenario-action[^"]*)"/g)].map(match => match[1]);
    assert.equal(buttons.length, 2);
    assert.equal(buttons[0], buttons[1]);
    assert.match(buttons[0], /primary/);
    app.state.targetCode.value = 'ops-demo-notification-service';
    const notificationHtml = await app.html();
    assert.match(notificationHtml, /RabbitMQ 消费暂停与消息积压/);
    assert.equal([...notificationHtml.matchAll(/<button class="([^"]*automation-scenario-action[^"]*)"/g)].length, 1);
    app.state.tab.value = 'runs';
    app.state.detail.value = { id: 'run-1', ownerId: 7, status: 'NEEDS_ATTENTION', nodeId: 'verify', approvals: [], snapshot: { graph, model: { model: 'test' } },
      state: { ticketId: 2057, deadline: deadline(), turns: 5, toolCount: 11, tokens: 14812, ticketResolved: true,
        recoveryVerification: { resolved: false, reason: 'ALERT_PENDING' } } };
    app.state.events.value = records;
    const html = await app.html();
    assert.equal([...html.matchAll(/<details class="automation-event-group">/g)].length, 3);
    const positions = [2, 3, 4, 5].map(id => html.indexOf(`事件 #${id}`));
    assert.ok(positions.every((position, index) => position >= 0 && (!index || position > positions[index - 1])));
    for (const event of records) assert.ok(html.includes(`ORIGINAL-${event.id}`));
    assert.ok(!html.includes('<strong>已验证解决</strong>'));
    app.state.detail.value.state = { ticketId: 2063, deadline: deadline(), turns: 0, toolCount: 0, tokens: 0,
      ticketResolved: false, message: 'MODEL_OUTCOME_UNKNOWN' };
    app.state.events.value = [];
    const interrupted = await app.html();
    assert.ok(interrupted.includes('等待人工处理'));
    assert.ok(!interrupted.includes('本次运行已结束'));
    assert.ok(interrupted.includes('本次运行尚未产生审批请求'));
    assert.match(interrupted, /<details class="automation-failure-detail"><summary>查看错误代码<\/summary><code>MODEL_OUTCOME_UNKNOWN<\/code>/);
    assert.equal(app.state.canResume.value, false, 'An unknown model receipt cannot safely resume the same call');
    app.state.detail.value.state.modelFailure = { code: 'MODEL_PROVIDER_UNAVAILABLE', reason: '模型服务当前不可用，请检查配置。',
      canResume: false, retryable: false, recoveryAction: 'CHECK_CONFIGURATION' };
    app.state.detail.value.approvals = [approval()];
    const knownFailure = await app.html();
    assert.ok(knownFailure.includes('模型服务当前不可用，请检查配置。'));
    assert.ok(!knownFailure.includes('本次运行尚未产生审批请求'), 'Existing approvals must not be erased by model failure copy');
    delete app.state.detail.value.state.modelFailure;
    app.state.detail.value.state.message = '人工输入待确认';
    assert.equal(app.state.canResume.value, true, 'A non-model attention state retains existing continuation behavior');
  } finally { app.stop(); }
}
console.log('PASS real AutomationView render: drill buttons, event chronology, truthful recovery/model failure, exact resume gate');

// The global shell controls remain available away from /automation.
{
  route.path = '/tickets/2057'; route.fullPath = '/tickets/2057';
  const item = approval('global');
  let selectedDecision;
  const sharedInbox = vue.reactive({ open: true, selected: item, availableItems: [item], items: [item], count: 1,
    error: '', notice: '', busy: '', loading: false, isCurrent: () => true, refresh: async () => true,
    later() { this.open = false; }, show() { this.open = true; }, select(value) { this.selected = value; },
    decide: async (entry, approved, reason) => { selectedDecision = { id: entry.id, approved, reason }; },
  });
  const card = componentFixture('components/automation/ApprovalCard.vue', { approval: item, available: true });
  const modalStub = { props: ['title'], setup: (props, { slots }) => () => vue.h('section', { role: 'dialog', 'aria-label': props.title }, [slots.default?.(), slots.footer?.()]) };
  const global = componentFixture('components/automation/GlobalApprovalInbox.vue', {}, {
    '@/stores/approval-inbox': { useApprovalInboxStore: () => sharedInbox }, '@/components/BaseModal.vue': modalStub,
    './ApprovalCard.vue': card.component,
  });
  const topbar = componentFixture('components/GlobalTopbar.vue', { isAdmin: true }, {
    '@/stores/approval-inbox': { useApprovalInboxStore: () => sharedInbox },
    '@/data/navigation': { navigationFor: () => ({ group: '运维', label: '工单中心' }) },
  });
  try {
    const bar = await topbar.html();
    assert.match(bar, /aria-haspopup="dialog"/);
    assert.ok(bar.includes('待处理审批 1 项'));
    const html = await global.html();
    assert.ok(html.includes('6379'));
    assert.ok(html.includes('稍后处理'));
    assert.match(html, /<details class="approval-technical">/);
    await global.state.decide({ approved: true, reason: '已核对当前隔离目标' });
    assert.deepEqual(selectedDecision, { id: 'global', approved: true, reason: '已核对当前隔离目标' });
    sharedInbox.error = 'network unavailable';
    assert.ok((await topbar.html()).includes('待审批数据未能更新'));
    route.fullPath = '/operations';
    await vue.nextTick();
    assert.equal(sharedInbox.open, false, 'Following a page link closes the global dialog without removing the inbox entry');
    assert.equal(sharedInbox.count, 1);
  } finally { global.stop(); topbar.stop(); card.stop(); }
}
console.log('PASS real GlobalTopbar and GlobalApprovalInbox rendering off automation, shared summary, decision event and navigation dismissal');
