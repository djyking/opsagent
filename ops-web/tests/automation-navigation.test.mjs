import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const { createRouter, createMemoryHistory } = require('vue-router');
const compiler = require('vue/compiler-sfc');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports = {}) {
  const js = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true,
  } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => {
    if (Object.hasOwn(imports, id)) return imports[id];
    if (id.endsWith('.vue')) return { render: () => null };
    if (id.endsWith('.css')) return {};
    return require(id);
  }, module, module.exports);
  return module.exports;
}
const { descriptor, errors } = compiler.parse(read('views/AutomationView.vue'), { filename: 'AutomationView.vue' });
assert.deepEqual(errors, []);
const script = compiler.compileScript(descriptor, { id: 'automation-navigation-test' });
const template = compiler.compileTemplate({ source: descriptor.template.content, filename: 'AutomationView.vue',
  id: 'automation-navigation-test', compilerOptions: { bindingMetadata: script.bindings } });
assert.deepEqual(template.errors, []);
// Inspect the compiled message branches without mounting unrelated components or form directives.
const render = evaluate(template.code, { vue: { ...vue, resolveComponent: () => ({ render: () => null }),
  withDirectives: node => node } }).render;
const presentation = evaluate(read('utils/automation-presentation.ts'));
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function settle() { await vue.nextTick(); await new Promise(setImmediate); await vue.nextTick(); }
function run(id) { return { id, ownerId: 7, status: 'COMPLETED', nodeId: 'end', approvals: [],
  state: { ticketId: 2057, ticketResolved: false }, snapshot: { graph: { nodes: [], edges: [] } } }; }
async function fixture(url, overrides = {}, visitor = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/automation', component: { render: () => null } }] });
  await router.push(url); await router.isReady();
  const route = vue.reactive({ ...router.currentRoute.value });
  const removeRouteListener = router.afterEach(to => Object.assign(route, to));
  const calls = [], reads = [];
  const implementation = {
    models: async () => ({ models: [] }), definitions: async () => [], tools: async () => ({ limits: {}, tools: [], approvalRequired: [] }),
    target: async code => ({ targetCode: code, configured: true, canStart: true, status: 'BASELINE', business: { httpStatus: 200 } }),
    scenarios: async () => ({ incidents: [] }), runs: async () => ({ items: [], total: 0 }),
    run: async id => run(id), events: async () => [], ...overrides,
  };
  const api = {
    models: () => { reads.push('models'); return implementation.models(); },
    definitions: () => { reads.push('definitions'); return implementation.definitions(); },
    tools: () => { reads.push('tools'); return implementation.tools(); },
    target: code => { reads.push('target'); return implementation.target(code); },
    scenarios: code => { reads.push('scenarios'); return implementation.scenarios(code); },
    runs: (page, filters) => { reads.push('runs'); return implementation.runs(page, filters); },
    run: id => { calls.push(['run', id]); return implementation.run(id); },
    events: (id, after) => { calls.push(['events', id, after]); return implementation.events(id, after); },
    start: code => { calls.push(['start', code]); return implementation.start(code); },
    takeoverPreview: id => implementation.takeoverPreview(id),
    takeover: (id, input) => implementation.takeover(id, input),
  };
  const component = evaluate(script.content, {
    vue: { ...vue, onMounted() {}, onBeforeUnmount() {} },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    '@lucide/vue': new Proxy({}, { get: () => ({ render: () => null }) }),
    '@/stores/auth': { useAuthStore: () => ({ user: { userId: 7, roles: [visitor ? 'DEMO' : 'ADMIN'] }, isAdmin: !visitor, isOps: !visitor, isDemo: visitor }) },
    '@/stores/approval-inbox': { useApprovalInboxStore: () => ({ decisionVersion: 0, isCurrent: () => true }) },
    '@/api/automation': { automationApi: api }, '@/utils/automation-presentation': presentation,
    '@/api/modules': { ticketApi: { detail: async id => ({ id }) } },
  }).default;
  const scope = vue.effectScope();
  const state = scope.run(() => component.setup({}, { expose() {} }));
  return { state, calls, reads, router, route, stop() { scope.stop(); removeRouteListener(); } };
}
function visibleErrors(state) {
  const messages = [];
  function visit(node) {
    if (!node || typeof node !== 'object') return;
    if (node.type === state.InlineError) messages.push(node.props.message);
    if (Array.isArray(node.children)) node.children.forEach(visit);
  }
  visit(render({}, [], {}, vue.proxyRefs(state), {}, {}));
  return messages;
}

