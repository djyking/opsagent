import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const compiler = require('vue/compiler-sfc');
const { createRouter, createMemoryHistory } = require('vue-router');
const { renderToString } = require('vue/server-renderer');
const Stub = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.actions?.()]) };
const Header = { props: ['title'], setup: (props, { slots }) => () => vue.h('header', [props.title, slots.actions?.()]) };
const icons = new Proxy({}, { get: () => Stub });
const compiled = new Map();

function evaluate(source, imports) {
  const js = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true,
  } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => {
    if (Object.hasOwn(imports, id)) return imports[id];
    if (id.endsWith('.vue')) return Stub;
    if (id.endsWith('.css')) return {};
    return require(id);
  }, module, module.exports);
  return module.exports;
}

function compile(filename) {
  if (compiled.has(filename)) return compiled.get(filename);
  const source = readFileSync(new URL('../src/views/' + filename, import.meta.url), 'utf8');
  const { descriptor, errors } = compiler.parse(source, { filename });
  assert.deepEqual(errors, []);
  const script = compiler.compileScript(descriptor, { id: 'support-navigation' });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename,
    id: 'support-navigation', compilerOptions: { bindingMetadata: script.bindings } });
  assert.deepEqual(template.errors, []);
  const result = { script: script.content, render: evaluate(template.code, { vue }).render };
  compiled.set(filename, result);
  return result;
}

// Compile and execute the real page setup/template. Only child visuals and API transport
// are stubbed; navigation and RouterLink rendering use a real Vue Router memory history.
async function fixture(filename, initialUrl, extra = {}, props = {}) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    '/operations', '/automation', '/itsm/alerts', '/configuration', '/knowledge',
    '/knowledge/review', '/knowledge/index-admin', '/rag/chat',
    '/observability/metrics', '/observability/topology',
  ].map(path => ({ path, component: Stub })) });
  await router.push(initialUrl);
  const route = { get query() { return router.currentRoute.value.query; },
    get hash() { return router.currentRoute.value.hash; } };
  const mounted = [], unmounting = [];
  let pendingNavigation = Promise.resolve();
  const { script, render } = compile(filename);
  const component = evaluate(script, {
    vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounting.push(fn) },
    'vue-router': { useRoute: () => route, useRouter: () => ({
      replace: destination => pendingNavigation = router.replace(destination),
      push: destination => pendingNavigation = router.push(destination),
    }) },
    '@lucide/vue': icons, '@/components/PageHeader.vue': Header, ...extra,
  }).default;
  const scope = vue.effectScope();
  const state = scope.run(() => component.setup(props, { expose() {} }));
  return {
    router, state,
    async mount() { await Promise.all(mounted.map(fn => fn())); await vue.nextTick(); },
    async settle() { await vue.nextTick(); await pendingNavigation; await vue.nextTick(); },
    async html() {
      const context = vue.proxyRefs({ ...state, ...props });
      const app = vue.createSSRApp({ render: () => render(context, [], props, context, {}, {}) });
      app.use(router);
      return renderToString(app);
    },
    stop() { unmounting.forEach(fn => fn()); scope.stop(); },
  };
}

function operationsTransport() {
  const calls = [], unexpected = [];
  const operationsApi = new Proxy({
    overview: async minutes => {
      calls.push(['overview', minutes]);
      return { capturedAt: '2026-09-06T08:00:00Z', status: 'UNKNOWN', summary: '等待真实采样',
        windowMinutes: minutes, targets: [], metrics: [], risks: [],
        nacos: { status: 'UNKNOWN', services: [], configurations: [] },
        sentinel: { status: 'UNKNOWN', rules: [] } };
    },
    workflows: async () => {
      calls.push(['workflows']);
      return [{ code: 'ISOLATED_DEMO', scheduleEnabled: true, intervalMinutes: 99 },
        { code: 'HEALTH_CHECK', scheduleEnabled: true, intervalMinutes: 17 }];
    },
  }, { get: (target, key) => target[key] ?? (() => {
    unexpected.push(key);
    throw new Error(`Unexpected operations API: ${String(key)}`);
  }) });
  return { calls, unexpected, imports: { '@/api/operations': { operationsApi } } };
}

{
  const api = operationsTransport();
  const page = await fixture('OperationsView.vue',
    '/operations?tab=workflows&service=redis&service=gateway#evidence', api.imports);
  try {
    await page.mount();
    await page.settle();
    assert.equal(page.router.currentRoute.value.path, '/automation');
    assert.deepEqual(page.router.currentRoute.value.query, { tab: 'inspection', service: ['redis', 'gateway'] });
    assert.equal(page.router.currentRoute.value.hash, '#evidence');
    assert.deepEqual(api.calls, [], 'A legacy initial visit should redirect without starting redundant API reads');
    assert.deepEqual(api.unexpected, []);
  } finally { page.stop(); }
}
console.log('PASS legacy inspection initial URL: real redirect preserves repeated query values/hash and starts no API reads');

