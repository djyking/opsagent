import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), vr = require('vue-router'), compiler = require('vue/compiler-sfc');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports = {}) {
  const module = { exports: {} };
  new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText)(id => imports[id] ?? require(id), module, module.exports);
  return module.exports;
}
const util = evaluate(read('utils/topology-view.ts'));
const obs = evaluate(read('utils/observability.ts'));
const layout = evaluate(read('utils/observability-layout.ts'));
const nodes = ['a', 'b', 'c', 'd', 'nacos'].map(ciCode => ({ ciCode, ciType: 'SERVICE', environment: 'PROD' }));
const edges = [
  ['a', 'b', 'CALLS'], ['b', 'a', 'CALLS'], ['a', 'b', 'WRITES_TO'], ['b', 'c', 'CALLS'], ['c', 'a', 'CALLS'], ['c', 'd', 'CALLS'], ['a', 'nacos', 'CONFIGURED_BY'],
].map(([sourceCiCode, targetCiCode, relationType], id) => ({ id, sourceCiCode, targetCiCode, relationType }));
assert.equal(util.neighborhood(nodes, edges, '', 0).edges.length, 6);
assert.equal(util.neighborhood(nodes, edges, '', 0, true).edges.length, 7);
assert.deepEqual(util.neighborhood(nodes, edges, 'a', 1).nodes.map(node => node.ciCode), ['a', 'b', 'c']);
assert.deepEqual(util.neighborhood(nodes, edges, 'a', 2).nodes.map(node => node.ciCode), ['a', 'b', 'c', 'd']);
assert.equal(util.neighborhood(nodes, edges, 'a', 1).edges.length, 5, 'cycles, reverse and parallel edges remain visible');
assert.equal(util.graphStructure(nodes, edges), util.graphStructure(nodes.map(node => ({ ...node, metrics: { rps: 73 } })), edges.map(edge => ({ ...edge, rps: 90 }))));
const wheel = (extra = {}) => ({ deltaX: 0, deltaY: 1, deltaMode: 1, ctrlKey: false, metaKey: false, altKey: false, ...extra });
assert.deepEqual(util.wheelIntent(wheel(), 'touchpad', 560), { kind: 'pan', x: -0, y: -16 });
assert.equal(util.wheelIntent(wheel({ ctrlKey: true }), 'mouse', 560).kind, 'browser');
assert.equal(util.wheelIntent(wheel({ ctrlKey: true }), 'touchpad', 560).kind, 'zoom');
assert.equal(util.wheelIntent(wheel({ altKey: true }), 'touchpad', 560).kind, 'zoom');
assert.equal(obs.effectiveHealth({ health: 'UNKNOWN', observation: { status: 'FAILED' }, observedAt: new Date().toISOString() }), 'UNKNOWN');
assert.equal(obs.effectiveHealth({ health: 'HEALTHY', observation: { status: 'PARTIAL' }, observedAt: '2020-01-01' }), 'HEALTHY', 'V3 source health uses backend evidence freshness, not a second 90s UI cutoff');
console.log('PASS GRAPH-01/04 protocol: governance layers, cycle/reverse/parallel edges, one/two-hop focus, stable metric structure and distinct mouse/touchpad wheel semantics');

const g6Require = createRequire(require.resolve('@antv/g6'));
const { GridLayout } = g6Require('@antv/layout'); const { Graph } = g6Require('@antv/graphlib');
for (const [count, edgeCount] of [[50, 100], [200, 400]]) {
  const data = { nodes: Array.from({ length: count }, (_, index) => ({ id: `fixture-${index}`, data: { displayOrder: count - index } })),
    edges: Array.from({ length: edgeCount }, (_, index) => ({ id: `edge-${index}`, source: `fixture-${index % count}`, target: `fixture-${(index * 7 + 3) % count}`, data: {} })) };
  const begin = performance.now(); const result = await new GridLayout(layout.topologyGridLayout).execute(new Graph(data)); const elapsed = performance.now() - begin;
  assert.equal(result.nodes.length, count); assert(result.nodes.every(node => Number.isFinite(node.data.x) && Number.isFinite(node.data.y)));
  assert.equal(new Set(result.nodes.map(node => `${node.data.x}:${node.data.y}`)).size, count);
  console.log(`PASS actual G6 layout fixture ${count}/${edgeCount}: ${elapsed.toFixed(2)} ms layout computation; browser rendering/frame latency NOT_RUN`);
}