// An explicit secondary tab survives a refresh even with a retained run-list filter or old run identifier.
for (const tab of ['inspection', 'workflows', 'tools', 'experience', 'runs']) {
  const app = await fixture(`/automation?tab=${tab}&ticketId=2057`);
  try { await app.state.initial(); assert.equal(app.state.tab.value, tab); }
  finally { app.stop(); }
}
{
  const app = await fixture('/automation?tab=inspection&ticketId=2057&run=previous-run');
  try {
    await app.state.initial(); assert.equal(app.state.tab.value, 'inspection');
    assert.equal(app.calls.length, 0, 'An explicit non-run tab must not open a stale deep-link run');
    await app.router.replace('/automation?tab=inspection&ticketId=2058&run=another-run'); await settle();
    assert.equal(app.state.tab.value, 'inspection'); assert.equal(app.calls.length, 0);
  } finally { app.stop(); }
}
console.log('PASS real AutomationView initialization and same-component URL updates preserve explicit tabs over retained filters/runs');

for (const outcome of ['fulfilled', 'rejected']) {
  const pending = deferred();
  const app = await fixture('/automation?run=old-run', { run: () => pending.promise });
  try {
    const initial = app.state.initial();
    assert.deepEqual(app.calls[0], ['run', 'old-run']);
    app.state.selectTab('inspection'); await settle();
    assert.equal(app.state.tab.value, 'inspection'); assert.equal(app.router.currentRoute.value.query.run, undefined);
    if (outcome === 'fulfilled') pending.resolve(run('old-run')); else pending.reject(Error('obsolete request failed'));
    await initial; await settle();
    assert.equal(app.state.tab.value, 'inspection'); assert.equal(app.state.detail.value, undefined);
    assert.equal(app.router.currentRoute.value.query.tab, 'inspection'); assert.equal(app.router.currentRoute.value.query.run, undefined);
    assert.equal(app.state.error.value, '', 'A cancelled read must not add an error to the newly selected workspace');
  } finally { app.stop(); }
}
console.log('PASS late successful and failed deep-link requests cannot replace a user-selected tab or its URL');

for (const resource of ['runs', 'definitions']) {
  const pending = deferred();
  const app = await fixture('/automation?tab=runs', { [resource]: () => pending.promise });
  try {
    const initial = app.state.initial(); await settle();
    app.state.selectTab('inspection'); await settle();
    pending.reject(Error(`obsolete ${resource} request failed`));
    await initial; await settle();
    assert.equal(app.state.error.value, '', 'An obsolete list/catalog failure must not appear in the newly selected tab');
  } finally { app.stop(); }
}
console.log('PASS obsolete operational and catalog read failures stay out of the current tab');

{
  const old = deferred();
  const app = await fixture('/automation?tab=runs&run=old-run', { run: id => id === 'old-run' ? old.promise : Promise.resolve(run(id)) });
  try {
    const initial = app.state.initial();
    await app.router.replace('/automation?tab=tools'); await settle();
    assert.equal(app.state.tab.value, 'tools');
    await app.state.selectRun('new-run'); await settle();
    old.resolve(run('old-run')); await initial; await settle();
    assert.equal(app.state.detail.value.id, 'new-run'); assert.equal(app.state.tab.value, 'runs');
    assert.equal(app.router.currentRoute.value.query.run, 'new-run'); assert.equal(app.router.currentRoute.value.query.tab, 'runs');
  } finally { app.stop(); }
}
console.log('PASS route-driven tab changes cancel old selection; deliberate later run selection writes a consistent runs deep link');

// Initial terminal reads fetch beyond the backend's 200-event page; explicit refresh can read late receipts.
{
  let receiptAvailable = false;
  const app = await fixture('/automation?run=full', { events: async (_id, after = 0) => after === 0 ? Array.from({ length: 200 }, (_, i) => ({ id: i + 1, type: 'NODE_COMPLETED', nodeId: 'diagnose', payload: {} })) : after === 200 ? [{ id: 201, type: 'TOOL_INTENT', nodeId: 'repair', payload: { id: 'write1', name: 'config_change_apply' } }] : receiptAvailable ? [{ id: 202, type: 'TOOL_OBSERVATION', nodeId: 'repair', payload: { call: { id: 'write1', name: 'config_change_apply' }, result: { operation: { status: 'APPLIED' } } } }] : [] });
  try {
    await app.state.initial();
    assert.equal(app.state.events.value.length, 201);
    assert.equal(app.state.eventsComplete.value, true);
    assert.equal(app.state.writeSummary.value.uncertain, 1);
    receiptAvailable = true;
    await app.state.refreshSelectedRun(true);
    assert.equal(app.state.events.value.length, 202);
    assert.equal(app.state.writeSummary.value.applied, 1);
    assert.equal(app.state.writeSummary.value.uncertain, 0);
  } finally { app.stop(); }
}
console.log('PASS paginated terminal histories and explicit late-receipt refresh');