{
  const api = operationsTransport();
  const page = await fixture('OperationsView.vue', '/operations?tab=overview', api.imports);
  try {
    await page.mount();
    assert.deepEqual(api.calls, [['overview', 60], ['workflows']]);
    const overview = await page.html();
    assert.match(overview, /每17分钟/);
    assert.doesNotMatch(overview, /每99分钟/);
    for (const [tab, label] of [['overview', '运行态势'], ['topology', '服务与拓扑'], ['governance', '注册与流控']]) {
      await page.router.push({ path: '/operations', query: { tab } });
      await page.settle();
      const html = await page.html();
      const nav = html.match(/<nav\b[^>]*class="operations-tabs"[\s\S]*?<\/nav>/)?.[0];
      assert.ok(nav, 'The observation navigation should render');
      assert.equal((nav.match(/<button\b/g) || []).length, 3);
      const selected = nav.match(/<button[^>]*aria-current="page"[^>]*>([\s\S]*?)<\/button>/)?.[1];
      assert.equal(selected?.replace(/<[^>]+>/g, '').trim(), label);
      for (const href of ['/automation?tab=inspection', '/itsm/alerts', '/configuration']) {
        assert.ok(nav.includes(`href="${href}"`), `${tab}: missing ${href}`);
      }
      assert.doesNotMatch(html, /operations-(?:workflow-card|history|run-detail)/);
    }
    await page.router.push('/operations?tab=workflows&service=rabbitmq#latest');
    await page.settle();
    assert.equal(page.router.currentRoute.value.path, '/automation');
    assert.deepEqual(page.router.currentRoute.value.query, { tab: 'inspection', service: 'rabbitmq' });
    assert.equal(page.router.currentRoute.value.hash, '#latest');
    assert.deepEqual(api.calls, [['overview', 60], ['workflows']], 'Query navigation must not invoke the old inspection APIs');
    assert.deepEqual(api.unexpected, []);
  } finally { page.stop(); }
}
console.log('PASS observation render: three active tabs, real secondary links, read-only HEALTH_CHECK summary and same-instance legacy redirect');

{
  const api = operationsTransport(); const wrapped = api.imports['@/api/operations'].operationsApi;
  const metric = (job, id = 'heap') => ({ id, job, label: id === 'heap' ? 'JVM堆使用率' : 'HTTP错误率', unit: '%', currentValue: 12,
    forecastValue: 13, sampleCount: 2, status: 'OK', reason: '真实采样', method: '线性趋势', points: [], observedAt: '2026-09-06T08:00:00Z' });
  const metricsApi = { overview: async minutes => ({ ...(await wrapped.overview(minutes)),
    targets: [{ service: 'opsagent-rag', ciCode: 'ops-rag-service', health: 'up' }, { service: 'opsagent-auth', ciCode: 'ops-auth-service', health: 'up' }],
    metrics: [metric('opsagent-rag'), metric('opsagent-auth'), metric('all'), metric('opsagent-rag', 'http5xx')] }), workflows: wrapped.workflows };
  const page = await fixture('OperationsView.vue', '/observability/metrics?tab=governance&ciCode=ops-rag-service&environment=PROD',
    { '@/api/operations': { operationsApi: metricsApi } }, { metricsOnly: true });
  try {
    await page.mount(); await page.settle();
    assert.equal(page.router.currentRoute.value.path, '/observability/metrics');
    assert.equal(page.state.activeTab.value, 'overview');
    let rendered = await page.html();
    assert.match(rendered, /指标与采集/); assert.match(rendered, /服务运行切面/); assert.match(rendered, /趋势与风险预估/);
    assert.doesNotMatch(rendered, /class="operations-tabs"/); assert.doesNotMatch(rendered, /class="operations-governance-grid"/);
    assert.equal(page.state.serviceFilter.value, 'opsagent-rag');
    assert.deepEqual(page.state.visibleMetrics.value.map(item => item.job), ['opsagent-rag']);
    page.state.metricKind.value = 'http5xx'; assert.equal(page.state.visibleMetrics.value[0].id, 'http5xx');
    page.state.serviceFilter.value = 'opsagent-auth'; page.state.changeServiceFilter(); await page.settle();
    assert.equal(page.router.currentRoute.value.query.ciCode, 'ops-auth-service');
    await page.router.push('/observability/metrics?ciCode=unmapped'); await page.settle();
    assert.equal(page.state.serviceFilter.value, '__unmapped__'); assert.equal(page.state.visibleMetrics.value.length, 0);
    page.state.goTopology('ops-rag-service'); await page.settle();
    assert.equal(page.router.currentRoute.value.path, '/observability/topology'); assert.equal(page.router.currentRoute.value.query.ciCode, 'ops-rag-service');
  } finally { page.stop(); }
}
console.log('PASS retained metrics-only workspace: real heap/HTTP history, collection targets, hidden legacy tabs, service-scope mapping and topology return');

{
  const auth = vue.reactive({ isAdmin: true, isDemo: false });
  const requests = [];
  const page = await fixture('KnowledgeView.vue', '/knowledge', {
    '@/stores/auth': { useAuthStore: () => auth },
    '@/api/http': { request: async options => {
      requests.push(options);
      assert.equal(options.url, '/api/knowledge/bases');
      assert.equal(options.method, undefined);
      return [];
    } },
    '@/utils/datetime': { formatDateTime: String, formatShortDateTime: String },
    '@/composables/usePageFeedback': { usePageFeedback: () => ({ show() {} }) },
  });
  try {
    await page.mount();
    for (const [role, isAdmin, isDemo, visible] of [
      ['ADMIN', true, false, true], ['ordinary', false, false, false],
      ['DEMO', false, true, false], ['ADMIN+DEMO', true, true, false], ['ADMIN again', true, false, true],
    ]) {
      Object.assign(auth, { isAdmin, isDemo });
      await vue.nextTick();
      const html = await page.html();
      assert.match(html, /知识与经验/);
      assert.match(html, /href="\/knowledge"/);
      for (const href of ['/knowledge/review', '/knowledge/index-admin']) {
        assert.equal(html.includes(`href="${href}"`), visible, `${role}: ${href}`);
      }
    }
    assert.deepEqual(requests, [{ url: '/api/knowledge/bases' }],
      'Role-dependent navigation must not trigger management API requests');
  } finally { page.stop(); }
}
console.log('PASS knowledge real render: ADMIN-only management links, ordinary/DEMO/mixed-role hiding and identity changes');
