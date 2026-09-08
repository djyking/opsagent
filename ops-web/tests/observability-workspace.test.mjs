import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'); const vue = require('vue'); const pinia = require('pinia'); const compiler = require('vue/compiler-sfc');
const read = name => readFileSync(new URL('../src/' + name, import.meta.url), 'utf8');
function evaluate(source, imports = {}) { const module = { exports: {} }; const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText; new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports); return module.exports; }
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function flush() { await vue.nextTick(); await Promise.resolve(); await Promise.resolve(); }
const util = evaluate(read('utils/observability.ts'));
const layoutUtil = evaluate(read('utils/observability-layout.ts'), { './observability-routing': evaluate(read('utils/observability-routing.ts')) });
const now = Date.now();
const node = (code, extra = {}) => ({ ciCode: code, ciName: code, ciType: 'SERVICE', environment: 'PROD', health: 'HEALTHY', observedAt: new Date(now).toISOString(), metrics: { rps: 0, errorRate: 2.5, p95Ms: null, healthyInstances: 1, totalInstances: 1 }, ...extra });
const snapshot = (nodes = [node('rag')]) => ({ nodes, edges: [], checkedAt: new Date(now).toISOString(), dataSources: [] });
{
  const types = ['GATEWAY', ...Array(8).fill('SERVICE'), 'QUEUE', 'QUEUE', 'CACHE', 'CACHE', 'DATABASE',
    'SEARCH', 'VECTOR_DATABASE', 'REGISTRY', 'GOVERNANCE', 'MONITOR', 'MONITOR', 'ALERT', 'EXTERNAL_API', 'EXTERNAL_API'];
  const registered = types.map((ciType, i) => node(`ci-${String(i).padStart(2, '0')}`, { ciType }));
  const sorted = layoutUtil.orderedTopologyNodes([...registered].reverse());
  assert.equal(sorted.length, 23); assert(sorted.slice(0, 9).every(item => ['GATEWAY', 'SERVICE'].includes(item.ciType)));
  const data = { nodes: sorted.map((item, index) => ({ id: item.ciCode, data: { displayOrder: sorted.length - index } })),
    edges: Array.from({ length: 33 }, (_, index) => ({ id: `edge-${index}`, source: registered[index % 9].ciCode, target: registered[9 + index % 14].ciCode, data: {} })) };
  const laidOut = layoutUtil.initialTopologyPositions(sorted);
  const xs = Object.values(laidOut).map(item => item.x), ys = Object.values(laidOut).map(item => item.y);
  assert(Object.keys(laidOut).length === 23); assert(new Set(ys).size <= 7, 'four centered rows can occupy seven full/half-row levels across role lanes');
  const width = Math.max(...xs) - Math.min(...xs) + layoutUtil.TOPOLOGY_NODE_WIDTH;
  const height = Math.max(...ys) - Math.min(...ys) + layoutUtil.TOPOLOGY_NODE_HEIGHT;
  assert(width < 1700); assert(height <= 550);
  const desktopFit = Math.min((1000 - 2 * layoutUtil.TOPOLOGY_FIT_PADDING) / width, (560 - 2 * layoutUtil.TOPOLOGY_FIT_PADDING) / height);
  assert(desktopFit >= .55);
  const dragged = { ...laidOut, 'ci-00': { x: 331, y: 541 } };
  const refreshed = layoutUtil.reconcileTopologyPositions([...registered].reverse(), [], laidOut, dragged);
  assert.deepEqual(refreshed, dragged, 'sample refresh never changes dragged coordinates');
  const added = layoutUtil.reconcileTopologyPositions([...registered, node('new-service')], [], laidOut, dragged);
  for (const item of registered) assert.deepEqual(added[item.ciCode], dragged[item.ciCode]);
  assert(added['new-service']);
  assert.deepEqual(layoutUtil.reconcileTopologyPositions([registered[0]], [], {}, added), added, 'filtering retains hidden coordinates');
  const savedCollision = layoutUtil.reconcileTopologyPositions([node('a'), node('b')], [], { b: { x: 100, y: 75 } });
  assert.notDeepEqual(savedCollision.a, savedCollision.b, 'new nodes avoid a saved node even when it occurs later in order');
  console.log(`PASS stable layered coordinates: 23 nodes, ${width}x${height}; refresh, drag, filtering and local insertion preserve known nodes`);
}
assert.equal(util.effectiveHealth(node('a')), 'HEALTHY');
for (const observedAt of [undefined, 'invalid', new Date(now - 91_000).toISOString(), new Date(now + 20_000).toISOString()]) assert.equal(util.effectiveHealth(node('a', { observedAt }), now), 'UNKNOWN');
assert.equal(util.effectiveHealth(node('a', { health: 'MAINTENANCE', observedAt: undefined })), 'MAINTENANCE');
assert.equal(util.effectiveHealth(node('a', { observedAt: new Date(now - 100_000).toISOString(), observation: { maximumSampleAgeSeconds: 120 } }), now), 'HEALTHY', 'uses backend freshness rule');
assert.equal(util.effectiveHealth(node('a', { healthScope: 'BUSINESS_PROBE', observedAt: new Date(now - 31_000).toISOString() }), now), 'UNKNOWN', 'business probe retains its independent 30 second lifetime');
assert.equal(util.observationState(node('q', { observation: { status: 'READY', sampledAt: new Date(now - 95_000).toISOString(), fetchedAt: new Date(now).toISOString(), maximumSampleAgeSeconds: 90 } }), now), 'STALE', 'fetching does not extend sample lifetime');
assert.equal(util.evidenceMetric(node('q', { metricEvidence: { messagesReady: { value: 0, unit: 'messages', sampledAt: new Date(now).toISOString() } } }), 'messagesReady', now), '0');
assert.equal(util.metric(null), '—'); assert.equal(util.metric(0, '/s'), '0/s'); assert.equal(util.metric(2.5, '%'), '2.5%'); assert.equal(util.metric(Infinity), '—');
const nodes = [node('a'), node('b', { health: 'CRITICAL' }), node('c', { health: 'DEGRADED' }), node('d', { health: 'UNKNOWN' }), node('e', { drilling: true })];
const edges = [{ sourceCiCode: 'b', targetCiCode: 'c', relationType: 'CALLS' }, { sourceCiCode: 'a', targetCiCode: 'b', relationType: 'DEPENDS_ON' }];
const filtered = util.filterTopology(nodes, edges, '', true, now); assert.deepEqual(filtered.nodes.map(n => n.ciCode), ['b', 'c', 'e']); assert.deepEqual(filtered.edges, [edges[0]]);
assert.deepEqual(util.filterTopology(nodes, edges, 'missing', false).nodes, []);
assert.deepEqual(util.serviceContext('rag', 'PROD', '15m'), { ciCode: 'rag', environment: 'PROD', timeRange: '15m' });
assert.equal(util.safeMetricUrl('javascript:alert(1)'), undefined);
console.log('PASS real health/freshness semantics, missing versus zero metrics, directed filtering, drilling overlay and service context');