// Optional demo services must not make the run/approval workspace look broken.
{
  let configured = false;
  let scenesAvailable = false;
  const app = await fixture('/automation', {
    target: async () => { if (!configured) throw Error('DEMO_TARGET_NOT_CONFIGURED');
      return { configured: true, canStart: true, status: 'BASELINE', business: { httpStatus: 200 } }; },
    scenarios: async () => { if (!scenesAvailable) throw Error('场景服务请求超时'); return { incidents: [] }; },
    runs: async () => ({ items: [{ id: 'working-run', status: 'RUNNING' }], total: 1 }),
  });
  try {
    await app.state.initial();
    assert.equal(app.state.error.value, '');
    assert.equal(app.state.runs.value[0].id, 'working-run');
    assert.equal(app.state.targetError.value, '', 'Run tab does not read the unrelated demo target');
    assert.equal(app.state.scenariosError.value, '', 'Run tab does not read unrelated scenarios');
    assert.deepEqual(visibleErrors(app.state), [], 'Run workspace must not render optional demo errors');
    app.state.selectTab('experience'); await settle(); await app.state.refreshCurrentTab();
    assert.match(app.state.targetError.value, /演练目标尚未配置/);
    assert.deepEqual(visibleErrors(app.state), app.state.experienceErrors.value);
    assert.equal(visibleErrors(app.state).length, 2);
    configured = true; await app.state.refresh();
    assert.equal(app.state.targetError.value, '');
    assert.equal(app.state.scenariosError.value, '演练场景读取失败：场景服务请求超时');
    scenesAvailable = true; await app.state.refresh();
    assert.deepEqual(visibleErrors(app.state), []);
  } finally { app.stop(); }
}
console.log('PASS optional demo failures appear only in the demo tab, retain real causes and clear independently on success');

for (const failure of ['runs', 'run']) {
  const app = await fixture(failure === 'run' ? '/automation?run=failed-run' : '/automation', {
    target: async () => { throw Error('DEMO_TARGET_NOT_CONFIGURED'); },
    [failure]: async () => { throw Error(failure === 'runs' ? '运行列表加载失败' : '运行详情加载失败'); },
  });
  try {
    await app.state.initial();
    assert.equal(app.state.error.value, failure === 'runs' ? '运行列表加载失败' : '运行详情加载失败');
    assert.deepEqual(visibleErrors(app.state), [app.state.error.value]);
  } finally { app.stop(); }
}
console.log('PASS real run-list and run-detail failures remain global errors even when demo configuration is missing');

for (const outcome of ['fulfilled', 'rejected']) {
  const old = deferred();
  const app = await fixture('/automation?tab=experience', {
    target: code => code === 'ops-demo-order-service' ? old.promise : Promise.resolve({
      targetCode: code, configured: true, canStart: true, status: 'BASELINE', business: { httpStatus: 200 },
    }),
    scenarios: async code => { if (code === 'ops-demo-order-service') throw Error('旧目标场景读取失败');
      return { incidents: [] }; },
  });
  try {
    const oldRefresh = app.state.refresh('experience');
    await app.state.selectTarget('ops-demo-notification-service');
    if (outcome === 'fulfilled') old.resolve({ targetCode: 'ops-demo-order-service', configured: false });
    else old.reject(Error('DEMO_TARGET_NOT_CONFIGURED'));
    await oldRefresh;
    assert.equal(app.state.target.value.targetCode, 'ops-demo-notification-service');
    assert.deepEqual(app.state.experienceErrors.value, []);
    assert.equal(app.state.error.value, '');
  } finally { app.stop(); }
}
console.log('PASS switching targets discards obsolete successful data and failed demo hints');

