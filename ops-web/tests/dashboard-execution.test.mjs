import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
function evaluate(source, imports) {
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
const filename = 'OperationsDecision.vue';
const source = readFileSync(new URL('../src/components/dashboard/' + filename, import.meta.url), 'utf8');
const { descriptor, errors } = compiler.parse(source, { filename }); assert.deepEqual(errors, []);
const script = compiler.compileScript(descriptor, { id: 'dashboard-execution-test' });
const template = compiler.compileTemplate({ source: descriptor.template.content, filename, id: 'dashboard-execution-test',
  compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(template.errors, []);
const render = evaluate(template.code, { vue }).render;
const Icon = { render: () => vue.h('i') };
const icons = new Proxy({}, { get: () => Icon });
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function run(id, overrides = {}) {
  return { id, ownerId: 7, status: 'COMPLETED', nodeId: 'end', approvals: [],
    state: { ticketId: 2000 + Number(id), ticketResolved: true, recoveryVerification: { resolved: true, toStatus: 'RESOLVED' } }, ...overrides };
}
function summary(overrides = {}) { return { scope: 'OWNER', totalRuns: 200, pendingApprovals: 75, activeRuns: 8,
  statusCounts: { COMPLETED: 150, NEEDS_ATTENTION: 3, BUDGET_EXCEEDED: 2, CANCELLED: 8, PAUSED: 4 }, ...overrides }; }
function setup(overrides = {}) {
  const auth = vue.reactive({ user: { userId: 7 }, isAdmin: false });
  const inbox = vue.reactive({ decisionVersion: 0, count: 50, opens: 0, show() { this.opens++; } });
  const calls = [], mounted = [], unmount = [];
  const api = { summary: async () => summary(), runs: async () => ({ items: [], total: 200 }), run: async id => run(id), ...overrides };
  const component = evaluate(script.content, {
    vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn) }, '@lucide/vue': icons,
    '@/api/http': { request: ({ url }) => { assert.equal(url, '/api/automation/summary'); calls.push(['summary']); return api.summary(); } },
    '@/api/automation': { automationApi: { runs: page => { calls.push(['runs', page]); return api.runs(page); },
      run: id => { calls.push(['run', id]); return api.run(id); } } },
    '@/stores/auth': { useAuthStore: () => auth }, '@/stores/approval-inbox': { useApprovalInboxStore: () => inbox },
  }).default;
  const props = vue.reactive({ eventCount: 125, eventsKnown: true, eventsLoading: false, eventsStale: false });
  const scope = vue.effectScope(); const state = scope.run(() => component.setup(props, { expose() {} }));
  const ready = Promise.all(mounted.map(fn => fn()));
  return { state, api, auth, inbox, props, calls, ready, stop() { unmount.forEach(fn => fn()); scope.stop(); }, async html() {
    const context = vue.proxyRefs({ ...state, ...props });
    const app = vue.createSSRApp({ render: () => render(context, [], props, context, {}, {}) });
    app.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', {
      href: typeof p.to === 'string' ? p.to : p.to.path + '?run=' + p.to.query.run,
    }, slots.default?.()) });
    return renderToString(app);
  } };
}

