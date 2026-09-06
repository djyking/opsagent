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
const layoutUtil = evaluate(read('utils/observability-layout.ts'));
const topologyView = evaluate(read('utils/topology-view.ts'));
const now = Date.now();
const node = (code, extra = {}) => ({ ciCode: code, ciName: code, ciType: 'SERVICE', environment: 'PROD', health: 'HEALTHY', observedAt: new Date(now).toISOString(), metrics: { rps: 0, errorRate: 2.5, p95Ms: null, healthyInstances: 1, totalInstances: 1 }, ...extra });
const snapshot = (nodes = [node('rag')]) => ({ nodes, edges: [], checkedAt: new Date(now).toISOString(), dataSources: [] });
{
  // Execute the actual layout package used by G6, with the deployed 23-node/33-edge scale.
  const g6Require = createRequire(require.resolve('@antv/g6'));
  const { GridLayout } = g6Require('@antv/layout'); const { Graph } = g6Require('@antv/graphlib');
  const types = ['GATEWAY', ...Array(8).fill('SERVICE'), 'QUEUE', 'QUEUE', 'CACHE', 'CACHE', 'DATABASE',
    'SEARCH', 'VECTOR_DATABASE', 'REGISTRY', 'GOVERNANCE', 'MONITOR', 'MONITOR', 'ALERT', 'EXTERNAL_API', 'EXTERNAL_API'];
  const registered = types.map((ciType, i) => node(`ci-${String(i).padStart(2, '0')}`, { ciType }));
  const sorted = layoutUtil.orderedTopologyNodes([...registered].reverse());
  assert.equal(sorted.length, 23); assert(sorted.slice(0, 9).every(item => ['GATEWAY', 'SERVICE'].includes(item.ciType)));
  const data = { nodes: sorted.map((item, index) => ({ id: item.ciCode, data: { displayOrder: sorted.length - index } })),
    edges: Array.from({ length: 33 }, (_, index) => ({ id: `edge-${index}`, source: registered[index % 9].ciCode, target: registered[9 + index % 14].ciCode, data: {} })) };
  const laidOut = await new GridLayout(layoutUtil.topologyGridLayout).execute(new Graph(data));
  const xs = laidOut.nodes.map(item => item.data.x), ys = laidOut.nodes.map(item => item.data.y);
  assert.equal(new Set(xs).size, 5); assert.equal(new Set(ys).size, 5);
  const width = Math.max(...xs) - Math.min(...xs) + layoutUtil.TOPOLOGY_NODE_WIDTH;
  const height = Math.max(...ys) - Math.min(...ys) + layoutUtil.TOPOLOGY_NODE_HEIGHT;
  assert.equal(width, 1128); assert.equal(height, 728);
  const desktopFit = Math.min((1000 - 2 * layoutUtil.TOPOLOGY_FIT_PADDING) / width, (560 - 2 * layoutUtil.TOPOLOGY_FIT_PADDING) / height);
  assert(desktopFit >= .70 && desktopFit <= .80);
  assert.deepEqual(laidOut.nodes.slice(0, 9).map(item => item.id), sorted.slice(0, 9).map(item => item.ciCode));
  console.log(`PASS actual G6 GridLayout: 23 nodes / 33 edges, 5 columns / 5 rows, ${width}x${height}, conservative 1440 viewport fit ${(desktopFit * 100).toFixed(1)}%`);
}
assert.equal(util.effectiveHealth(node('a')), 'HEALTHY');
for (const observedAt of [undefined, 'invalid', new Date(now - 91_000).toISOString(), new Date(now + 20_000).toISOString()]) assert.equal(util.effectiveHealth(node('a', { observedAt }), now), 'UNKNOWN');
assert.equal(util.effectiveHealth(node('a', { health: 'MAINTENANCE', observedAt: undefined })), 'MAINTENANCE');
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
  const auth = vue.reactive({ token: 'actor-a' }); let impl; const api = { topology: params => impl(params) };
  const module = evaluate(read('stores/observability.ts'), { vue, pinia, '@/api/observabilityV3': { observabilityV3Api: api }, '@/stores/auth': { useAuthStore: () => auth }, '@/utils/observability': util });
  const store = module.useObservabilityStore();
  const old = deferred(); impl = () => old.promise; const olderLoad = store.load();
  store.selectedEnvironment = 'DEMO'; const recent = deferred(); impl = () => recent.promise; const recentLoad = store.load();
  recent.resolve(snapshot([node('new')])); await recentLoad; old.resolve(snapshot([node('old')])); await olderLoad;
  assert.equal(store.topologyData.nodes[0].ciCode, 'new');
  const foreign = deferred(); impl = () => foreign.promise; const foreignLoad = store.load(); auth.token = 'actor-b'; assert.equal(store.topologyData, undefined);
  foreign.resolve(snapshot([node('foreign')])); await foreignLoad; assert.equal(store.topologyData, undefined);
  impl = async () => snapshot(); await store.load(); assert.equal(store.topologyData.nodes.length, 1);
  impl = async () => { throw Error('prometheus network boundary'); }; await store.load(); assert.equal(store.topologyData, undefined); assert.match(store.error, /network/);
  impl = async () => snapshot([]); await store.load(); assert.deepEqual(store.visible, { nodes: [], edges: [] });
  store.$dispose();
}
console.log('PASS actual Pinia store environment/account races, stale response discard, empty API and error clearing');

