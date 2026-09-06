import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
const require = createRequire(new URL('../package.json', import.meta.url));
const vue = require('vue'), compiler = require('vue/compiler-sfc'), ts = require('typescript');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports = {}) { const module = { exports: {} }; new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText)(id => imports[id] ?? require(id), module, module.exports); return module.exports; }
const obs = evaluate(read('utils/observability.ts'));
const view = evaluate(read('utils/topology-view.ts'));
const icons = new Proxy({}, { get: () => ({ render: () => vue.h('i') }) });
const Stub = { render: () => vue.h('section') };
const auth = vue.reactive({ token: 'actor-a', user: { userId: 7 }, isAdmin: false });
function fixture(path, props, api = {}, additions = {}) {
  const mounted = [], unmounted = []; const scope = vue.effectScope(); const emitted = []; props = vue.reactive(props);
  const { descriptor } = compiler.parse(read(path)); const script = compiler.compileScript(descriptor, { id: path });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename: path, id: path, compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(template.errors, []);
  const imports = { vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounted.push(fn) }, '@lucide/vue': icons,
    '@/api/observabilityV3': { observabilityV3Api: api }, '@/stores/auth': { useAuthStore: () => auth }, '@/utils/observability': obs, '@/utils/topology-view': view,
    ...Object.fromEntries(['EmptyState', 'InlineError', 'LoadingState', 'PaginationBar', 'DetailPanel'].map(name => [`@/components/${name}.vue`, Stub])), ...additions };
  const component = evaluate(script.content, imports).default;
  const state = scope.run(() => component.setup(props, { expose() {}, emit: (...args) => emitted.push(args) }));
  return { state, props, emitted, mounted, stop() { unmounted.forEach(fn => fn()); scope.stop(); } };
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function flush() { await vue.nextTick(); await Promise.resolve(); await Promise.resolve(); }
const sampleNode = { ciCode: 'order', ciName: '订单服务', ciType: 'SERVICE', environment: 'DEMO', health: 'HEALTHY', observation: { status: 'READY', sampledAt: '2026-09-05T00:00:00Z' } };

{
  let instances = () => Promise.resolve({ ciCode: 'order', environment: 'DEMO', runtimeServiceCiCode: 'shared-jvm', items: [], status: 'NO_DATA', podSupported: false });
  let traces = () => Promise.resolve({ status: 'NO_DATA', items: [], message: 'No retained span' });
  let trace = () => Promise.resolve({ traceId: 'valid', environment: 'DEMO', spans: [], partial: true, source: 'TEMPO' });
  const app = fixture('components/observability/ServiceRuntimeEvidence.vue', { ciCode: 'order', environment: 'DEMO', timeRange: '15m' }, { instances: (...args) => instances(...args), traces: (...args) => traces(...args), trace: (...args) => trace(...args) });
  try {
    await app.state.load(); assert.equal(app.state.instances.value.runtimeServiceCiCode, 'shared-jvm'); assert.equal(app.state.instances.value.podSupported, false); assert.deepEqual(app.state.traces.value.items, []);
    const old = deferred(); instances = () => old.promise; const pending = app.state.load();
    instances = async code => ({ ciCode: code, environment: 'DEMO', items: [], status: 'READY', podSupported: false });
    app.props.ciCode = 'notification'; await flush(); old.resolve({ ciCode: 'order', environment: 'DEMO', items: [{ instanceId: 'old' }] }); await pending;
    assert.equal(app.state.instances.value.ciCode, 'notification');
    const oldTrace = deferred(); trace = () => oldTrace.promise; const first = app.state.openTrace('old');
    trace = async () => ({ traceId: 'valid', environment: 'DEMO', source: 'TEMPO', partial: true, spans: [
      { spanId: 'a', parentSpanId: 'b', startTime: '2026-09-06T00:00:00Z' }, { spanId: 'b', parentSpanId: 'a', startTime: '2026-09-06T00:00:01Z' },
    ] }); await app.state.openTrace('valid'); oldTrace.resolve({ traceId: 'old' }); await first;
    assert.equal(app.state.selected.value.traceId, 'valid'); assert(app.state.spanRows.value.every(span => span.depth <= 8), 'malformed parent loops cannot hang rendering');
    traces = async () => { throw Error('Tempo down'); }; await app.state.load(); assert.equal(app.state.traces.value, undefined); assert.equal(app.state.instances.value.ciCode, 'notification'); assert(app.state.errors.value.some(value => value.includes('调用链暂不可用')));
    assert.equal(app.state.selected.value, undefined); assert.equal(app.state.traceLoading.value, false);
  } finally { app.stop(); }
}
console.log('PASS P3 service evidence: independent API failures, shared JVM identity, no invented Pod, stale service/Trace responses discarded, cyclic span parents bounded');

