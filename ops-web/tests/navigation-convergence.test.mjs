import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const vueRouter = require('vue-router');
const compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports) {
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : id === '@/utils/assistant-placement' ? evaluate(read('utils/assistant-placement.ts'), {}) : require(id), module, module.exports);
  return module.exports;
}
const Stub = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.actions?.(), slots.tabs?.()]) };
const icons = new Proxy({}, { get: () => ({ render: () => vue.h('i') }) });
const routeNavigation = evaluate(read('utils/route-navigation.ts'), {});
const aiContext = evaluate(read('utils/ai-context.ts'), {});
const navigation = evaluate(read('data/navigation.ts'), { '@lucide/vue': icons, '@/utils/route-navigation': routeNavigation });
const workspaceActions = evaluate(read('data/workspace-actions.ts'), {});
const { navigationGroups, navigationFor } = navigation;
assert.deepEqual(navigationGroups.map(group => group.items.map(item => [item.to, item.label])), [
  [['/dashboard', '运行总览'], ['/tickets', '事件处置'], ['/automation', '自动化中心']],
  [['/observability/topology', '服务与观测'], ['/knowledge', '知识与经验']],
]);
const ownership = new Map([
  ['/tickets/2057', '/tickets'], ['/itsm/alerts', '/tickets'], ['/itsm/sla', '/tickets'], ['/itsm/oncall', '/tickets'],
  ['/knowledge/review', '/knowledge'], ['/knowledge/index-admin', '/knowledge'], ['/configuration', '/observability/topology'],
  ['/observability/catalog', '/observability/topology'], ['/observability/inspections', '/observability/topology'],
  ['/observability/config', '/observability/topology'], ['/observability/traffic', '/observability/topology'],
  ['/observability/metrics', '/observability/topology'],
  ['/rag/chat', ''], ['/notifications', ''], ['/admin', ''],
]);
for (const [path, expected] of ownership) assert.equal(navigationFor(path).primaryTo, expected, path);

// Use the real router with memory history and inert page components. Redirects and auth guards execute unchanged.
const auth = vue.reactive({ isAuthenticated: true, user: { userId: 7 }, isAdmin: false, isDemo: false, logout() { this.isAuthenticated = false; } });
const routerSource = read('router/index.ts').replaceAll('import.meta.env.DEV', 'false')
  .replace(/component:\s*\(\)\s*=>\s*import\([^)]+\)/g, 'component: { render: () => null }');
const router = evaluate(routerSource, {
  'vue-router': { ...vueRouter, createWebHistory: vueRouter.createMemoryHistory },
  '@/stores/auth': { useAuthStore: () => auth },
  '@/utils/route-navigation': routeNavigation,
  '@/api/session': { ensureAccessToken: async () => 'test-active-session', SessionError: class SessionError extends Error {} },
}).default;
for (const [from, expected] of [
  ['/events?keyword=Redis#queue', '/tickets?keyword=Redis#queue'], ['/events/2057?source=alert#evidence', '/tickets/2057?source=alert#evidence'],
  ['/system/monitor?tab=governance', '/observability/traffic'], ['/itsm/cmdb?service=gateway', '/observability/catalog?service=gateway'],
  ['/operations?tab=workflows&service=redis#history', '/observability/inspections?service=redis#history'],
  ['/configuration?ciCode=ops-rag-service', '/observability/config?ciCode=ops-rag-service'],
  ['/configurations?ciCode=ops-demo-order-service', '/observability/config/managed?ciCode=ops-demo-order-service'],
]) {
  await router.push(from);
  assert.equal(router.currentRoute.value.fullPath, expected);
}
for (const path of ['/tickets', '/tickets/2057', '/itsm/alerts', '/itsm/sla', '/itsm/oncall', '/observability/topology', '/observability/config', '/knowledge', '/rag/chat']) {
  await router.push(path); assert.equal(router.currentRoute.value.path, path, 'Existing routes remain reachable: ' + path);
}
for (const path of ['/knowledge/review', '/knowledge/index-admin', '/notifications', '/admin']) {
  await router.push(path); assert.equal(router.currentRoute.value.path, '/dashboard', 'Ordinary users retain admin route gate');
  auth.isAdmin = true; await router.push(path); assert.equal(router.currentRoute.value.path, path);
  auth.isAdmin = false;
}
auth.isAuthenticated = false; await router.push('/events/2057?source=alert');
assert.equal(router.currentRoute.value.name, 'login');
assert.equal(router.currentRoute.value.query.redirect, '/tickets/2057?source=alert');
auth.isAuthenticated = true;
console.log('PASS navigation hierarchy, backward-compatible redirects, preserved queries/anchors and original authorization gates');