const storage = new Map(); globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) };
globalThis.window = { matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }) };
const storedView = { version: 1, structure: 'a', zoom: .7, position: [12, 23], positions: { a: { x: 100, y: 200 } } };
storage.set('opsagent-graph:account-a:PROD:topology:v3', JSON.stringify(storedView));
assert.deepEqual(util.readGraphView('account-a:PROD:topology:v3'), storedView);
assert.equal(util.readGraphView('account-b:PROD:topology:v3'), undefined);
assert.equal(util.readGraphView('account-a:DEMO:topology:v3'), undefined);
storage.set('opsagent-graph:broken', JSON.stringify({ ...storedView, zoom: 1e6 })); assert.equal(util.readGraphView('broken'), undefined);

const icons = new Proxy({}, { get: () => ({ render: () => vue.h('i') }) });
const Stub = { render: () => vue.h('div') };
const auth = vue.reactive({ user: { userId: 7, username: 'operator' }, isAdmin: false, isOps: true, isDemo: false });
let modelStarts = 0;
const assistant = vue.reactive({ open: false, minimized: false, busy: false, show() { this.open = true; modelStarts++; }, setContext() {} });
const inbox = vue.reactive({ open: false, start() {}, stop() {} });
const routeNav = evaluate(read('utils/route-navigation.ts'));
const nav = evaluate(read('data/navigation.ts'), { '@lucide/vue': icons, '@/utils/route-navigation': routeNav });
function component(path, imports = {}) {
  const { descriptor } = compiler.parse(read(path)); const script = compiler.compileScript(descriptor, { id: path });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename: path, id: path, compilerOptions: { bindingMetadata: script.bindings } });
  assert.deepEqual(template.errors, []);
  const compiled = evaluate(script.content, { vue, 'vue-router': vr, '@lucide/vue': icons,
    '@/stores/auth': { useAuthStore: () => auth }, '@/stores/ai-assistant': { useAiAssistantStore: () => assistant },
    '@/stores/approval-inbox': { useApprovalInboxStore: () => inbox }, '@/data/navigation': nav,
    '@/utils/route-navigation': routeNav, '@/utils/ai-context': evaluate(read('utils/ai-context.ts')),
    ...Object.fromEntries(['AppSidebar', 'GlobalTopbar', 'automation/GlobalApprovalInbox', 'ai/AiAssistantOrb', 'ai/AiAssistantDock'].map(name => [`@/components/${name}.vue`, Stub])), ...imports }).default;
  compiled.render = evaluate(template.code, { vue }).render; return compiled;
}
// Actual Vue renderer and RouterView, including a pending lazy route. Observe committed frames after each Vue flush.
const renderer = vue.createRenderer({
  createElement: tag => ({ tag, children: [], props: {}, parent: null }), createText: text => ({ text, children: [], parent: null }), createComment: text => ({ comment: text, children: [], parent: null }),
  insert(child, parent, anchor) { if (child.parent) this.remove?.(child); const old = parent.children.indexOf(child); if (old >= 0) parent.children.splice(old, 1); const index = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(index < 0 ? parent.children.length : index, 0, child); child.parent = parent; },
  remove(child) { if (child.parent) { const index = child.parent.children.indexOf(child); if (index >= 0) child.parent.children.splice(index, 1); child.parent = null; } },
  setText(node, text) { node.text = text; }, setElementText(node, text) { node.text = text; node.children = []; },
  parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1],
  patchProp(node, key, _previous, value) { node.props[key] = value; },
});
function collect(node, predicate) { return [...(predicate(node) ? [node] : []), ...node.children.flatMap(child => collect(child, predicate))]; }
const Layout = component('layouts/AppLayout.vue');
const body = page => ({ render: () => vue.h('section', { 'data-page': page }, page) });
let releaseSlow;
const delayed = new Promise(resolve => { releaseSlow = resolve; });
const router = vr.createRouter({ history: vr.createMemoryHistory(), routes: [{ path: '/', component: Layout, children: [
  { path: 'tickets', name: 'tickets', component: body('tickets'), meta: routeNav.pageMeta('events', '事件处置', 'standard') },
  ...['alerts', 'sla', 'oncall'].map(name => ({ path: `itsm/${name}`, name, component: name === 'sla' ? () => delayed.then(() => body(name)) : body(name), meta: routeNav.pageMeta('events', name, 'standard', true) })),
] }] });
await router.push('/tickets'); await router.isReady();
const root = { children: [] }; const app = renderer.createApp({ render: () => vue.h(vr.RouterView) }); app.use(router); app.mount(root);
const frames = [];
function frame(expected) {
  const pages = collect(root, node => node.props?.['data-page']); assert.equal(pages.length, 1); assert.equal(pages[0].props['data-page'], expected);
  const secondary = collect(root, node => node.props?.class === 'module-secondary-navigation'); assert.equal(secondary.length, expected === 'tickets' ? 0 : 1);
  const active = secondary.flatMap(node => collect(node, child => child.props?.['aria-current'] === 'page'));
  if (expected !== 'tickets') assert.equal(active[0]?.props.href, `/itsm/${expected}`);
  frames.push({ page: expected, secondary: secondary.length });
}
frame('tickets'); const pending = router.push('/itsm/sla'); await vue.nextTick(); frame('tickets'); releaseSlow(); await pending; await vue.nextTick(); frame('sla');
for (const name of ['alerts', 'sla', 'oncall']) {
  await router.push(`/itsm/${name}`); await vue.nextTick(); frame(name);
  await router.push(routeNav.parentLocation(router.currentRoute.value)); await vue.nextTick(); frame('tickets');
}
await router.push('/itsm/alerts?ciCode=rag'); await vue.nextTick(); const before = collect(root, node => node.props?.['data-page'])[0];
await router.replace('/itsm/alerts?ciCode=queue'); await vue.nextTick(); assert.equal(collect(root, node => node.props?.['data-page'])[0], before, 'query update does not remount business page');
app.unmount();
console.log(`PASS NAV-01/02 source/runtime seam: actual Vue shell/RouterView ${frames.length} committed frames and delayed route; body/nav agree, query preserves instance. Browser slow-CPU frame matrix NOT_RUN`);

