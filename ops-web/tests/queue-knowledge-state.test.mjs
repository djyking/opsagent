import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports = {}) { const module = { exports: {} }; new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(name => imports[name] ?? {}, module, module.exports); return module.exports; }
function fixture(path, imports) { const { descriptor } = compiler.parse(read(path)); const script = compiler.compileScript(descriptor, { id: path }); const unmount = [], scope = vue.effectScope(); const component = evaluate(script.content, { ...imports, vue: { ...vue, onMounted() {}, onBeforeUnmount: fn => unmount.push(fn) } }).default; return { state: scope.run(() => component.setup({}, { expose() {} })), stop() { unmount.forEach(fn => fn()); scope.stop(); } }; }
const later = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const flush = async () => { await vue.nextTick(); await Promise.resolve(); await Promise.resolve(); };
const actor = vue.reactive({ token: 'a', isAdmin: true, isOps: false, isDemo: false, user: { userId: 1, roles: ['ADMIN'] } });
const route = vue.reactive({ name: 'tickets', query: {} });
const common = { '@/stores/auth': { useAuthStore: () => actor }, '@/composables/usePageFeedback': { usePageFeedback: () => ({ show() {} }) }, '@/utils/knowledge-stage': evaluate(read('utils/knowledge-stage.ts')) };
const pageRequests = [], summaryRequests = []; let leave;
const queue = fixture('views/TicketListView.vue', { ...common, 'vue-router': { useRoute: () => route, useRouter: () => ({ async replace({ query }) { route.query = query; } }), onBeforeRouteLeave: fn => { leave = fn; } }, '@/api/modules': { itsmApi: { cis: async () => [] }, ticketApi: {} }, '@/api/event-queue': { eventQueueApi: { page(params) { const pending = later(); pageRequests.push({ params, ...pending }); return pending.promise; }, summary(params) { const pending = later(); summaryRequests.push({ params, ...pending }); return pending.promise; } } } });
const queueState = queue.state;
const oldLoad = queueState.load(); await flush();
queueState.filters.eventScope = 'CLOSED'; queueState.filters.affectedCiCode = 'ops-ticket'; queueState.filters.pageNum = 3;
const newLoad = queueState.load(); await flush();
assert.equal(pageRequests[1].params.affectedCiCode, 'ops-ticket'); assert.equal(route.query.pageNum, '3'); assert.equal(route.query.eventScope, 'CLOSED');
pageRequests[1].resolve({ records: [{ id: 3 }], total: 1 }); summaryRequests[1].resolve({ counts: { open: 5 } }); await newLoad;
pageRequests[0].resolve({ records: [{ id: 1 }], total: 999 }); summaryRequests[0].resolve({ counts: { open: 999 } }); await oldLoad;
assert.deepEqual(queueState.page.value.records, [{ id: 3 }]); assert.equal(queueState.summary.value.counts.open, 5, 'late old filter results cannot replace the current scope');
globalThis.window = { scrollY: 420 }; const scrolls = new Map(); globalThis.sessionStorage = { setItem: (key, value) => scrolls.set(key, value) }; leave(); assert.equal([...scrolls.values()][0], '420');
actor.user = { userId: 2, roles: ['USER'] }; await flush();
assert.equal(queueState.page.value.records.length, 0); assert.equal(queueState.summary.value, undefined, 'account change removes previous user evidence immediately');
pageRequests[2].resolve({ records: [{ id: 5 }], total: 1 }); summaryRequests[2].resolve({ counts: { open: 1 } }); await flush(); queue.stop();
console.log('PASS queue scope: exact service/page URL, stale filter suppression, scoped back-navigation position and account evidence cleanup');