const route = vue.reactive({ path: '/dashboard', fullPath: '/dashboard', name: 'dashboard', query: {}, meta: {} });
const pushes = [];
const inbox = vue.reactive({ count: 2, open: false, error: '', loading: false, items: [{}, {}], show() { this.open = true; }, start() {}, stop() {} });
const assistant = vue.reactive({ open: false, minimized: false, busy: false, context: {}, show() { this.open = true; }, setContext(value) { this.context = value; }, newSession() {} });
function fixture(path, props = {}) {
  const filename = path.split('/').at(-1);
  const { descriptor, errors } = compiler.parse(read(path), { filename }); assert.deepEqual(errors, []);
  const script = compiler.compileScript(descriptor, { id: 'navigation-convergence-test' });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename, id: 'navigation-convergence-test',
    compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(template.errors, []);
  const component = evaluate(script.content, {
    vue: { ...vue, onMounted() {}, onBeforeUnmount() {} }, '@lucide/vue': icons,
    'vue-router': { useRoute: () => route, useRouter: () => ({ push: destination => pushes.push(destination) }) },
    '@/data/navigation': navigation, '@/stores/auth': { useAuthStore: () => auth },
    '@/data/workspace-actions': workspaceActions,
    '@/stores/approval-inbox': { useApprovalInboxStore: () => inbox },
    '@/stores/ai-assistant': { useAiAssistantStore: () => assistant }, '@/utils/route-navigation': routeNavigation, '@/utils/ai-context': aiContext,
    '@/utils/assistant-welcome': evaluate(read('utils/assistant-welcome.ts'), {}),
    '@/components/AppBreadcrumb.vue': { default: Stub }, '@/components/ai/AiAssistantOrb.vue': { default: Stub }, '@/components/ai/AiAssistantDock.vue': { default: Stub },
    '@/components/AppSidebar.vue': { default: Stub }, '@/components/GlobalTopbar.vue': { default: Stub },
    '@/components/automation/GlobalApprovalInbox.vue': { default: Stub },
    '@/components/BaseModal.vue': { default: Stub },
    '@/api/modules': { authApi: { endExperience: async () => { throw new Error('Navigation test must not end an experience'); } } },
  }).default;
  const render = evaluate(template.code, { vue }).render;
  const routerViewMarker = { name: 'RouterViewInspection' };
  const inspectionRender = evaluate(template.code, { vue: { ...vue, resolveComponent: name => name === 'RouterView' ? routerViewMarker : name } }).render;
  const reactiveProps = vue.reactive(props); const scope = vue.effectScope(); const emitted = [];
  const state = scope.run(() => component.setup(reactiveProps, { expose() {}, emit: (...args) => emitted.push(args) }));
  return { state, props: reactiveProps, emitted, stop: () => scope.stop(), routedContent(Component) {
    const context = vue.proxyRefs({ ...state, ...reactiveProps });
    const root = inspectionRender(context, [], reactiveProps, context, {}, {});
    const find = node => node?.type === routerViewMarker ? node : Array.isArray(node?.children) ? node.children.map(find).find(Boolean) : undefined;
    return find(root).children.default({ Component });
  }, async html() {
    const context = vue.proxyRefs({ ...state, ...reactiveProps });
    const app = vue.createSSRApp({ render: () => render(context, [], reactiveProps, context, {}, {}) });
    app.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', { href: typeof p.to === 'string' ? p.to : router.resolve(p.to).href }, slots.default?.()) });
    app.component('RouterView', { render: () => null });
    return renderToString(app);
  } };
}
{
  const app = fixture('components/AppSidebar.vue', { collapsed: false, mobileOpen: false, isAdmin: false, initials: 'O', username: 'Operator', roleLabel: '用户' });
  try {
    for (const [path, expected] of ownership) {
      route.path = path;
      const html = await app.html();
      const selected = [...html.matchAll(/<a href="([^"]+)"[^>]*aria-current="page"/g)].map(match => match[1]);
      assert.deepEqual(selected, expected ? [expected] : [], path);
      assert.equal([...html.matchAll(/<section class="sidebar-group"/g)].length, 2);
    }
  } finally { app.stop(); }
}
console.log('PASS actual AppSidebar: exactly one owning primary item for each secondary page and no false AI/admin selection');
{
  const app = fixture('components/GlobalTopbar.vue', { isAdmin: false, mobileOpen: false });
  try {
    for (const [admin, demo] of [[false, false], [true, false], [false, true], [true, true]]) {
      auth.isAdmin = admin; auth.isDemo = demo; app.props.isAdmin = admin;
      const html = await app.html();
      assert.ok(html.includes('aria-label="AI 助手，分析当前页面上下文"'), 'The shared assistant remains a global entry for every role');
      assert.equal(html.includes('href="/admin"'), admin && !demo);
      assert.equal(html.includes('href="/notifications"'), admin && !demo);
      assert.equal(html.includes('href="/tickets?create=1"'), !demo);
      assert.ok(html.includes('待处理审批 2 项'));
      assert.ok(html.includes('通用问答与知识检索'));
    }
    app.state.assistant.show(); assert.equal(assistant.open, true);
    app.state.keyword.value = '  Redis  '; app.state.searchTicket();
    assert.deepEqual(pushes.at(-1), { path: '/tickets', query: { keyword: 'Redis' } });
    const length = pushes.length; app.state.keyword.value = ' '; app.state.searchTicket(); assert.equal(pushes.length, length);
    app.state.createMenu.value = { open: true }; app.state.managementMenu.value = { open: true };
    route.fullPath = '/operations?tab=topology'; await vue.nextTick();
    assert.equal(app.state.createMenu.value.open, false); assert.equal(app.state.managementMenu.value.open, false);
    app.state.approvalInbox.show(); assert.equal(inbox.open, true);
  } finally { app.stop(); auth.isAdmin = false; auth.isDemo = false; }
}
console.log('PASS actual GlobalTopbar: general assistant, management role gates, real event search and shared global approval entry');
{
  const app = fixture('components/dashboard/WorkspaceLauncher.vue');
  try {
    auth.isAdmin = true; app.state.query.value = '操作审计';
    assert.ok(app.state.matches.value.some(item => item.id === 'audit'));
    auth.isDemo = true; assert.ok(app.state.matches.value.every(item => !item.admin));
    app.state.query.value = '持续巡检';
    await app.state.choose(app.state.matches.value[0]);
    assert.deepEqual(pushes.at(-1), { path: '/observability/inspections' });
    app.state.query.value = 'AI助手';
    await app.state.choose(app.state.matches.value[0]);
    assert.deepEqual(pushes.at(-1), { path: '/rag/chat', query: { new: '1' } });
  } finally { app.stop(); auth.isAdmin = false; auth.isDemo = false; }
}
console.log('PASS actual WorkspaceLauncher: visitor management gate and current inspection/assistant destinations');