// Aggregate counts are permission-scoped server totals; recovery is a separately bounded evidence sample.
{
  const samples = [run('0'), run('1', { state: { ticketId: 2001, ticketResolved: false, recoveryVerification: { resolved: true, toStatus: 'RESOLVED' } } }),
    run('2', { state: { ticketId: 2002, ticketResolved: true, recoveryVerification: { resolved: false, toStatus: 'RESOLVED' } } }),
    run('3', { state: { ticketId: 2003, ticketResolved: true, recoveryVerification: { resolved: true, toStatus: 'PROCESSING' } } }),
    run('4', { status: 'NEEDS_ATTENTION' }), run('5', { state: { ticketId: 2005, ticketResolved: 'true', recoveryVerification: { resolved: true, toStatus: 'RESOLVED' } } }),
    run('6', { state: { ticketId: 2006, ticketResolved: true, recoveryVerification: { resolved: 1, toStatus: 'RESOLVED' } } }),
    run('7', { state: { ticketId: 2007, ticketResolved: true } }), run('8', { status: 'CANCELLED' }),
    run('9', { state: { ticketId: 2009, ticketResolved: true, recoveryVerification: { resolved: true, toStatus: 'CLOSED' } } }), run('10')];
  const app = setup({ runs: async () => ({ items: samples, total: 200 }), run: async id => samples.find(item => item.id === id) });
  try {
    await app.ready; assert.equal(app.state.problemCount.value, 5); assert.equal(app.state.summary.value.pendingApprovals, 75);
    assert.equal(app.state.recentRuns.value.length, 10); assert.deepEqual(app.state.strictRecoveries.value.map(item => item.id), ['0', '9']);
    assert.equal(app.calls.filter(call => call[0] === 'run').length, 10); assert.equal(app.state.recoveryCount.value, 2);
    const html = await app.html(); assert.match(html, /最近 10 次可见运行/); assert.match(html, /共 200 次/);
    assert.match(html, /<strong>75<\/strong>/); assert.match(html, /<strong>125<\/strong>/); assert.match(html, /\/automation\?run=9/);
    assert.ok(!html.includes('成功率')); assert.equal(app.state.recentProblems.value.length, 1);
    app.state.approvals.show(); assert.equal(app.inbox.opens, 1, 'The card opens the existing approval inbox rather than creating a second approval flow');
  } finally { app.stop(); }
}
console.log('PASS real dashboard summary: full scoped totals, at most ten details, strict recovery truth and existing approval inbox');

{
  const app = setup({ runs: async () => ({ items: [run('1')], total: 1 }), run: async () => { throw new Error('unavailable'); } });
  try {
    await app.ready; assert.equal(app.state.missingDetails.value, 1); assert.equal(app.state.recoveryCount.value, '部分待确认');
    assert.match(await app.html(), /恢复证据待确认，不按零次恢复统计/); assert.equal(app.state.problemCount.value, 5);
    app.api.run = async () => run('wrong-id'); await app.state.load(); assert.equal(app.state.missingDetails.value, 1);
    assert.equal(app.state.strictRecoveries.value.length, 0, 'A response for another run cannot verify the requested event');
  } finally { app.stop(); }
  const failed = setup({ summary: async () => { throw new Error('offline'); }, runs: async () => { throw new Error('offline'); } });
  try {
    await failed.ready; assert.equal(failed.state.summaryReady.value, false); assert.equal(failed.state.recoveryCount.value, '未确认');
    assert.match(await failed.html(), /全量运行范围尚未确认/);
  } finally { failed.stop(); }
}
console.log('PASS unavailable/mismatched details remain unknown; sample failures never become zero verified recovery');

{
  const app = setup({ runs: async () => ({ items: [run('1')], total: 1 }) });
  try {
    await app.ready; assert.equal(app.state.recoveryCount.value, 1);
    const pending = deferred(); app.api.run = () => pending.promise;
    const refresh = app.state.load(); await Promise.resolve(); await Promise.resolve();
    assert.equal(app.state.recoveryCount.value, '核对中'); assert.ok(!(await app.html()).includes('最新恢复验证已通过'));
    pending.reject(new Error('lost detail')); await refresh;
    assert.equal(app.state.recoveryCount.value, '部分待确认'); assert.equal(app.state.strictRecoveries.value.length, 0);
  } finally { app.stop(); }
}
console.log('PASS refreshing evidence cannot show stale verified recovery against a newer sample');

{
  const oldSummary = deferred(), oldRows = deferred();
  const app = setup({ summary: () => oldSummary.promise, runs: () => oldRows.promise });
  try {
    app.api.summary = async () => summary({ scope: 'ADMIN', totalRuns: 3, pendingApprovals: 1 });
    app.api.runs = async () => ({ items: [], total: 3 });
    app.auth.user = { userId: 8 }; app.auth.isAdmin = true;
    for (let index = 0; index < 8; index++) await Promise.resolve();
    oldSummary.resolve(summary({ totalRuns: 900 })); oldRows.resolve({ items: [run('7')], total: 900 }); await app.ready;
    assert.equal(app.state.summary.value.totalRuns, 3); assert.equal(app.state.summary.value.scope, 'ADMIN');
    assert.equal(app.state.recentRuns.value.length, 0); assert.match(await app.html(), /管理员可见的全部运行/);
    app.auth.user = null; assert.equal(app.state.summary.value, undefined); assert.equal(app.state.strictRecoveries.value.length, 0);
  } finally { app.stop(); }
}
console.log('PASS actor/role changes discard prior summary and details rather than leaking another scope');
