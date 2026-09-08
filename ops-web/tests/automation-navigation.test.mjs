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
async function fixture(url, overrides = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/automation', component: { render: () => null } }] });
  await router.push(url); await router.isReady();
  const route = vue.reactive({ ...router.currentRoute.value });
  const removeRouteListener = router.afterEach(to => Object.assign(route, to));
  const calls = [];
  const implementation = {
    target: async code => ({ targetCode: code, configured: true, canStart: true, status: 'BASELINE', business: { httpStatus: 200 } }),
    scenarios: async () => ({ incidents: [] }), runs: async () => ({ items: [], total: 0 }),
    run: async id => run(id), events: async () => [], ...overrides,
  };
  const api = {
    models: async () => ({ models: [] }), definitions: async () => [], tools: async () => ({}),
    target: code => implementation.target(code), scenarios: code => implementation.scenarios(code),
    runs: (page, filters) => implementation.runs(page, filters),
    run: id => { calls.push(['run', id]); return implementation.run(id); },
    events: (id, after) => { calls.push(['events', id, after]); return implementation.events(id, after); },
  };
  const component = evaluate(script.content, {
    vue: { ...vue, onMounted() {}, onBeforeUnmount() {} },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    '@lucide/vue': new Proxy({}, { get: () => ({ render: () => null }) }),
    '@/stores/auth': { useAuthStore: () => ({ user: { userId: 7, roles: ['ADMIN'] }, isAdmin: true, isOps: true, isDemo: false }) },
    '@/stores/approval-inbox': { useApprovalInboxStore: () => ({ decisionVersion: 0, isCurrent: () => true }) },
    '@/api/automation': { automationApi: api }, '@/utils/automation-presentation': presentation,
    '@/api/modules': { ticketApi: { detail: async id => ({ id }) } },
  }).default;
  const scope = vue.effectScope();
  const state = scope.run(() => component.setup({}, { expose() {} }));
  return { state, calls, router, route, stop() { scope.stop(); removeRouteListener(); } };
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
    assert.match(app.state.targetError.value, /演练目标尚未配置/);
    assert.equal(app.state.scenariosError.value, '演练场景读取失败：场景服务请求超时');
    assert.deepEqual(visibleErrors(app.state), [], 'Run workspace must not render optional demo errors');
    app.state.selectTab('experience'); await settle();
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
    const oldRefresh = app.state.refresh();
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
    const oldRefresh = app.state.refresh(); await settle();
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
