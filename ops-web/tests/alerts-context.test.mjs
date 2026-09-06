import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
function evaluate(source, imports = {}) {
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
const Stub = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.toolbar?.(), slots.footer?.(), slots.actions?.(), slots.meta?.()]) };
const source = readFileSync(new URL('../src/views/AlertView.vue', import.meta.url), 'utf8');
const { descriptor } = compiler.parse(source);
const script = compiler.compileScript(descriptor, { id: 'alerts-context-test' });
const template = compiler.compileTemplate({ source: descriptor.template.content, filename: 'AlertView.vue', id: 'alerts-context-test', compilerOptions: { bindingMetadata: script.bindings } });
assert.deepEqual(template.errors, []);
const render = evaluate(template.code, { vue }).render;
const route = vue.reactive({ path: '/itsm/alerts', query: { service: 'rag', environment: 'prod', timeRange: '15m' } });
const calls = [], pending = [];
const api = { alerts: status => { calls.push(status); return new Promise(resolve => pending.push(resolve)); } };
const router = { replace: async ({ query }) => { route.query = query; } };
const imports = {
  vue: { ...vue, onBeforeUnmount() {} }, 'vue-router': { useRoute: () => route, useRouter: () => router },
  '@lucide/vue': new Proxy({}, { get: () => ({ render: () => vue.h('i') }) }), '@/api/modules': { itsmApi: api },
  '@/utils/datetime': { formatDateTime: String, formatRelativeTime: String }, '@/ui/status-map': { statusLabel: String },
  '@/composables/usePageFeedback': { usePageFeedback: () => ({}) },
};
for (const name of ['PageHeader', 'FilterBar', 'EmptyState', 'InlineError', 'LoadingState', 'ListSurface', 'DetailPanel', 'StatusBadge', 'PriorityIndicator', 'experience/GuidedEmptyState']) imports['@/components/' + name + '.vue'] = Stub;
const component = evaluate(script.content, imports).default;
const scope = vue.effectScope();
const state = scope.run(() => component.setup({}, { expose() {} }));
async function flush() { for (let i = 0; i < 6; i++) { await Promise.resolve(); await vue.nextTick(); } }
async function html() {
  const context = vue.proxyRefs(state);
  const app = vue.createSSRApp({ render: () => render(context, [], {}, context, {}, {}) });
  app.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', { href: typeof p.to === 'string' ? p.to : p.to.path + '?' + new URLSearchParams(p.to.query) }, slots.default?.()) });
  return renderToString(app);
}
try {
  assert.deepEqual(calls, ['firing']);
  const rows = [{ id: 1, serviceCode: 'rag', alertName: 'RAG Slow', severity: 'WARNING', currentStatus: 'firing' },
    { id: 2, affectedCiCode: 'redis', alertName: 'Redis Down', severity: 'CRITICAL', currentStatus: 'firing' },
    { id: 3, serviceCode: 'rag-other', alertName: 'Different service', severity: 'WARNING', currentStatus: 'firing' }];
  pending.shift()(rows); await flush();
  assert.deepEqual(state.alerts.value.map(row => row.id), [1]);
  assert.equal(state.criticalCount.value, 0);
  const rendered = await html();
  assert.ok(rendered.includes('当前服务'));
  assert.ok(rendered.includes('/observability/topology?ciCode=rag&amp;environment=prod&amp;timeRange=15m'));
  assert.ok(rendered.includes('最近 200 条可见记录中匹配当前服务'));
  assert.ok(!rendered.includes('Different service'));
  state.selectAlert(rows[0]); await flush();
  assert.equal(route.query.alertId, '1'); assert.equal(route.query.alertService, 'rag');
  assert.equal(state.selected.value.id, 1);
  state.selectAlert(); await flush(); assert.equal(state.selected.value, undefined);
  route.query = { ciCode: 'redis' }; await flush();
  assert.deepEqual(state.alerts.value.map(row => row.id), [2]);
  state.clearService(); await flush(); assert.equal(state.alerts.value.length, 3);
  state.status.value = 'resolved'; state.changeStatus(); await flush();
  assert.equal(calls.at(-1), 'resolved');
  state.status.value = 'firing'; state.changeStatus(); await flush();
  assert.equal(calls.at(-1), 'firing');
  pending[1]([rows[0]]); await flush(); pending[0]([rows[1]]); await flush();
  assert.deepEqual(state.alerts.value.map(row => row.id), [1], 'Old status response cannot replace the newer selection');
} finally { scope.stop(); }
console.log('PASS real AlertView: exact service filter, preserved topology return, selected alert AI scope, status deep links and stale-response rejection');