{
  let requests = 0, mutations = 0; let snapshotResult = { nodes: [], edges: [], history: false };
  const app = fixture('components/observability/TopologyEvidencePanel.vue', { environment: 'PROD', timeRange: '15m' }, {
    history: async params => { requests++; assert.equal(params.environment, 'PROD'); assert(Number.isFinite(Date.parse(params.from))); return { items: [], retentionHours: 24 }; },
    historySnapshot: async () => snapshotResult,
    differences: async () => ({ items: [{ id: 'a', category: 'INDETERMINATE', sourceCiCode: 'a', targetCiCode: 'b' }], traceStatus: 'FAILED' }),
    decideDifference: async () => { mutations++; },
  });
  try {
    await app.state.load(); assert.deepEqual(app.state.history.value.items, []); assert.equal(requests, 1);
    await app.state.showSnapshot('7'); assert.equal(app.emitted.length, 0); assert.match(app.state.error.value, /未标识为历史/);
    snapshotResult = { nodes: [sampleNode], edges: [], checkedAt: '2026-09-05T00:00:00Z', history: true };
    await app.state.showSnapshot('7'); assert.deepEqual(app.emitted[0], ['history', snapshotResult, '7']);
    app.state.tab.value = 'differences'; await flush(); app.state.selectedDifference.value = app.state.differences.value.items[0]; app.state.note.value = '已核对固定目标';
    await app.state.saveDecision(); assert.equal(mutations, 0, 'read-only users cannot write a difference decision');
    auth.isAdmin = true; await app.state.saveDecision(); assert.equal(mutations, 1); auth.isAdmin = false;
    app.state.tab.value = 'history'; await flush(); const before = requests; app.state.from.value = 'invalid'; await app.state.load(); assert.equal(requests, before); assert.match(app.state.error.value, /有效时间范围/);
  } finally { app.stop(); }
}
console.log('PASS P3 history/difference: genuine history flag required, empty windows remain empty, date validation and administrator write gate');

{
  let reads = 0;
  const snapshot = { nodes: [sampleNode], edges: [], history: true, checkedAt: '2026-09-05T00:00:00Z', dataSources: [] };
  const app = fixture('components/observability/ServiceNodeDrawer.vue', { node: sampleNode, environment: 'DEMO', timeRange: '15m', snapshot }, {}, {
    'vue-router': { useRoute: () => ({ path: '/observability/topology' }), useRouter: () => ({ push() {} }) },
    '@/api/observability': { observabilityApi: new Proxy({}, { get: () => async () => { reads++; throw Error('historical details must not load current source'); } }) },
    '@/stores/ai-assistant': { useAiAssistantStore: () => ({ show() {} }) }, '@/components/cmdb/topology': { ciType: () => ({ icon: Stub, label: 'service' }), environmentNames: {} },
    '@/utils/service-endpoint': { displayEndpoint: value => value }, './ServiceHealthBadge.vue': Stub, './ServiceRuntimeEvidence.vue': Stub,
  });
  try { await app.state.load(); assert.equal(reads, 0); assert.equal(app.state.detail.value.checkedAt, snapshot.checkedAt); assert(app.state.tabs.value.every(tab => !['runtime', 'config', 'traffic'].includes(tab.key))); assert.equal(obs.effectiveHealth(app.state.current.value), 'HEALTHY'); }
  finally { app.stop(); }
}
console.log('PASS HIST-01 historical Drawer never fetches current metrics/config/runtime or silently changes saved health');

{
  let impl = async () => ({ nodes: [sampleNode], edges: [], checkedAt: '2026-09-06T00:00:00Z', dataSources: [], coverage: { eligible: 8, ready: 2, partial: 2, failed: 1, unknown: 3, excluded: 2, ratio: .5, completeRatio: .25 } });
  const app = fixture('components/dashboard/DashboardTopology.vue', { priorityCount: undefined, verificationCount: undefined }, { topology: () => impl() }, {
    'vue-router': { useRoute: () => ({ query: {} }), useRouter: () => ({ push() {} }) },
    '@/stores/approval-inbox': { useApprovalInboxStore: () => ({ count: 0, error: '', show() {} }) },
    '@/components/observability/ServiceTopology.vue': Stub, '@/components/cmdb/topology': { environmentNames: {} },
  });
  try {
    await app.state.load(); assert.equal(app.state.coverageText.value, '50%'); assert.equal(app.state.coverage.value.eligible, 8, 'server denominator is used even when only one node is returned');
    const prior = deferred(); impl = () => prior.promise; const pending = app.state.load();
    impl = async () => { throw Error('new actor cannot fetch'); }; auth.token = 'actor-b'; await flush(); prior.resolve({ nodes: [{ ...sampleNode, ciCode: 'foreign' }], edges: [] }); await pending;
    assert.equal(app.state.snapshot.value, undefined); assert.equal(app.state.coverageText.value, '未取得');
  } finally { app.stop(); auth.token = 'actor-a'; }
}
console.log('PASS HOME-02 coverage: backend denominator, partial coverage, unknown data and cross-account response rejection');
