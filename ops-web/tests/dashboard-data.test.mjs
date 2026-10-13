import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
const filename = 'DashboardView.vue';
const source = readFileSync(new URL('../src/views/' + filename, import.meta.url), 'utf8');
const { descriptor, errors } = compiler.parse(source, { filename });
assert.deepEqual(errors, []);
const script = compiler.compileScript(descriptor, { id: 'dashboard-data-test' });
const template = compiler.compileTemplate({
  source: descriptor.template.content,
  filename,
  id: 'dashboard-data-test',
  compilerOptions: { bindingMetadata: script.bindings },
});
assert.deepEqual(template.errors, []);

function evaluate(sourceText, imports) {
  const js = ts.transpileModule(sourceText, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => imports[id] ?? require(id), module, module.exports);
  return module.exports;
}

// Render the real dashboard template. Child stubs expose their public text/props,
// without loading unrelated navigation, document APIs or a browser environment.
const SlotSurface = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.actions?.()]) };
const PageHeader = { props: ['title', 'description'], setup: (props, { slots }) => () => vue.h('header', [props.title, props.description, slots.actions?.()]) };
const EmptyState = { props: ['title', 'description'], setup: props => () => vue.h('div', { class: 'empty-state' }, [props.title, props.description]) };
const LoadingState = { props: ['text'], setup: props => () => vue.h('div', { class: 'loading-state' }, props.text) };
const ValueBadge = { props: ['value'], setup: props => () => vue.h('span', String(props.value)) };
const MetricStrip = {
  props: ['items'],
  setup: props => () => vue.h('section', props.items.map(item => vue.h(item.to ? 'a' : 'div', {
    'data-metric': item.key, href: item.to,
  }, [vue.h('span', item.label), vue.h('strong', String(item.value)), vue.h('small', item.meta)]))),
};
const RouterLink = { props: ['to'], setup: (props, { slots }) => () => vue.h('a', { href: props.to }, slots.default?.()) };
const render = evaluate(template.code, { vue }).render;

