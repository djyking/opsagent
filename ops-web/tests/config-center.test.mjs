import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports = {}) {
  const js = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true,
  } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
function deferred() { let resolve; const promise = new Promise(yes => { resolve = yes; }); return { promise, resolve }; }
const quietVue = { ...vue, onMounted() {}, onBeforeUnmount() {} };
const Stub = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.actions?.()]) };
const field = (key, value) => ({ key, value, label: '参数 ' + key, category: '连接配置', source: '环境变量', verification: 'RUNTIME_RESOLVED' });
const item = id => ({ id, name: id + '.yaml', serviceId: 'ops-rag-service', source: 'NACOS', namespace: 'public', group: 'DEFAULT_GROUP', dataId: id + '.yaml',
  identity: { environment: 'test', sourceInstanceId: 'nacos-test', targetScope: ['ops-rag-service'] },
  capabilities: { canEdit: false, canVerifyApplied: false, reasons: { edit: '源配置只读' } } });
const detail = id => ({ item: item(id), status: 'AVAILABLE', content: '{"info":{"middleware":{"nacos-config":"connected"}}}',
  revision: 'a'.repeat(64), observedAt: '2026-09-07T06:44:00Z', message: '实际源片段', overview: {
    status: 'AVAILABLE', serviceId: 'ops-rag-service', observedAt: '2026-09-07T06:44:00Z', instanceId: 'runtime-process',
    message: '运行解析不等同业务已应用', fields: Array.from({ length: 10 }, (_, index) => field('key-' + index, 'value-' + index)),
  } });
function fixture() {
  const auth = vue.reactive({ token: 'token-a', identity: 'actor-a' }); const calls = [];
  const api = {
    list: async () => ({ items: [item('current')], status: 'AVAILABLE' }),
    detail: async id => detail(id), history: async id => { calls.push(id); return { status: 'AVAILABLE', items: [], message: '真实版本' }; },
  };
  const scope = vue.effectScope();
  const module = evaluate(read('composables/useConfigCenter.ts'), { vue: quietVue,
    '@/api/configCenter': { configCenterApi: api }, '@/stores/auth': { useAuthStore: () => auth } });
  const manager = scope.run(() => module.useConfigCenter(() => ''));
  return { auth, calls, api, manager, stop: () => scope.stop() };
}
function page(manager) {
  const path = 'views/observability/ConfigCenterView.vue';
  const { descriptor, errors } = compiler.parse(read(path), { filename: path }); assert.deepEqual(errors, []);
  const script = compiler.compileScript(descriptor, { id: 'config-center-test' });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename: path, id: 'config-center-test', compilerOptions: { bindingMetadata: script.bindings } });
  assert.deepEqual(template.errors, []);
  const component = evaluate(script.content, {
    vue: quietVue, '@lucide/vue': new Proxy({}, { get: () => Stub }), 'vue-router': { useRoute: () => ({ query: {} }) },
    '@/composables/useConfigCenter': { useConfigCenter: () => manager }, './ObservabilityWorkspaceView.vue': Stub,
    '@/components/configuration/ConfigurationFiles.vue': Stub,
    ...Object.fromEntries(['InlineError', 'LoadingState', 'EmptyState', 'BaseModal'].map(name => ['@/components/' + name + '.vue', Stub])),
    '@/styles/pages/observability-governance.css': {}, '@/styles/pages/config-center.css': {},
  }).default;
  const render = evaluate(template.code, { vue }).render;
  const state = component.setup({}, { expose() {} });
  return { state, async html() {
    const context = vue.proxyRefs(state);
    const app = vue.createSSRApp({ render: () => render(context, [], {}, context, {}, {}) });
    app.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', { href: p.to }, slots.default?.()) });
    return renderToString(app);
  } };
}

{
  const app = fixture();
  try {
    await app.manager.load(); assert.equal(app.calls.length, 0, 'History must not delay initial configuration');
    const old = deferred(); app.api.history = () => old.promise;
    const pending = app.manager.loadHistory(); await app.manager.select('new');
    assert.equal(app.manager.historyLoading.value, false);
    old.resolve({ status: 'AVAILABLE', items: [{ id: 99 }], message: 'old' }); await pending;
    assert.equal(app.manager.history.value, undefined, 'Late history must not attach to a newly selected source');
    const another = deferred(); app.api.history = () => another.promise;
    const changedActor = app.manager.loadHistory(); app.auth.identity = 'actor-b';
    another.resolve({ status: 'AVAILABLE', items: [{ id: 88 }] }); await changedActor;
    assert.ok(!app.manager.history.value?.items.some(v => v.id === 88), 'Actor transition must discard older history');
  } finally { app.stop(); }
}
console.log('PASS lazy history does not block summary and old selection/identity responses cannot restore it');

{
  const app = fixture();
  try {
    await app.manager.load();
    const delayed = deferred(); app.api.history = () => delayed.promise;
    const pending = app.manager.loadHistory(); app.auth.token = 'refreshed-token-a';
    delayed.resolve({ status: 'AVAILABLE', items: [{ id: 77 }] }); await pending;
    assert.equal(app.manager.history.value.items[0].id, 77, 'Token refresh for the same actor must retain current history');
  } finally { app.stop(); }
}
console.log('PASS token refresh preserves in-flight history for the same actor');

{
  const app = fixture();
  try {
    await app.manager.load(); const view = page(app.manager); const html = await view.html();
    assert.match(html, /目标实例当前解析值/); assert.match(html, /已取得运行快照/);
    assert.equal(view.state.primaryFields.value.length, 7); assert.equal(view.state.moreFields.value.length, 3);
    assert.equal([...html.matchAll(/<details\b[^>]*\bopen\b/g)].length, 0, 'Secondary content is collapsed by default');
    assert.match(html, /静态配置文字，不能证明实时连接正常/);
    assert.ok(!html.includes('创建受控变更'), 'Readonly source has no edit action');
    app.manager.detail.value = { ...detail('current'), overview: { status: 'UNAVAILABLE', serviceId: 'ops-rag-service', message: '目标读取失败', fields: [] } };
    const failed = await view.html(); assert.ok(!failed.includes('value-0')); assert.ok(!failed.includes('已取得运行快照')); assert.match(failed, /目标读取失败/);
    app.manager.detail.value.overview.fields = [{ ...field('ops.rag.top-k', '5'), verification: 'SOURCE_ONLY', source: 'Nacos 源配置' }];
    const sourceOnly = await view.html(); assert.match(sourceOnly, /已读取的源参数/); assert.match(sourceOnly, /仅源配置 · 未核实采用/);
  } finally { app.stop(); }
}
console.log('PASS actual configuration view renders bounded summary, collapsed source/evidence, and honest unavailable/source-only states');
