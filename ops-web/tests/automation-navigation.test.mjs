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
  const implementation = { run: async id => run(id), events: async () => [], ...overrides };
  const api = {
    models: async () => ({ models: [] }), definitions: async () => [], tools: async () => ({}),
    target: async () => ({ configured: true, canStart: true, status: 'BASELINE', business: { httpStatus: 200 } }),
    scenarios: async () => ({ incidents: [] }), runs: async () => ({ items: [], total: 0 }),
    run: id => { calls.push(['run', id]); return implementation.run(id); },
    events: id => { calls.push(['events', id]); return implementation.events(id); },
  };
  const component = evaluate(script.content, {
    vue: { ...vue, onMounted() {}, onBeforeUnmount() {} },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    '@lucide/vue': new Proxy({}, { get: () => ({ render: () => null }) }),
    '@/stores/auth': { useAuthStore: () => ({ user: { userId: 7, roles: ['ADMIN'] }, isAdmin: true, isOps: true, isDemo: false }) },
    '@/stores/approval-inbox': { useApprovalInboxStore: () => ({ decisionVersion: 0, isCurrent: () => true }) },
    '@/api/automation': { automationApi: api }, '@/utils/automation-presentation': presentation,
  }).default;
  const scope = vue.effectScope();
  const state = scope.run(() => component.setup({}, { expose() {} }));
  return { state, calls, router, route, stop() { scope.stop(); removeRouteListener(); } };
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
