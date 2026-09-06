import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
const read = name => readFileSync(new URL('../src/' + name, import.meta.url), 'utf8');
function evaluate(source, imports = {}) {
  const module = { exports: {} };
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function flush() { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); }
function snapshot(extra = {}) { return { schemaVersion: 1, ticketId: 2064, incidentId: 'incident-1', targetCode: 'ops-demo-order-service',
  generatedAt: new Date().toISOString(), stage: { code: 'VERIFYING', label: '等待业务验证', basis: '已执行动作，等待真实观测' },
  access: { runScope: 'OWNER', operationalEvidence: true, notice: '' }, actions: { canDiagnose: true, reason: '', definitionId: 'isolated-recovery' },
  facts: [{ id: 'fact-1', label: '业务响应', value: '503 <script>unsafe</script>', source: 'HTTP_PROBE', observedAt: null, scope: 'CURRENT' }],
  hypotheses: [{ id: 'cause-1', title: '连接配置待核对', reason: '仍缺少旧版本', evidenceIds: ['fact-1'], source: 'MODEL', runId: 'run-1' }],
  gaps: [{ id: 'gap-1', message: '缺少业务连续成功记录', source: 'VERIFICATION' }], changes: [],
  verification: { status: 'BUSINESS_PENDING', label: '业务恢复尚未确认', scope: 'CURRENT', source: 'MANUAL', observedAt: null,
    incidentMatched: true, businessHealthy: false, consecutiveSuccesses: 0, alertResolved: false, agentAttributed: false },
  runs: [{ id: 'run-1', status: 'COMPLETED', nodeId: 'end', createdAt: '2026-09-06T06:00:00Z', updatedAt: null, ticketResolved: true, message: '流程结束，验证未通过' }],
  runTotal: 1, latestRunId: 'run-1', pendingApprovalIds: [], diagnosis: { summary: '配置是候选原因', knownFacts: [], candidateCauses: [], evidenceGaps: [], recordedAt: null, runId: 'run-1' },
  sources: [{ name: '变更记录', status: 'UNAVAILABLE', message: '未能读取，不代表没有变更' }], limits: { runs: 6, changes: 12 }, ...extra }; }

// Actual state manager: stale reads from another account/event can never replace the selected evidence.
{
  const input = vue.reactive({ id: 2064, identity: 'actor-1' });
  const old = deferred(); const newer = deferred();
  let impl = () => old.promise;
  const scope = vue.effectScope();
  const module = evaluate(read('composables/useEventWorkspace.ts'), { vue, '@/api/event-workspace': { eventWorkspaceApi: { read: id => impl(id) } } });
  const manager = scope.run(() => module.useEventWorkspace(() => input.id, () => input.identity));
  try {
    const pending = manager.load();
    impl = () => newer.promise; input.id = 2065;
    newer.resolve(snapshot({ ticketId: 2065 })); await flush();
    old.resolve(snapshot()); await pending; await flush();
    assert.equal(manager.data.value.ticketId, 2065);
    const late = deferred(); impl = () => late.promise;
    const reload = manager.load(); input.identity = ''; assert.equal(manager.data.value, undefined);
    late.resolve(snapshot({ ticketId: 2065 })); await reload; assert.equal(manager.data.value, undefined);
    impl = async () => snapshot(); input.identity = 'actor-2'; await flush();
    assert.equal(manager.data.value, undefined); assert.match(manager.error.value, /不匹配/);
    impl = async () => snapshot({ ticketId: 2065 }); await manager.load(); assert(manager.data.value);
    impl = async () => { throw Error('network unavailable'); }; await manager.load();
    assert.equal(manager.data.value, undefined); assert.match(manager.error.value, /network/);
  } finally { manager.dispose(); scope.stop(); }
}
console.log('PASS event/account switching, late response discard, wrong event rejection and failed evidence read');

const presentation = evaluate(read('utils/event-workspace.ts'));
{
  const data = snapshot();
  assert.equal(presentation.currentlyVerified(data), false, 'COMPLETED run and ticketResolved do not prove business recovery');
  data.verification = { ...data.verification, status: 'RECOVERED', observedAt: new Date().toISOString(), businessHealthy: true, alertResolved: true, consecutiveSuccesses: 3 };
  assert.equal(presentation.currentlyVerified(data), true);
  for (const changed of [{ observedAt: '2020-01-01T00:00:00Z' }, { scope: 'HISTORICAL' }, { incidentMatched: false }, { status: 'STALE' }, { consecutiveSuccesses: 2 }, { businessHealthy: false }, { alertResolved: false }])
    assert.equal(presentation.currentlyVerified({ ...data, verification: { ...data.verification, ...changed } }), false);
  data.verification.scope = 'HISTORICAL'; data.verification.source = 'TTL_GUARD';
  const draft = presentation.eventRetrospective(data, '订单事件');
  assert.match(draft, /历史恢复记录，不代表当前健康/); assert.match(draft, /到期保护恢复/);
  assert.match(draft, /配置是候选原因/); assert.match(draft, /不等于已证明因果关系/);
}
console.log('PASS current versus historical verification and retrospective provenance without invented causes');