{
  pinia.setActivePinia(pinia.createPinia());
  const auth = vue.reactive({ token: 'token-a', identity: 'actor-a' }); let impl; const api = { topology: params => impl(params), layout: async environment => ({ environment, positions: {}, source: 'AUTO' }) };
  const module = evaluate(read('stores/observability.ts'), { vue, pinia, '@/api/observability': { observabilityApi: api }, '@/stores/auth': { useAuthStore: () => auth }, '@/utils/observability': util });
  const store = module.useObservabilityStore();
  const old = deferred(); impl = () => old.promise; const olderLoad = store.load();
  store.selectedEnvironment = 'DEMO'; const recent = deferred(); impl = () => recent.promise; const recentLoad = store.load();
  recent.resolve(snapshot([node('new')])); await recentLoad; old.resolve(snapshot([node('old')])); await olderLoad;
  assert.equal(store.topologyData.nodes[0].ciCode, 'new');
  auth.token = 'token-a-refreshed'; assert.equal(store.topologyData.nodes[0].ciCode, 'new', 'same-account token renewal keeps current topology');
  const foreign = deferred(); impl = () => foreign.promise; const foreignLoad = store.load(); auth.token = 'token-b'; auth.identity = 'actor-b'; assert.equal(store.topologyData, undefined);
  foreign.resolve(snapshot([node('foreign')])); await foreignLoad; assert.equal(store.topologyData, undefined);
  impl = async () => snapshot(); await store.load(); assert.equal(store.topologyData.nodes.length, 1);
  impl = async () => { throw Error('prometheus network boundary'); }; await store.load(); assert.equal(store.topologyData.nodes.length, 1); assert.match(store.error, /network/);
  impl = async () => snapshot([]); await store.load(); assert.deepEqual(store.visible, { nodes: [], edges: [] });
  store.$dispose();
}
console.log('PASS actual Pinia store environment/account races, stale response discard, empty API and error clearing');