function ticket(id, overrides = {}) {
  return { id, ticketNo: `TEST-${id}`, title: `事件 ${id}`, description: '自动化测试样本', priority: 'LOW', status: 'CREATED', creatorId: 1, sourceType: 'MANUAL', version: 0, createTime: '2026-09-01T08:00:00', updateTime: '2026-09-01T08:00:00', ...overrides };
}
function sla(counts = {}) {
  return { counts: { total: 0, running: 0, risk: 0, dashboardRisk: 0, breached: 0, completed: 0, ...counts }, services: [], checkedAt: '2026-09-06T08:00:00' };
}
function monitor() {
  return { checkedAt: '2026-09-06T08:00:00', services: [{ job: 'test-service', health: 'up', lastScrape: '2026-09-06T08:00:00', scrapeUrl: 'http://test/metrics' }], prometheus: { healthy: true, targetCount: 1, upCount: 1 }, grafana: { healthy: true } };
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function setup(overrides = {}, isAdmin = true) {
  const mounted = [], unmounting = [], calls = [];
  const actor = vue.reactive({ isAdmin, user: { userId: 7, roles: isAdmin ? ['ADMIN'] : ['USER'] } });
  const inbox = vue.reactive({ count: 50, decisionVersion: 0, show() {} });
  const api = {
    tickets: async () => [],
    sla: async () => sla(),
    oncall: async () => ({ fallback: true, message: '当前无有效排班', members: [] }),
    monitor: async () => monitor(),
    alerts: async () => [],
    topology: async () => ({ nodes: [], edges: [], dataSources: [], checkedAt: '2026-09-07T08:00:00' }),
    approvals: async () => ({ pendingApprovals: 75 }),
    ...overrides,
  };
  const lifecycleVue = { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounting.push(fn) };
  const read = (key, details) => { calls.push({ key, ...details }); return api[key](details); };
  const imports = {
    vue: lifecycleVue,
    '@/components/observability/ServiceTopology.vue': SlotSurface,
    '@/utils/load-topology-graph': { loadTopologyGraphRuntime: async () => ({}) },
    '@/components/events/EventActivityStrip.vue': SlotSurface,
    '@/api/observability': { observabilityApi: { topology: (params, options) => read('topology', { params, options }) } },
    '@/stores/approval-inbox': { useApprovalInboxStore: () => inbox },
    '@/styles/pages/dashboard-overview.css': {},
    '@/utils/observability': evaluate(readFileSync(new URL('../src/utils/observability.ts', import.meta.url), 'utf8'), {}),
    '@/stores/auth': { useAuthStore: () => actor },
    '@/composables/usePageFeedback': { usePageFeedback: () => ({}) },
    '@/api/http': { request: ({ url }) => {
      if (url === '/api/automation/summary') return read('approvals', { url });
      if (url === '/api/tickets') return read('tickets', { url });
      if (url === '/api/platform/monitor/summary') return read('monitor', { url });
      throw new Error(`Unexpected dashboard request: ${url}`);
    } },
    '@/api/sla': { slaApi: { summary: () => read('sla') } },
    '@/api/modules': {
      itsmApi: { currentOnCall: () => read('oncall'), alerts: status => read('alerts', { status }) },
      // Model the pre-existing client pagination contract too: a regression to
      // pageSize:100 must fail the >100 test rather than fail on a missing mock.
      ticketApi: { page: async ({ pageNum = 1, pageSize = 10 }) => {
        const all = await read('tickets', { pageNum, pageSize });
        return { records: all.slice((pageNum - 1) * pageSize, pageNum * pageSize), total: all.length, pageNum, pageSize };
      } },
    },
    '@/components/PageHeader.vue': PageHeader,
    '@/components/MetricStrip.vue': MetricStrip,
    '@/components/StatusBadge.vue': ValueBadge,
    '@/components/PriorityIndicator.vue': ValueBadge,
    '@/components/LoadingState.vue': LoadingState,
    '@/components/EmptyState.vue': EmptyState,
    '@/components/dashboard/WorkspaceLauncher.vue': SlotSurface,
  };
  const component = evaluate(script.content, imports).default;
  const scope = vue.effectScope();
  const state = scope.run(() => component.setup({}, { expose() {} }));
  const ready = Promise.all(mounted.map(fn => fn()));
  return {
    state, api, calls, ready, actor, inbox,
    metric: key => state.overviewMetrics.value.find(item => item.key === key),
    async html() {
      const context = vue.proxyRefs(state);
      const app = vue.createSSRApp({ render: () => render(context, [], {}, context, {}, {}) });
      app.component('RouterLink', RouterLink);
      return renderToString(app);
    },
    stop() { unmounting.forEach(fn => fn()); scope.stop(); },
  };
}

// Relevant work after the old 100-row cutoff must contribute to every summary.
{
  const rows = Array.from({ length: 120 }, (_, index) => ticket(index + 1));
  rows.push(ticket(121, { priority: 'URGENT', status: 'PROCESSING' }), ticket(122, { priority: 'HIGH', status: 'WAITING_CONFIRM' }), ticket(123, { priority: 'URGENT', status: 'CLOSED', eventClosed: true }), ticket(124, { priority: 'HIGH', status: 'REJECTED', eventClosed: true }), ticket(125, { status: 'RESOLVED' }));
  const app = setup({ tickets: async () => rows });
  try {
    await app.ready;
    assert.equal(app.state.tickets.value.length, 125);
    assert.deepEqual(app.state.queueTabs.value.map(tab => [tab.key, tab.count]), [['all', 123], ['priority', 2], ['confirm', 1]]);
    assert.equal(app.metric('priority').value, 2);
    assert.equal(app.metric('processing').value, 1);
    assert.equal(app.state.statusMetrics.value.reduce((total, item) => total + item.value, 0), 125);
    assert.equal(app.state.visibleTickets.value.length, 7, 'preview size must not become the total count');
    assert.ok(app.state.visibleTickets.value.some(row => row.id === 121));
    assert.match(await app.html(), /当前范围 123 项/);
  } finally { app.stop(); }
}

// Before a first response, and after a failed first response, zero is unknown.
{
  const pending = deferred();
  const unavailable = () => Promise.reject(new Error('test backend unavailable'));
  const app = setup({ tickets: () => pending.promise, sla: unavailable, oncall: unavailable, monitor: unavailable, alerts: unavailable });
  try {
    assert.equal(app.metric('priority').value, '获取中');
    assert.equal(app.state.noPriorityRisk.value, false);
    pending.reject(new Error('test ticket endpoint unavailable'));
    await app.ready;
    assert.ok(app.state.overviewMetrics.value.every(item => item.value === '未获取'));
    assert.equal(app.state.checkedAt.value, '', 'no successful synchronization may be reported');
    assert.equal(app.state.noPriorityRisk.value, false);
    const html = await app.html();
    assert.match(html, /事件数据未获取/);
    assert.match(html, /服务监控未获取/);
    assert.match(html, /最近活动未获取/);
    assert.doesNotMatch(html, /当前没有活跃事件|当前未发现以上风险事项|服务正常/);
  } finally { app.stop(); }
}

// One missing risk source is sufficient to suppress a reassuring empty state.
for (const missing of ['tickets', 'sla', 'alerts']) {
  const app = setup({ [missing]: async () => { throw new Error(`missing ${missing}`); } });
  try {
    await app.ready;
    assert.equal(app.state.noPriorityRisk.value, false, `${missing} cannot be treated as zero risk`);
    assert.ok(app.state.incompleteRiskSources.value.includes(missing));
    assert.doesNotMatch(await app.html(), /当前未发现以上风险事项/);
  } finally { app.stop(); }
}

// A failed refresh keeps a useful last result, while making its age/failure clear.
{
  const initialRows = [ticket(1, { priority: 'HIGH', status: 'PROCESSING' }), ticket(2, { priority: 'URGENT' })];
  const app = setup({ tickets: async () => initialRows, sla: async () => sla({ risk: 3, dashboardRisk: 9 }) });
  try {
    await app.ready;
    const previousSync = app.state.checkedAt.value;
    const previousSourceSync = app.state.sourceCheckedAt.tickets;
    for (const key of ['tickets', 'sla', 'oncall', 'monitor', 'alerts']) app.api[key] = async () => { throw new Error('refresh unavailable'); };
    await app.state.load();
    assert.equal(app.metric('priority').value, 2);
    assert.equal(app.metric('sla').value, 3);
    assert.deepEqual(app.state.tickets.value.map(row => row.id), [1, 2]);
    assert.equal(app.state.loaded.tickets, true);
    assert.equal(app.state.checkedAt.value, previousSync);
    assert.equal(app.state.sourceCheckedAt.tickets, previousSourceSync);
    assert.match(app.metric('priority').meta, /刷新失败.*上次数据/);
    assert.match(app.state.freshness('tickets'), /刷新失败.*上次/);
    assert.match(await app.html(), /刷新失败 · 显示上次数据/);
  } finally { app.stop(); }
}

// A previously clean snapshot must not remain a clean bill of health after failure.
{
  const app = setup();
  try {
    await app.ready;
    assert.equal(app.state.noPriorityRisk.value, true);
    app.api.sla = async () => { throw new Error('SLA refresh failed'); };
    await app.state.load();
    assert.equal(app.metric('sla').value, 0, 'retain the last known numeric value');
    assert.equal(app.state.noPriorityRisk.value, false, 'do not present stale zero as current safety');
    assert.doesNotMatch(await app.html(), /当前未发现以上风险事项/);
    app.api.sla = async () => sla();
    await app.state.load();
    assert.equal(app.state.noPriorityRisk.value, true);
    assert.deepEqual(app.state.failedSources.value, []);
  } finally { app.stop(); }
}

// Queues use real status semantics, while the separate activity feed uses recency.
{
  const rows = [
    ticket(11, { priority: 'HIGH', status: 'WAITING_CONFIRM', updateTime: '2026-09-01T08:00:00' }),
    ticket(22, { priority: 'URGENT', status: 'RESOLVED', updateTime: '2026-09-05T08:00:00' }),
    ticket(33, { priority: 'HIGH', status: 'CLOSED', eventClosed: true, updateTime: '2026-09-10T08:00:00' }),
    ticket(44, { priority: 'LOW', status: 'PROCESSING', updateTime: '2026-09-06T08:00:00' }),
    ticket(55, { priority: 'URGENT', status: 'REJECTED', eventClosed: true, updateTime: '2026-09-08T08:00:00' }),
    ticket(66, { priority: 'HIGH', status: 'ASSIGNED', updateTime: '2026-09-07T08:00:00' }),
    ticket(77, { priority: 'MEDIUM', status: 'WAITING_CONFIRM', updateTime: '2026-09-09T08:00:00' }),
    ticket(88, { priority: 'HIGH', status: 'SUSPENDED', updateTime: '2026-09-04T08:00:00' }),
  ];
  const app = setup({ tickets: async () => rows, sla: async () => sla({ risk: 2, dashboardRisk: 99, breached: 4 }) });
  try {
    await app.ready;
    assert.deepEqual(app.state.recentTickets.value.map(row => row.id), [33, 77, 55, 66]);
    assert.deepEqual(app.state.queueTickets.value.map(row => row.id), [22, 66, 88, 11, 77, 44]);
    app.state.activeQueue.value = 'priority';
    await vue.nextTick();
    assert.deepEqual(app.state.queueTickets.value.map(row => row.id), [22, 66, 88, 11]);
    const priorityButton = (await app.html()).match(/<button\b[^>]*data-queue="priority"[^>]*>/)?.[0];
    assert.match(priorityButton || '', /aria-selected="true"/);
    app.state.activeQueue.value = 'confirm';
    await vue.nextTick();
    assert.deepEqual(app.state.queueTickets.value.map(row => row.id), [11, 77], 'RESOLVED is not WAITING_CONFIRM');
    assert.deepEqual(app.state.tickets.value.map(row => row.id), [11, 22, 33, 44, 55, 66, 77, 88], 'sorting must not mutate the loaded dataset');
    assert.equal(app.metric('processing').value, 1, 'ASSIGNED/SUSPENDED must not inflate the PROCESSING link count');
    assert.equal(app.metric('processing').to, '/tickets?status=PROCESSING');
    assert.equal(app.metric('sla').value, 2, 'risk link must not use overdue-inclusive dashboardRisk');
    assert.equal(app.metric('sla').to, '/itsm/sla?view=risk');
    assert.notEqual(app.metric('priority').to, '/tickets?priority=HIGH', 'P1/P2 union must not open a HIGH-only queue');
  } finally { app.stop(); }
}

// Normal users neither request nor expose the administrator-only alert source.
{
  const app = setup({ alerts: async () => { throw new Error('non-admin must never call alerts'); } }, false);
  try {
    await app.ready;
    assert.equal(app.calls.some(call => call.key === 'alerts'), false);
    assert.equal(app.metric('alerts'), undefined);
    assert.equal(app.state.noPriorityRisk.value, true);
    assert.doesNotMatch(await app.html(), /未恢复告警/);
  } finally { app.stop(); }
}

console.log('PASS dashboard data: >100 tickets, unknown first loads, partial failures, stale refreshes/recovery, queue semantics, update-time ordering and role-filtered alerts');

// Event closure is independent of ticket closure; scoped totals are never the 50-row inbox preview.
{
  const app = setup({ tickets: async () => [ticket(1, { status: 'CLOSED', eventClosed: false, assigneeId: 7 }), ticket(2, { status: 'RESOLVED', eventClosed: true, assigneeId: 7 }), ticket(3, { status: 'PROCESSING', assigneeId: 9 })] });
  try {
    await app.ready;
    assert.deepEqual(app.state.activeTickets.value.map(t => t.id), [3, 1]);
    assert.equal(app.state.myTickets.value.length, 1);
    assert.equal(app.state.pendingCount.value, 76);
    assert.match(await app.html(), /待审批 75/);
  } finally { app.stop(); }
}
console.log('PASS independent event closure, personal ownership and untruncated approval totals');

// The homepage uses the same freshness contract as the service graph.
{
  const observed = Date.now();
  const app = setup({ topology: async () => ({ nodes: [{ ciCode: 'rag', ciName: 'RAG', ciType: 'SERVICE', environment: 'PROD', health: 'HEALTHY', observedAt: new Date(observed).toISOString(), observation: { maximumSampleAgeSeconds: 90 } }], edges: [], dataSources: [], checkedAt: new Date(observed).toISOString() }) });
  try {
    await app.ready; assert.equal(app.state.healthCount.value, 1);
    app.state.observationClock.value = observed + 91000;
    assert.equal(app.state.healthCount.value, 0); assert.equal(app.state.unknownCount.value, 1);
  } finally { app.stop(); }
}
console.log('PASS homepage health expires using the shared observation validity window');

{
  const app = setup();
  try {
    await app.ready; app.api.approvals = async () => ({ pendingApprovals: 74 });
    app.inbox.decisionVersion++;
    await vue.nextTick(); await new Promise(setImmediate);
    assert.equal(app.state.approvalTotal.value, 74);
  } finally { app.stop(); }
}
{
  const old = deferred();
  const app = setup({ tickets: () => old.promise });
  try {
    app.api.tickets = async () => [ticket(2, { assigneeId: 8 })];
    app.actor.user = { userId: 8, roles: ['USER'] };
    await vue.nextTick(); await new Promise(setImmediate);
    old.resolve([ticket(1, { assigneeId: 7 })]); await app.ready;
    assert.deepEqual(app.state.tickets.value.map(row => row.id), [2]);
  } finally { app.stop(); }
}
{
  const app = setup({ tickets: async () => [ticket(1, { status: 'CLOSED', eventClosed: false, eventLegacyArchived: true }), ticket(2, { status: 'CLOSED', eventClosed: false })] });
  try { await app.ready; assert.deepEqual(app.state.activeTickets.value.map(row => row.id), [2]); }
  finally { app.stop(); }
}
console.log('PASS live approval decisions, account response isolation and explicitly archived legacy events');

// Advance the real page timer through five visible minutes without waiting in wall time.
{
  const originals = { now: Date.now, setInterval: globalThis.setInterval, clearInterval: globalThis.clearInterval, document: globalThis.document };
  let clock = originals.now(); const timers = new Map(), listeners = new Map(); let timerId = 0;
  Date.now = () => clock;
  globalThis.setInterval = callback => { timers.set(++timerId, callback); return timerId; };
  globalThis.clearInterval = id => timers.delete(id);
  globalThis.document = { hidden: false, addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) };
  let sampleTime = clock, fail = false, pending;
  const fresh = () => ({ nodes: [{ ciCode: 'live', ciName: 'Live', environment: 'PROD', health: 'HEALTHY', healthScope: 'BUSINESS_PROBE', observedAt: new Date(sampleTime).toISOString() }], edges: [], dataSources: [], checkedAt: new Date(clock).toISOString() });
  const app = setup({ topology: async () => { if (pending) return pending.promise; if (fail) throw Error('sample transport timeout'); return fresh(); } });
  const tick = async () => { for (const callback of timers.values()) callback(); await new Promise(setImmediate); await vue.nextTick(); };
  try {
    await app.ready;
    for (let index = 0; index < 20; index++) { clock += 15_000; sampleTime = clock; await tick(); assert.equal(app.state.healthCount.value, 1); }
    assert.equal(app.calls.filter(call => call.key === 'topology').length, 21, 'The actual 15s timer rereads topology throughout five minutes');
    assert.equal(app.calls.filter(call => call.key === 'tickets').length, 1, 'Topology polling does not refetch unrelated work queues');
    globalThis.document.hidden = true; const beforeHidden = app.calls.length; clock += 60_000; await tick(); assert.equal(app.calls.length, beforeHidden);
    globalThis.document.hidden = false; sampleTime = clock; listeners.get('visibilitychange')(); await new Promise(setImmediate);
    assert.equal(app.state.healthCount.value, 1, 'Returning to a visible tab immediately obtains a new sample');
    pending = deferred(); const first = app.state.loadTopology(); const count = app.calls.length;
    const second = app.state.loadTopology(); clock += 15_000; await tick(); assert.equal(app.calls.length, count, 'Manual/timer requests share one pending read');
    const active = app.calls.filter(call => call.key === 'topology').at(-1);
    app.state.environment.value = 'DEMO'; app.state.changeEnvironment();
    assert.equal(active.options.signal.aborted, true, 'Changing environment aborts the old request');
    pending.resolve(fresh()); await Promise.all([first, second]); await new Promise(setImmediate); pending = undefined;
    fail = true; clock += 40_000; await app.state.loadTopology();
    assert.equal(app.state.healthCount.value, 0); assert.equal(app.state.expiredHealthCount.value, 1);
    assert.match(app.state.healthSummary.value, /刷新失败.*观测已过期/);
    const failures = app.calls.length; clock += 15_000; await tick(); assert.equal(app.calls.length, failures, 'Failed reads back off instead of piling up');
    fail = false; sampleTime = clock; await app.state.loadTopology(); assert.equal(app.state.healthCount.value, 1); assert.equal(app.state.topologyError.value, '');
    pending = deferred(); const leaving = app.state.loadTopology(); const last = app.calls.filter(call => call.key === 'topology').at(-1);
    app.stop(); assert(last.options.signal.aborted); assert.equal(timers.size, 0); assert.equal(listeners.size, 0);
    pending.resolve(fresh()); await leaving;
  } finally {
    app.stop(); Date.now = originals.now; globalThis.setInterval = originals.setInterval; globalThis.clearInterval = originals.clearInterval;
    if (originals.document === undefined) delete globalThis.document; else globalThis.document = originals.document;
  }
}
console.log('PASS live Dashboard five-minute polling, no unrelated reads, visibility return, deduplication, abort, real expiry and failure backoff');