const previousWindow = globalThis.window; const previousStorage = globalThis.localStorage;
globalThis.window = { matchMedia: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }) };
globalThis.localStorage = { getItem: () => null, setItem() {} };
try {
  const app = fixture('layouts/AppLayout.vue');
  try {
    for (const path of ['/itsm/alerts', '/itsm/sla', '/itsm/oncall']) {
      route.path = path; route.meta = router.resolve(path).meta; const html = await app.html();
      for (const item of navigation.eventNavigation) assert.ok(html.includes('href="' + item.to + '"'), path);
      assert.equal([...html.matchAll(/aria-current="page"/g)].length, 1);
    }
    route.path = '/tickets'; route.meta = router.resolve('/tickets').meta; assert.ok(!(await app.html()).includes('module-secondary-navigation'), 'Event list owns its own tabs; shell must not duplicate them');
    route.path = '/rag/chat'; route.meta = router.resolve('/rag/chat').meta; assert.ok((await app.html()).includes('具体事件的诊断、审批和执行记录保存在事件工作区'));
    route.path = '/admin'; route.meta = router.resolve('/admin').meta; auth.isAdmin = true; assert.ok((await app.html()).includes('href="/notifications"'));
    auth.isDemo = true; assert.ok(!(await app.html()).includes('href="/notifications"'));
    // Render the actual AppLayout RouterView slot through Vue's keyed patcher.
    // The inert routed component makes mount/unmount observable without API calls.
    let mounts = 0, unmounts = 0;
    const page = { setup() { mounts++; vue.onBeforeUnmount(() => unmounts++); return () => vue.h('div', 'private event contents'); } };
    const host = () => ({ children: [], parent: null });
    const renderer = vue.createRenderer({
      createElement: host, createText: text => ({ ...host(), text }), createComment: host,
      insert(node, parent, anchor) { if (node.parent) this.remove(node); node.parent = parent; const at = parent.children.indexOf(anchor); parent.children.splice(at < 0 ? parent.children.length : at, 0, node); },
      remove(node) { if (node.parent) { const rows = node.parent.children; rows.splice(rows.indexOf(node), 1); node.parent = null; } },
      setText(node, text) { node.text = text; }, setElementText(node, text) { node.text = text; }, patchProp() {},
      parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
    });
    const container = host();
    const renderPage = () => renderer.render(vue.h(vue.Fragment, app.routedContent(page)), container);
    route.path = '/tickets/2057'; route.name = 'ticket-detail'; route.meta = router.resolve(route.path).meta;
    auth.identity = 'session-a'; auth.user = { userId: -1 }; renderPage(); assert.equal(mounts, 1);
    route.query = { knowledge: '1' }; renderPage(); assert.equal(mounts, 1, 'query changes retain the current event state');
    auth.user = null; renderPage(); assert.equal(unmounts, 1, 'missing identity profile immediately removes private event content');
    auth.identity = 'session-b'; auth.user = { userId: -1 }; renderPage(); assert.equal(mounts, 2);
    auth.identity = 'session-c'; renderPage(); assert.equal(unmounts, 2); assert.equal(mounts, 3, 'same actor with a new login receives a fresh event component');
    auth.user = { userId: -2 }; renderPage(); assert.equal(unmounts, 3); assert.equal(mounts, 4, 'another visitor cannot reuse the old private event component');
    route.path = '/dashboard'; route.name = 'dashboard'; renderPage();
    const ordinaryMounts = mounts; auth.identity = 'session-d'; auth.user = null; renderPage();
    assert.equal(mounts, ordinaryMounts, 'other routes retain their original path-only lifecycle');
    renderer.render(null, container);
    auth.user = { userId: 7 }; auth.isDemo = false;
  } finally { app.stop(); }
} finally { globalThis.window = previousWindow; globalThis.localStorage = previousStorage; }
console.log('PASS actual AppLayout: reachable secondary pages, no duplicate event-list tabs and clear assistant scope');