const Stub = { setup: (_, { slots }) => () => vue.h('section', slots.default?.()) };
const icons = new Proxy({}, { get: () => ({ render: () => vue.h('svg') }) });
function fixture() {
  const source = read('components/events/EventWorkspace.vue');
  const { descriptor, errors } = compiler.parse(source); assert.deepEqual(errors, []);
  const script = compiler.compileScript(descriptor, { id: 'event-workspace-test' });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename: 'EventWorkspace.vue', id: 'event-workspace-test', compilerOptions: { bindingMetadata: script.bindings } });
  assert.deepEqual(template.errors, []);
  const stateManager = { data: vue.ref(snapshot()), loading: vue.ref(false), error: vue.ref(''), load: async () => {}, dispose() {} };
  const auth = vue.reactive({ user: { userId: 7, roles: ['ADMIN'] }, isAdmin: true, isDemo: false });
  const inbox = vue.reactive({ availableItems: [], decisionVersion: 0, busy: '', isCurrent: () => true, refresh: async () => true });
  const calls = []; let impl = async () => ({ id: 'new-run' });
  const api = { create: (...args) => { calls.push(args); return impl(...args); }, models: async () => ({ models: [] }), definitions: async () => [] };
  const imports = { vue: { ...vue, onMounted() {}, onBeforeUnmount() {} }, '@lucide/vue': icons,
    '@/api/automation': { automationApi: api }, '@/composables/useEventWorkspace': { useEventWorkspace: () => stateManager },
    '@/utils/event-workspace': presentation, '@/stores/auth': { useAuthStore: () => auth }, '@/stores/approval-inbox': { useApprovalInboxStore: () => inbox },
    '@/api/http': { request: async () => { throw Error('Unexpected mutation'); } }, '@/styles/pages/event-workspace.css': {} };
  for (const name of ['BaseModal', 'FormField', 'InlineError', 'LoadingState']) imports[`@/components/${name}.vue`] = Stub;
  imports['@/components/automation/ApprovalCard.vue'] = { props: ['approval'], setup: p => () => vue.h('article', p.approval.id) };
  const component = evaluate(script.content, imports).default;
  const render = evaluate(template.code, { vue }).render;
  const props = vue.reactive({ ticket: { id: 2064, title: '订单异常', version: 1, creatorId: 7 } });
  const scope = vue.effectScope();
  const state = scope.run(() => component.setup(props, { expose() {}, emit() {} }));
  return { state, stateManager, auth, inbox, calls, setImpl(value) { impl = value; }, stop: () => scope.stop(), async html() {
    const context = vue.proxyRefs({ ...state, ...props });
    const app = vue.createSSRApp({ render: () => render(context, [], props, context, {}, {}) });
    app.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', { href: typeof p.to === 'string' ? p.to : p.to.path }, slots.default?.()) });
    return renderToString(app);
  } };
}
{
  const app = fixture();
  try {
    app.stateManager.data.value.pendingApprovalIds = ['approval-own', 'approval-other'];
    app.inbox.availableItems = [{ id: 'approval-own', revision: 1, ticketId: 2064 }, { id: 'approval-other', revision: 1, ticketId: 2065 }];
    const html = await app.html();
    for (const text of ['已取得的事实', '诊断判断与证据缺口', '本事件关联的变更', '业务恢复尚未确认', '候选原因', '仍需核对', 'approval-own']) assert(html.includes(text));
    assert(!html.includes('approval-other')); assert(!html.includes('<script>unsafe</script>')); assert(html.includes('&lt;script&gt;'));
    assert.match(html, /<details class="event-sources">/);
    app.stateManager.data.value.verification = { ...app.stateManager.data.value.verification, scope: 'HISTORICAL', status: 'HISTORICAL_RECOVERY', label: '历史恢复已记录' };
    assert((await app.html()).includes('历史结论，不代表目标当前健康'));
  } finally { app.stop(); }
}
console.log('PASS actual event component rendering, cross-event approval isolation and escaped evidence');
{
  const app = fixture();
  try {
    app.state.models.value = [{ provider: 'DEEPSEEK', configured: true, toolCalling: true }];
    app.state.definitions.value = [{ id: 'isolated-recovery', published_version: 1 }];
    app.state.model.value = 'DEEPSEEK'; app.state.definition.value = 'isolated-recovery';
    const pending = deferred(); app.setImpl(() => pending.promise);
    const first = app.state.createRun(); await app.state.createRun(); assert.equal(app.calls.length, 1);
    pending.reject(Error('connection lost after submission')); await first;
    assert.equal(app.state.createUnconfirmed.value, true);
    app.setImpl(async () => ({ id: 'same-run' })); await app.state.createRun();
    assert.deepEqual(app.calls[0], app.calls[1], 'An unconfirmed creation reuses exact parameters and request identity');
    assert.equal(app.state.createUnconfirmed.value, false);
    app.setImpl(async () => { throw Object.assign(Error('模型尚未通过验证'), { status: 400, code: 40000 }); });
    await app.state.createRun(); assert.equal(app.state.createUnconfirmed.value, false, 'Definite validation rejection permits correcting model/workflow');
    app.state.createOpen.value = true; app.state.draft.value = 'private draft'; app.auth.user.userId = 8;
    assert.equal(app.state.createOpen.value, false); assert.equal(app.state.draft.value, '');
  } finally { app.stop(); }
}
console.log('PASS actual event start action duplicate exclusion, exact retry identity and identity-change cleanup');