const Orb = component('components/ai/AiAssistantOrb.vue');
function mountOrb() { const tree = { children: [] }; const vnode = vue.h(Orb); renderer.render(vnode, tree); return { tree, state: vnode.component.setupState, unmount() { renderer.render(null, tree); } }; }
let orb = mountOrb(); await vue.nextTick(); await vue.nextTick();
assert.equal(orb.state.introduction, true); assert.equal(modelStarts, 0); assert.equal(assistant.open, false);
assert.equal(storage.get('opsagent-ai-guide:7:v3'), 'seen');
orb.state.dismiss(); orb.state.toggleMotion(); assert.equal(storage.get('opsagent-ai-motion:7'), 'off'); orb.unmount();
orb = mountOrb(); await vue.nextTick(); await vue.nextTick(); assert.equal(orb.state.introduction, false); assert.equal(orb.state.motion, false);
auth.user = { userId: 8, username: 'second' }; await vue.nextTick(); await vue.nextTick(); assert.equal(orb.state.introduction, true); assert.equal(orb.state.motion, true);
orb.state.begin(); assert.equal(modelStarts, 1); assert.equal(assistant.open, true); orb.unmount();
console.log('PASS AIUI-01/04 actual Vue lifecycle: guide once per account/version, persistent motion preference, no automatic assistant/model start, explicit click shares existing assistant');