{
  const oldRuns = deferred();
  const app = await fixture('/automation?tab=experience', {
    scenarios: async code => ({ incidents: code === 'ops-demo-order-service' ? [{ incidentId: 'old-incident' }] : [] }),
    runs: async (_page, filters) => filters?.incidentId === 'old-incident' ? oldRuns.promise : { items: [], total: 0 },
  });
  try {
    const oldRefresh = app.state.refresh('experience'); await settle();
    assert.equal(app.state.trackedIncidentId.value, 'old-incident');
    await app.state.selectTarget('ops-demo-notification-service');
    oldRuns.reject(Error('旧演练运行读取失败'));
    await oldRefresh;
    assert.equal(app.state.error.value, '');
    assert.equal(app.state.trackedIncidentId.value, '');
    assert.deepEqual(app.state.trackedRuns.value, []);
  } finally { app.stop(); }
}
console.log('PASS an obsolete tracked-run failure cannot leak into the newly selected target');

// A new visitor sees reviewed examples, while selecting one prepares impact confirmation only.
{
  const app = await fixture('/automation', { start: async code => ({ incidentId: 'visitor-new-incident', ownerId: 7, scenarioCode: code }) }, true);
  try {
    await app.state.initial();
    assert.equal(app.state.tab.value, 'cases');
    await app.state.prepareStart('RABBITMQ_CONSUMER_PAUSED', 'ops-demo-notification-service');
    assert.equal(app.state.tab.value, 'experience');
    assert.equal(app.state.targetCode.value, 'ops-demo-notification-service');
    assert.equal(app.state.startScenario.value, 'RABBITMQ_CONSUMER_PAUSED');
    assert.equal(app.calls.filter(call => call[0] === 'start').length, 0, 'Opening a historical case cannot inject a fault');
    await app.state.start(app.state.startScenario.value);
    assert.equal(app.calls.filter(call => call[0] === 'start').length, 1);
    assert.equal(app.state.trackedIncidentId.value, 'visitor-new-incident');
    assert.equal(app.router.currentRoute.value.query.incidentId, 'visitor-new-incident');
    assert.equal(app.state.startScenario.value, '');
  } finally { app.stop(); }
}
{
  const app = await fixture('/automation?tab=experience&scenario=NACOS_REDIS_CONFIG_DRIFT', {}, true);
  try {
    await app.state.initial();
    assert.equal(app.state.startScenario.value, 'NACOS_REDIS_CONFIG_DRIFT');
    assert.equal(app.calls.filter(call => call[0] === 'start').length, 0, 'Scenario deep links require explicit confirmation');
  } finally { app.stop(); }
}
console.log('PASS visitor public-case default, scenario impact confirmation, and newly owned run tracking');