const icons = new Proxy({}, { get: () => ({ render: () => vue.h('svg') }) });
const Stub = { setup: (_, { slots }) => () => vue.h('section', slots.default?.()) };
function compile(name, imports) { const { descriptor, errors } = compiler.parse(read(name)); assert.deepEqual(errors, []); const script = compiler.compileScript(descriptor, { id: name }); const template = compiler.compileTemplate({ source: descriptor.template.content, filename: name, id: name, compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(template.errors, []); return evaluate(script.content, imports).default; }
{
  const scope = vue.effectScope(), unmounted = [];
  const auth = vue.reactive({ identity: 'admin', isDemo: false });
  const pending = deferred(); let calls = 0;
  const component = compile('components/observability/HostResourcePanel.vue', {
    vue: { ...vue, onMounted() {}, onBeforeUnmount: fn => unmounted.push(fn) }, '@lucide/vue': icons,
    '@/stores/auth': { useAuthStore: () => auth },
    '@/api/host-resources': { hostResourcesApi: { read: () => { calls++; return pending.promise; } } },
    '@/utils/host-resources': {}, '@/utils/observability': util,
    '@/components/InlineError.vue': Stub, '@/components/LoadingState.vue': Stub, './MetricSparkline.vue': Stub,
  });
  const state = scope.run(() => component.setup({}, { expose() {} }));
  try {
    const old = state.load(); assert.equal(calls, 1);
    auth.identity = 'visitor'; auth.isDemo = true; await flush();
    pending.resolve({ hosts: [{ ciCode: 'private-host' }] }); await old;
    assert.equal(state.snapshot.value, undefined, 'Late administrator host resources must not appear for a visitor');
    await state.load(); assert.equal(calls, 1, 'Visitor entry and refresh never request privileged host resources');
    assert.equal(state.error.value, ''); assert.equal(state.loading.value, false);
  } finally { unmounted.forEach(fn => fn()); scope.stop(); }
}
console.log('PASS host resource panel suppresses visitor reads and discards prior administrator responses');
{
  const scope = vue.effectScope(), auth = vue.reactive({ identity: 'visitor', isDemo: true, isAdmin: false });
  const route = vue.reactive({ query: {}, path: '/observability/topology' });
  const store = vue.reactive({ selectedEnvironment: 'DEMO', selectedCiCode: '', timeRange: '15m', topologyMode: 'CONFIGURED', visible: { nodes: [], edges: [] }, load: async () => {}, topologyData: snapshot([node('demo', { environment: 'DEMO' })]) });
  let writes = 0;
  const imports = {
    vue: { ...vue, onMounted() {}, onBeforeUnmount() {} }, '@lucide/vue': icons,
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace() {} }) },
    '@/stores/auth': { useAuthStore: () => auth }, '@/stores/observability': { useObservabilityStore: () => store },
    '@/api/observability': { observabilityApi: { savePersonalLayout: async () => { writes++; }, resetPersonalLayout: async () => { writes++; } } },
    '@/components/cmdb/topology': { environmentNames: { DEMO: '演示' } }, '@/utils/observability': util,
    './ObservabilityWorkspaceView.vue': Stub,
  };
  for (const name of ['ServiceTopology', 'ServiceNodeDrawer', 'ServiceEditor', 'RelationEditor', 'ServiceHealthBadge']) imports[`@/components/observability/${name}.vue`] = Stub;
  for (const name of ['InlineError', 'LoadingState', 'EmptyState']) imports[`@/components/${name}.vue`] = Stub;
  const file = 'views/observability/TopologyView.vue';
  const component = compile(file, imports), { descriptor } = compiler.parse(read(file));
  const script = compiler.compileScript(descriptor, { id: file });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename: file, id: file, compilerOptions: { bindingMetadata: script.bindings } });
  const render = evaluate(template.code, { vue }).render;
  const state = scope.run(() => component.setup({}, { expose() {} }));
  state.graph.value = { positions: () => [{ ciCode: 'demo', x: 10, y: 20 }] };
  const html = async () => { const context = vue.proxyRefs(state); const app = vue.createSSRApp({ render: () => render(context, [], {}, context, {}, {}) }); app.component('RouterLink', Stub); return require('vue/server-renderer').renderToString(app); };
  const buttons = markup => [...markup.matchAll(/<button\b[^>]*>([\s\S]*?)<\/button>/g)].map(match => match[1].replace(/<[^>]+>/g, '').trim());
  try {
    const demo = await html();
    assert(!buttons(demo).includes('保存布局') && !buttons(demo).includes('使用团队默认'));
    assert(demo.includes('使用新版默认布局') && demo.includes('撤回上次自动布局'));
    await state.saveLayout(); await state.useTeamLayout(); assert.equal(writes, 0, 'Visitor cannot submit layout persistence even through a direct handler call');
    auth.isDemo = false; assert(buttons(await html()).includes('保存布局'));
    await state.saveLayout(); assert.equal(writes, 1, 'An ordinary account retains personal layout saving');
  } finally { scope.stop(); }
}
console.log('PASS visitor topology keeps in-memory layout tools while hiding and rejecting persistence');
{
  const mounted = [], unmounted = []; const scope = vue.effectScope(); const pushes = [], aiCalls = [], emissions = [];
  const auth = vue.reactive({ token: 'a', identity: 'actor-a' }); const props = vue.reactive({ node: node('first'), environment: 'PROD', timeRange: '15m' });
  let impl; const imports = { vue: { ...vue, onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => unmounted.push(callback) }, '@lucide/vue': icons,
    'vue-router': { useRoute: () => ({ path: '/observability/topology' }), useRouter: () => ({ push: value => pushes.push(value) }) },
    '@/api/observability': { observabilityApi: { service: (...args) => impl(...args), configSummary: async () => { throw Error('Nacos down'); }, trafficSummary: async () => ({ status: 'NOT_INTEGRATED', passQps: null }), metricHistory: async () => ({ series: {} }) } }, '@/stores/auth': { useAuthStore: () => auth }, '@/stores/ai-assistant': { useAiAssistantStore: () => ({ show: context => aiCalls.push(context) }) },
    '@/components/cmdb/topology': { ciType: () => ({ label: '服务', icon: Stub }), environmentNames: { PROD: '生产' } }, '@/utils/observability': util, '@/utils/service-endpoint': { displayEndpoint: value => value },
    './ServiceHealthBadge.vue': Stub, './ServiceDetailShell.vue': Stub, './ObservationEvidence.vue': Stub, './MetricSparkline.vue': Stub,
    '@/utils/observability-icons': { serviceIcon: () => Stub },
  };
  for (const name of ['DetailPanel', 'InlineError', 'LoadingState', 'EmptyState']) imports[`@/components/${name}.vue`] = Stub;
  const Component = compile('components/observability/ServiceNodeDrawer.vue', imports);
  const state = scope.run(() => Component.setup(props, { expose() {}, emit: (...args) => emissions.push(args) }));
  const first = deferred(); impl = () => first.promise; const request = state.load();
  const second = deferred(); impl = () => second.promise; props.node = node('second'); await flush();
  second.resolve({ node: node('second'), alerts: [], recentChanges: [], recentRuns: [], relations: [], alertsAvailable: false }); await flush();
  first.resolve({ node: node('first'), alerts: [], recentChanges: [], recentRuns: [], relations: [] }); await request;
  assert.equal(state.detail.value.node.ciCode, 'second'); assert.equal(state.configSummary.value.status, 'UNAVAILABLE'); assert.equal(state.trafficSummary.value.passQps, null); assert.equal(state.cards.value[1].value, '2.5%');
  state.go('/itsm/alerts'); assert.equal(pushes[0].query.ciCode, 'second'); assert.equal(pushes[0].query.environment, 'PROD');
  state.go('/observability/metrics'); assert.equal(pushes.at(-1).path, '/observability/metrics'); assert.equal(pushes.at(-1).query.ciCode, 'second');
  state.analyze(); assert.equal(aiCalls[0].service, 'second'); assert(emissions.some(item => item[0] === 'close'));
  impl = async () => { throw Error('node detail unavailable'); }; await state.load(); assert.equal(state.detail.value, undefined); assert.match(state.error.value, /unavailable/);
  unmounted.forEach(fn => fn()); scope.stop();
}
console.log('PASS actual service Drawer selection race, context-preserving alerts and shared AI, percentage display and failed reads');
assert.match(read('components/observability/ServiceNodeDrawer.vue'), /go\('\/observability\/metrics'\)/);
assert.doesNotMatch(read('components/observability/ServiceNodeDrawer.vue'), /go\('\/monitor'\)/);
assert.match(read('views/observability/TopologyView.vue'), /path: '\/observability\/metrics'/);