const reads = []; const knowledge = fixture('views/KnowledgeView.vue', { ...common, 'vue-router': { useRoute: () => ({ query: {} }) }, '@/api/http': { request({ url }) { const pending = later(); reads.push({ url, ...pending }); return pending.promise; } } });
const state = knowledge.state;
state.selectedBaseId.value = 1; const oldDocuments = state.loadDocuments(); state.selectedBaseId.value = 2; const newDocuments = state.loadDocuments();
reads[1].resolve([{ id: 2, original_name: 'current' }]); await newDocuments; reads[0].resolve([{ id: 1, original_name: 'old' }]); await oldDocuments;
assert.equal(state.documents.value[0].id, 2, 'late content from a previous library is discarded');
state.detailDocument.value = { id: 2, original_name: 'current' }; const chunks = state.showChunks(state.detailDocument.value); reads[2].resolve([{ id: 22, content: 'real text' }]); await chunks;
assert.equal(state.detailDocument.value, undefined, 'opening body chunks replaces the detail dialog'); assert.equal(state.chunkDocument.value.id, 2); assert.equal(state.chunks.value[0].content, 'real text');
const oldChunks = state.showChunks({ id: 2, original_name: 'current' }); actor.user = { userId: 3, roles: ['USER'] }; await flush(); reads[3].resolve([{ id: 99, content: 'previous account' }]); await oldChunks;
assert.equal(state.chunkDocument.value, undefined); assert.equal(state.chunks.value.length, 0); assert.equal(state.documents.value.length, 0);
reads[4].resolve([]); await flush(); knowledge.stop();
console.log('PASS knowledge state: library race isolation, one body dialog and old-account content rejection');

const topologyCalls = []; let graph = { nodes: [{ ciCode: 'redis', environment: 'PROD' }, { ciCode: 'app', environment: 'PROD' }], edges: [{ sourceCiCode: 'app', targetCiCode: 'redis' }] };
const mapFixture = (code, environment) => { const { descriptor } = compiler.parse(read('components/events/EventDependencyMap.vue')); const script = compiler.compileScript(descriptor, { id: 'event-map' }); const props = vue.reactive({ code, environment }); const scope = vue.effectScope(); const component = evaluate(script.content, { vue: { ...vue, onMounted() {}, onBeforeUnmount() {} }, '@/components/cmdb/topology': { environmentNames: { PROD: '生产', DEMO: '隔离演练' } }, '@/api/observability': { observabilityApi: { topology: async params => { topologyCalls.push(params); return graph; } } } }).default; return { props, state: scope.run(() => component.setup(props, { expose() {} })), stop: () => scope.stop() }; };
const map = mapFixture('redis', 'CORE'); await map.state.load(); assert.deepEqual(topologyCalls.at(-1), { environment: 'ALL', timeRange: '15m', mode: 'CONFIGURED' }); assert.equal(map.state.linkEnvironment.value, 'PROD'); assert.equal(map.state.nodes.value.length, 2);
graph = { ...graph, nodes: [...graph.nodes, { ciCode: 'redis', environment: 'DEMO' }] }; await map.state.load(); assert.equal(map.state.scopeAmbiguous.value, true); assert.equal(map.state.nodes.value.length, 0, 'an unmapped event cannot merge different environments with the same service identity');
map.props.environment = 'DEMO'; await map.state.load(); assert.equal(topologyCalls.at(-1).environment, 'DEMO'); assert.equal(topologyCalls.at(-1).mode, 'HYBRID'); map.stop();
console.log('PASS event dependency scope: registered environment provenance, no guessed CORE mapping and ambiguous-environment exclusion');

// New visitor entry is a public projection; owned history uses server-side actor scope, not assignee scope.
{
  const visitor = vue.reactive({ isDemo: true, isAdmin: false, user: { userId: -77, roles: ['DEMO'] } });
  const visitorRoute = vue.reactive({ name: 'tickets', query: {} });
  const requests = [];
  const app = fixture('views/TicketListView.vue', {
    ...common,
    '@/stores/auth': { useAuthStore: () => visitor },
    'vue-router': { useRoute: () => visitorRoute, useRouter: () => ({ async replace({ query }) { visitorRoute.query = query; } }), onBeforeRouteLeave() {} },
    '@/api/modules': { itsmApi: { cis: async () => [] }, ticketApi: {} },
    '@/api/event-queue': { eventQueueApi: {
      page: async params => { requests.push(params); return { records: [], total: 0 }; },
      summary: async () => ({ counts: { open: 0 } }),
    } },
  });
  try {
    await app.state.load();
    assert.equal(app.state.showingCases.value, true);
    assert.equal(requests.length, 0);
    visitorRoute.query = { view: 'mine' }; await flush(); await flush();
    assert.equal(app.state.showingCases.value, false);
    assert.equal(requests.at(-1).scope, 'mine');
    assert.equal(requests.at(-1).assigneeId, undefined);
    assert.equal(requests.at(-1).eventScope, undefined, 'Closed personal history is not silently excluded');
    assert.equal(visitorRoute.query.view, 'mine');
    visitorRoute.query = { view: 'cases' }; await flush();
    assert.equal(app.state.showingCases.value, true);
  } finally { app.stop(); }
}
console.log('PASS visitor case default, owned server scope and closed history continuity');