// Real API serialization must bind takeover to the reviewed incident/version and keep them on uncertain retries.
{
  const requests = [];
  let fail = true;
  const wireApi = evaluate(read('api/automation.ts'), { './http': { request: async config => {
    requests.push(structuredClone(config));
    if (fail) throw Error('response outcome unknown');
    return { id: 'recovery-run' };
  } } }).automationApi;
  const preview = { sourceRunId: 'source-run', originalOwnerId: -77, incidentId: 'incident-reviewed', targetCode: 'ops-demo-order-service', expectedRevision: 'revision-reviewed', canTakeover: true, reasonCode: 'READY', hint: '可接管' };
  const app = await fixture('/automation?tab=runs', { takeover: wireApi.takeover, takeoverPreview: async () => structuredClone(preview) });
  try {
    app.state.detail.value = { ...run('source-run'), state: { ticketId: 2057, incidentId: 'incident-reviewed', ticketResolved: false }, authorization: { canTakeover: true, canOperate: false } };
    app.state.takeoverReason.value = '核对现场后接管';
    assert.equal(app.state.canSubmitTakeover.value, false, 'No preview means no takeover');
    await app.state.takeover(); assert.equal(requests.length, 0);
    for (const invalid of [{ sourceRunId: 'other-run' }, { incidentId: 'other-incident' }, { expectedRevision: '' }, { targetCode: '' }, { canTakeover: false }]) {
      app.state.takeoverPreview.value = { ...preview, ...invalid };
      assert.equal(app.state.canSubmitTakeover.value, false);
      await app.state.takeover();
    }
    assert.equal(requests.length, 0);
    await app.state.prepareTakeover();
    app.state.takeoverReason.value = '核对现场后接管';
    assert.equal(app.state.canSubmitTakeover.value, true);
    app.state.busy.value = 'other-operation';
    assert.equal(app.state.canSubmitTakeover.value, false);
    app.state.busy.value = '';
    await app.state.takeover();
    assert.equal(requests.length, 1);
    assert.equal(requests[0].method, 'POST');
    assert.equal(requests[0].url, '/api/automation/runs/source-run/takeover');
    assert.deepEqual(Object.keys(requests[0].data).sort(), ['expectedRevision', 'incidentId', 'reason', 'requestId']);
    assert.equal(requests[0].data.incidentId, 'incident-reviewed');
    assert.equal(requests[0].data.expectedRevision, 'revision-reviewed');
    assert.equal(requests[0].data.reason, '核对现场后接管');
    assert.ok(requests[0].data.requestId);
    app.state.takeoverPreview.value = { ...preview, expectedRevision: 'revision-newer' };
    app.state.takeoverOpen.value = false;
    await app.state.prepareTakeover();
    assert.equal(app.state.takeoverAttempt.value.input.requestId, requests[0].data.requestId);
    fail = false;
    await app.state.takeover();
    assert.equal(requests.length, 2);
    assert.deepEqual(requests[1].data, requests[0].data, 'An uncertain retry must not silently change the reviewed revision or request identity');
    assert.equal(app.state.takeoverAttempt.value, undefined);
    assert.equal(app.state.takeoverOpen.value, false);
  } finally { app.stop(); }
}
console.log('PASS actual takeover HTTP payload, reviewed scope gates and stable incident/version/idempotency across uncertain retries');
for (const [tab, expected] of Object.entries({
  cases: ['runs'], runs: ['definitions', 'models', 'runs', 'tools'], experience: ['scenarios', 'target'],
  workflows: ['definitions'], tools: ['models', 'tools'], inspection: [],
})) {
  const app = await fixture(`/automation?tab=${tab}`);
  try {
    await app.state.initial(); assert.deepEqual([...app.reads].sort(), expected, `${tab} loads only its own sources`);
    const before = app.reads.length; await app.state.loadTabCatalog(tab); assert.equal(app.reads.length, before, 'Repeated tab entry reuses loaded catalog data');
    if (['tools', 'workflows', 'inspection'].includes(tab)) { await app.state.refresh(); assert.equal(app.reads.length, before, 'Static tab refresh does not poll demo targets or run lists'); }
  } finally { app.stop(); }
}
{
  const originalDocument = globalThis.document, originalNow = Date.now; let clock = originalNow();
  globalThis.document = { hidden: false }; Date.now = () => clock;
  const app = await fixture('/automation?tab=runs&run=active', {
    runs: async () => ({ items: [{ id: 'active', owner_id: 7, status: 'RUNNING' }], total: 1 }),
    run: async id => ({ ...run(id), status: 'RUNNING' }),
  });
  try {
    await app.state.initial(); assert.equal(app.state.pollingDelay(), 4000);
    const before = app.reads.length, detailBefore = app.calls.length; clock += 4000; await app.state.poll(); assert.equal(app.reads.length, before + 1, 'Active runs keep four-second refreshes');
    assert.equal(app.calls.length, detailBefore + 2, 'The selected active run and new approval/event data keep their four-second refresh');
    clock += 4000; await app.state.poll(); assert.equal(app.reads.length, before + 2);
    globalThis.document.hidden = true; clock += 60_000; await app.state.poll(); assert.equal(app.reads.length, before + 2);
    globalThis.document.hidden = false; await app.state.poll(true); assert.equal(app.reads.length, before + 3, 'Returning to a visible tab immediately refreshes active data');
    app.state.runs.value = []; app.state.detail.value = undefined; assert.equal(app.state.pollingDelay(), 30_000, 'Idle run polling backs off');
    assert.equal(app.state.modelStatus({ provider: 'KIMI', configured: false, toolCalling: false, verificationStatus: 'UNVERIFIED', configurationStatus: 'MISSING_API_KEY' }), '缺少 API 密钥');
    assert.equal(app.state.modelStatus({ provider: 'KIMI', configured: false, toolCalling: false, verificationStatus: 'UNVERIFIED', configurationStatus: 'INVALID_ENDPOINT' }), '模型地址无效', 'Other missing configuration is not falsely called a missing key');
  } finally { app.stop(); Date.now = originalNow; if (originalDocument === undefined) delete globalThis.document; else globalThis.document = originalDocument; }
}
console.log('PASS Automation active-tab data scope, catalog reuse, active/idle polling, hidden-tab pause and truthful Kimi configuration reasons');