{
  let writes = 0; const auth = vue.reactive({ isAdmin: false });
  const imports = { vue, '@/components/BaseModal.vue': Stub, '@/components/InlineError.vue': Stub, '@/api/modules': { itsmApi: { createCi: async () => writes++, updateCi: async () => writes++ } }, '@/api/observability': { observabilityApi: { deleteCi: async () => writes++ } }, '@/stores/auth': { useAuthStore: () => auth }, '@/components/cmdb/topology': { ciTypes: {}, environmentNames: {} } };
  const Component = compile('components/observability/ServiceEditor.vue', imports);
  const state = Component.setup({ node: node('protected', { id: 4, tags: ['RAG', '核心'] }) }, { expose() {}, emit() {} });
  await state.save(); state.confirmDelete.value = true; await state.remove(); assert.equal(writes, 0);
  auth.isAdmin = true; await state.save(); assert.equal(writes, 1);
}
console.log('PASS actual editor role gate for save/delete and existing tags adaptation');

{
  const mounted = [], unmounted = [], emitted = []; let instance;
  const priorDocument = globalThis.document, priorResize = globalThis.ResizeObserver, priorWindow = globalThis.window;
  globalThis.window = { innerWidth: 1366, innerHeight: 768, scrollY: 0, addEventListener() {}, removeEventListener() {} };
  globalThis.document = { createElement: () => ({ innerHTML: '', textContent: '', className: '' }), addEventListener() {}, removeEventListener() {} };
  globalThis.ResizeObserver = class { observe() {} disconnect() {} };
  class Graph {
    constructor(options) { this.options = options; this.events = new Map(); this.zoom = 1; this.data = { nodes: [], edges: [] }; instance = this; }
    on(name, handler) { this.events.set(name, handler); if (name === 'aftertransform') this.pendingTransform = handler; }
    off(name, handler) { if (this.events.get(name) === handler) this.events.delete(name); }
    setOptions(options) { Object.assign(this.options, options); }
    setData(data) { this.data = data; }
    getNodeData() { return this.data.nodes; }
    getEdgeData(id) { return id ? this.data.edges.find(edge => edge.id === id) : this.data.edges; }
    async setElementState(states) { this.states = states; }
    async render() { if (!this.viewportReady) this.events.get('aftertransform')?.(); this.viewportReady = true; this.data.nodes.forEach((node, i) => { node.style ||= { x: i * 250, y: 100 }; }); }
    async draw() {} async fitView() { this.zoom = .7; } getZoom() { if (!this.viewportReady || this.destroyed) throw Error('viewport unavailable'); return this.zoom; }
    async zoomBy(value) { this.zoom *= value; } async zoomTo(value) { this.zoom = value; } async focusElement() {} setSize() {}
    updateNodeData(data) { for (const item of data) Object.assign(this.data.nodes.find(n => n.id === item.id), item); }
    updateEdgeData(data) { this.data.edges = data; }
    destroy() { this.destroyed = true; this.pendingTransform?.(); }
  }
  const props = vue.reactive({ nodes: [node('rag'), node('queue', { ciType: 'QUEUE' })], edges: [{ id: 3, sourceCiCode: 'rag', targetCiCode: 'queue', relationType: 'PUBLISHES_TO', relationSource: 'CONFIGURED' }], selected: '', editing: false, showTraffic: false, wallboard: false });
  const scope = vue.effectScope();
  try {
    const Component = compile('components/observability/ServiceTopology.vue', { vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounted.push(fn), render: (node, box) => { if (node) box.innerHTML = '<svg aria-hidden="true"></svg>'; } }, '@antv/g6': { Graph }, '@/styles/pages/observability.css': {}, '@/utils/observability-icons': { serviceIcon: () => Stub }, '@/utils/observability': util, '@/utils/observability-layout': layoutUtil });
    const state = scope.run(() => Component.setup(props, { expose() {}, emit: (...args) => emitted.push(args) }));
    state.host.value = { clientWidth: 1200, clientHeight: 600, getBoundingClientRect: () => ({ top: 274 }), closest: () => null, addEventListener() {}, removeEventListener() {}, setPointerCapture() {}, hasPointerCapture: () => true, releasePointerCapture() {} };
    for (const callback of mounted) await callback();
    assert.equal(state.canvasHeight.value, '390px', 'canvas reserves the fixed relation summary below its viewport');
    assert.equal(emitted.some(item => item[0] === 'error'), false, 'initial G6 transform must not access the unfinished viewport');
    assert.equal(instance.options.edge.style.endArrow, true); assert.equal(instance.options.layout, undefined); assert(instance.data.nodes.every(item => Number.isFinite(item.style.x)));
    assert.equal(instance.data.edges[0].source, 'rag'); assert.equal(instance.data.edges[0].target, 'queue');
    instance.events.get('node:click')({ target: { id: 'queue' } }); assert.deepEqual(emitted[0], ['select', 'queue']);
    const card = { dataset: { ciCode: 'rag' }, closest: () => null };
    const pointer = (clientX, clientY, type = 'pointermove') => ({ type, button: 0, pointerId: 1, clientX, clientY, target: { closest: selector => selector === '[data-ci-code]' ? card : null }, preventDefault() {}, stopPropagation() {} });
    const original = state.positions().find(item => item.ciCode === 'rag');
    state.startPointerDrag(pointer(100, 100, 'pointerdown')); state.pointerFocus(pointer(150, 160)); await state.finishPointerDrag(pointer(150, 160, 'pointerup'));
    assert.deepEqual(state.positions().find(item => item.ciCode === 'rag'), original, 'view mode does not move HTML cards');
    props.editing = true; state.startPointerDrag(pointer(100, 100, 'pointerdown'));
    const moved = pointer(100 + (321 - original.x) * instance.getZoom(), 100 + (654 - original.y) * instance.getZoom());
    state.pointerFocus(moved); await state.finishPointerDrag({ ...moved, type: 'pointerup' });
    assert(emitted.some(item => item[0] === 'change')); assert.deepEqual(state.positions()[0], { ciCode: 'rag', x: 321, y: 654 });
    await state.zoomBy(1.25); assert.equal(state.zoom.value, 108); await state.fit(); assert.equal(state.zoom.value, 70, 'fit shows the complete graph');
    await state.restoreView(); assert.equal(state.zoom.value, 86, 'restore view returns to readable zoom');
    props.nodes = [...props.nodes, node('new')]; await flush(); assert.equal(state.positions().find(item => item.ciCode === 'rag').x, 321, 'editing a relation or node must not discard unsaved positions');
    props.layout = Object.fromEntries(props.nodes.map((node, index) => [node.ciCode, { x: 400 + index * 100, y: 500 }]));
    props.editing = false; await flush(); assert.equal(instance.options.layout, undefined, 'persisted administrator layout takes priority over the default grid');
    assert.equal(state.positions().find(item => item.ciCode === 'rag').x, 321, 'periodic layout refresh must not overwrite unsaved coordinates');
    await state.restoreLayout(); assert.equal(state.positions().find(item => item.ciCode === 'rag').x, 400);
    await state.autoLayout(); assert.notEqual(state.positions().find(item => item.ciCode === 'rag').x, 400, 'explicit auto-layout reflows even when a saved layout exists');
    const markup = state.html(node('x', { ciName: '<script>alert(1)</script>', statusReason: '" onfocus="alert(1)' })); assert(!markup.includes('<script>')); assert(markup.includes('&lt;script&gt;')); assert(!markup.includes('2.5%'), 'metrics stay in the drawer');
    props.wallboard = true; const before = emitted.length; instance.events.get('node:click')({ target: { id: 'queue' } }); assert.equal(emitted.length, before);
  } finally { unmounted.forEach(fn => fn()); scope.stop(); globalThis.document = priorDocument; globalThis.ResizeObserver = priorResize; globalThis.window = priorWindow; }
  assert.equal(instance.destroyed, true);
  assert.equal(instance.events.has('aftertransform'), false);
  assert.doesNotThrow(() => instance.pendingTransform?.(), 'late transforms after unmount must be harmless');
}
console.log('PASS G6 adapter node selection, directed edges, edit-only drag, persistent draft positions, zoom/fit, HTML escaping and read-only wallboard');

// Compile every delivered view so template regressions fail without needing a browser.
for (const name of ['TopologyView', 'ServiceCatalogView', 'InspectionView', 'WallboardView', 'ObservabilityWorkspaceView']) {
  const { descriptor } = compiler.parse(read(`views/observability/${name}.vue`)); const script = compiler.compileScript(descriptor, { id: name }); const result = compiler.compileTemplate({ source: descriptor.template.content, filename: name, id: name, compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(result.errors, []);
}
assert(!read('views/observability/WallboardView.vue').includes('ServiceEditor'));
assert.match(read('views/observability/WallboardView.vue'), /document.hidden/);
assert.match(read('views/observability/InspectionView.vue'), /currentHealth/);
console.log('PASS workspace templates, read-only wallboard and inspection current/history separation');