const icons = new Proxy({}, { get: () => ({ render: () => vue.h('svg') }) });
const Stub = { setup: (_, { slots }) => () => vue.h('section', slots.default?.()) };
function compile(name, imports) { const { descriptor, errors } = compiler.parse(read(name)); assert.deepEqual(errors, []); const script = compiler.compileScript(descriptor, { id: name }); const template = compiler.compileTemplate({ source: descriptor.template.content, filename: name, id: name, compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(template.errors, []); return evaluate(script.content, imports).default; }
{
  const mounted = [], unmounted = []; const scope = vue.effectScope(); const pushes = [], aiCalls = [], emissions = [];
  const auth = vue.reactive({ token: 'a' }); const props = vue.reactive({ node: node('first'), environment: 'PROD', timeRange: '15m' });
  let impl; const imports = { vue: { ...vue, onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => unmounted.push(callback) }, '@lucide/vue': icons,
    'vue-router': { useRoute: () => ({ path: '/observability/topology' }), useRouter: () => ({ push: value => pushes.push(value) }) },
    '@/api/observability': { observabilityApi: { service: (...args) => impl(...args), configSummary: async () => { throw Error('Nacos down'); }, trafficSummary: async () => ({ status: 'NOT_INTEGRATED', passQps: null }) } }, '@/stores/auth': { useAuthStore: () => auth }, '@/stores/ai-assistant': { useAiAssistantStore: () => ({ show: context => aiCalls.push(context) }) },
    '@/components/cmdb/topology': { ciType: () => ({ label: '服务', icon: Stub }), environmentNames: { PROD: '生产' } }, '@/utils/observability': util, '@/utils/service-endpoint': { displayEndpoint: value => value },
    './ServiceHealthBadge.vue': Stub, './ServiceRuntimeEvidence.vue': Stub,
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
  const priorDocument = globalThis.document, priorResize = globalThis.ResizeObserver, priorComputedStyle = globalThis.getComputedStyle;
  globalThis.getComputedStyle = () => ({ getPropertyValue: () => '#c0cadb' });
  globalThis.document = { createElement: () => ({ innerHTML: '', textContent: '', className: '' }) };
  globalThis.ResizeObserver = class { observe() {} disconnect() {} };
  class Graph {
    constructor(options) { this.options = options; this.calls = { setData: 0, render: 0, fit: 0, draw: 0 }; this.events = new Map(); this.zoom = 1; this.data = { nodes: [], edges: [] }; instance = this; }
    on(name, handler) { this.events.set(name, handler); if (name === 'aftertransform') this.pendingTransform = handler; }
    off(name, handler) { if (this.events.get(name) === handler) this.events.delete(name); }
    setOptions(options) { Object.assign(this.options, options); }
    setData(data) { this.calls.setData++; this.data = data; }
    getNodeData() { return this.data.nodes; }
    async render() { this.calls.render++; this.pendingTransform?.(); this.viewportReady = true; this.data.nodes.forEach((node, i) => { node.style ||= { x: i * 250, y: 100 }; }); }
    async draw() { this.calls.draw++; } async fitView() { this.calls.fit++; this.zoom = .7; } getZoom() { if (this.destroyed || !this.viewportReady) throw Error('viewport getZoom called before initialization or after destroy'); return this.zoom; }
    async zoomBy(value) { this.zoom *= value; } setSize() {}
    updateNodeData(data) { for (const item of data) Object.assign(this.data.nodes.find(n => n.id === item.id), item); }
    updateEdgeData(data) { this.data.edges = data; }
    destroy() { this.destroyed = true; this.pendingTransform?.(); }
  }
  const props = vue.reactive({ nodes: [node('rag'), node('queue', { ciType: 'QUEUE' })], edges: [{ id: 3, sourceCiCode: 'rag', targetCiCode: 'queue', relationType: 'PUBLISHES_TO', relationSource: 'CONFIGURED' }], selected: '', editing: false, showTraffic: false, wallboard: false });
  const scope = vue.effectScope();
  try {
    const Component = compile('components/observability/ServiceTopology.vue', { vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounted.push(fn), render: (node, box) => { if (node) box.innerHTML = '<svg aria-hidden="true"></svg>'; } }, '@antv/g6': { Graph }, '@/styles/pages/observability.css': {}, '@/components/cmdb/topology': { ciType: () => ({ icon: Stub }) }, '@/utils/observability': util, '@/utils/observability-layout': layoutUtil, '@/utils/topology-view': topologyView });
    const state = scope.run(() => Component.setup(props, { expose() {}, emit: (...args) => emitted.push(args) }));
    state.host.value = { clientWidth: 1200, clientHeight: 600, addEventListener() {}, removeEventListener() {} };
    for (const callback of mounted) await callback();
    assert.equal(instance.options.edge.style.endArrow, true); assert.equal(instance.options.layout.type, 'grid'); assert.equal(instance.options.layout.cols, 5);
    assert.equal(emitted.filter(item => item[0] === 'error').length, 0, 'initial G6 transform cannot access an unassigned viewport');
    assert.equal(instance.data.edges[0].source, 'rag'); assert.equal(instance.data.edges[0].target, 'queue');
    instance.events.get('node:click')({ target: { id: 'queue' } }); assert.deepEqual(emitted[0], ['select', 'queue']);
    instance.events.get('edge:click')({ target: { id: 'relation-3' } }); assert.equal(emitted.at(-1)[0], 'edgeSelect'); assert.equal(emitted.at(-1)[1].targetCiCode, 'queue');
    const drag = instance.options.behaviors.find(item => typeof item === 'object' && item.type === 'drag-element'); assert.equal(drag.enable(), false);
    props.editing = true; assert.equal(drag.enable(), true); instance.data.nodes[0].style = { x: 321, y: 654 }; instance.events.get('node:dragend')();
    assert(emitted.some(item => item[0] === 'change')); assert.deepEqual(state.positions()[0], { ciCode: 'rag', x: 321, y: 654 });
    await state.zoomBy(1.25); assert.equal(state.zoom.value, 88); await state.fit(); assert.equal(state.zoom.value, 70);
    props.nodes = [...props.nodes, node('new')]; await flush(); assert.equal(state.positions().find(item => item.ciCode === 'rag').x, 321, 'editing a relation or node must not discard unsaved positions');
    props.layout = Object.fromEntries(props.nodes.map((node, index) => [node.ciCode, { x: 400 + index * 100, y: 500 }]));
    props.editing = false; await flush(); assert.equal(instance.options.layout, undefined, 'persisted administrator layout takes priority over the default grid');
    await state.draw(false, true); assert.equal(state.positions().find(item => item.ciCode === 'rag').x, 400, 'explicit reset restores the server layout without automatic metric-refresh relayout');
    await state.autoLayout(); assert.equal(instance.options.layout.type, 'grid', 'explicit auto-layout reflows even when a saved layout exists');
    const markup = state.html(node('x', { ciName: '<script>alert(1)</script>', statusReason: '" onfocus="alert(1)' })); assert(!markup.includes('<script>')); assert(markup.includes('&lt;script&gt;')); assert(markup.includes('P95')); assert(!markup.includes('2.5%'), 'default nodes expose only two metrics');
    const stablePositions = state.positions(); const initialCalls = { ...instance.calls }; const stableZoom = instance.getZoom();
    for (let round = 0; round < 10; round++) { props.nodes = props.nodes.map(item => ({ ...item, metrics: { ...item.metrics, rps: round + 1 } })); await flush(); await state.draw(); }
    assert.deepEqual(state.positions(), stablePositions); assert.equal(instance.getZoom(), stableZoom);
    assert.equal(instance.calls.render, initialCalls.render); assert.equal(instance.calls.setData, initialCalls.setData); assert.equal(instance.calls.fit, initialCalls.fit);
    props.wallboard = true; const before = emitted.length; instance.events.get('node:click')({ target: { id: 'queue' } }); assert.equal(emitted.length, before);
  } finally { unmounted.forEach(fn => fn()); scope.stop(); globalThis.document = priorDocument; globalThis.ResizeObserver = priorResize; globalThis.getComputedStyle = priorComputedStyle; }
  assert.equal(instance.destroyed, true);
  assert.equal(instance.events.has('aftertransform'), false, 'remove viewport listener before destroying G6');
  assert.doesNotThrow(() => instance.pendingTransform(), 'already queued viewport callbacks cannot read a destroyed viewport');
}
console.log('PASS G6 adapter node selection, directed edges, edit-only drag, persistent draft positions, zoom/fit, HTML escaping, read-only wallboard and late viewport events during/after destroy');

// Compile every delivered view so template regressions fail without needing a browser.
for (const name of ['TopologyView', 'ServiceCatalogView', 'InspectionView', 'WallboardView', 'ObservabilityWorkspaceView']) {
  const { descriptor } = compiler.parse(read(`views/observability/${name}.vue`)); const script = compiler.compileScript(descriptor, { id: name }); const result = compiler.compileTemplate({ source: descriptor.template.content, filename: name, id: name, compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(result.errors, []);
}
assert(!read('views/observability/WallboardView.vue').includes('ServiceEditor'));
assert.match(read('views/observability/WallboardView.vue'), /document.hidden/);
assert.match(read('views/observability/InspectionView.vue'), /currentHealth/);
console.log('PASS workspace templates, read-only wallboard and inspection current/history separation');