for (const record of router.getRoutes().filter(item => item.name && !item.redirect && !item.meta.public)) {
  assert.ok(record.meta.navKey, record.path + ' has explicit module ownership');
  assert.ok(record.meta.title, record.path + ' has a breadcrumb title');
}
for (const [path, parentName, owner] of [
  ['/itsm/alerts', 'tickets', '/tickets'], ['/itsm/sla', 'tickets', '/tickets'], ['/itsm/oncall', 'tickets', '/tickets'],
  ['/tickets/2068', 'tickets', '/tickets'], ['/knowledge/review', 'knowledge', '/knowledge'], ['/knowledge/index-admin', 'knowledge', '/knowledge'],
  ['/observability/config', 'observability-topology', '/observability/topology'], ['/observability/traffic', 'observability-topology', '/observability/topology'],
]) {
  const destination = router.resolve(path); const parent = routeNavigation.parentLocation(destination);
  assert.equal(parent.name, parentName, 'Deep-link parent works without browser history');
  assert.equal(navigationFor(destination).primaryTo, owner);
  Object.assign(route, { path: destination.path, meta: destination.meta, query: destination.query });
  const breadcrumb = fixture('components/AppBreadcrumb.vue');
  try {
    const html = await breadcrumb.html(); assert.ok(html.includes('href="' + owner + '"'), path);
    assert.ok(html.includes(destination.meta.title), path);
    assert.match(html, /aria-current="page"/);
  } finally { breadcrumb.stop(); }
}
assert.deepEqual(routeNavigation.parentLocation(router.resolve('/observability/config?ciCode=rag&environment=prod&timeRange=15m&edit=1')),
  { name: 'observability-topology', query: { ciCode: 'rag', environment: 'prod', timeRange: '15m' } });
assert.equal(routeNavigation.parentLocation(router.resolve('/observability/topology')), undefined);
assert.deepEqual(routeNavigation.parentLocation(router.resolve('/observability/config/managed?ciCode=ops-demo-order-service')),
  { name: 'observability-config', query: { ciCode: 'ops-demo-order-service' } });
assert.equal(router.resolve('/observability/metrics').name, 'observability-metrics');
assert.deepEqual(router.getRoutes().find(record => record.name === 'observability-metrics').props.default, { metricsOnly: true });
{
  assistant.open = false; assistant.minimized = false;
  const orb = fixture('components/ai/AiAssistantOrb.vue');
  try {
    assert.ok((await orb.html()).includes('问问 OpsAgent AI'));
    orb.state.assistant.show(); assert.equal(assistant.open, true);
    assert.ok(!(await orb.html()).includes('ai-orb-position'), 'Opening the shared dock removes the floating button');
    assistant.open = false; assistant.minimized = true;
    assert.ok((await orb.html()).includes('展开 AI 悬浮入口'));
  } finally { orb.stop(); }
}
console.log('PASS all business route metadata, real clickable Breadcrumb, explicit deep-link parent with preserved service scope and shared/minimizable AI orb');
