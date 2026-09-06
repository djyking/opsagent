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
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
const Stub = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.actions?.(), slots.tabs?.()]) };
const icons = new Proxy({}, { get: () => ({ render: () => vue.h('i') }) });
const navigation = evaluate(read('data/navigation.ts'), { '@lucide/vue': icons });
const workspaceActions = evaluate(read('data/workspace-actions.ts'), {});
const { navigationGroups, navigationFor } = navigation;
assert.deepEqual(navigationGroups.map(group => group.items.map(item => [item.to, item.label])), [
  [['/dashboard', '运行总览'], ['/tickets', '事件处置'], ['/automation', '自动化中心']],
  [['/operations', '服务与观测'], ['/knowledge', '知识与经验']],
]);
const ownership = new Map([
  ['/tickets/2057', '/tickets'], ['/itsm/alerts', '/tickets'], ['/itsm/sla', '/tickets'], ['/itsm/oncall', '/tickets'],
  ['/knowledge/review', '/knowledge'], ['/knowledge/index-admin', '/knowledge'], ['/configuration', '/operations'],
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
}).default;
for (const [from, expected] of [
  ['/events?keyword=Redis#queue', '/tickets?keyword=Redis#queue'], ['/events/2057?source=alert#evidence', '/tickets/2057?source=alert#evidence'],
  ['/system/monitor?tab=governance', '/operations?tab=governance'], ['/itsm/cmdb?service=gateway', '/operations?service=gateway&tab=topology'],
]) {
  await router.push(from);
  assert.equal(router.currentRoute.value.fullPath, expected);
}
for (const path of ['/tickets', '/tickets/2057', '/itsm/alerts', '/itsm/sla', '/itsm/oncall', '/operations', '/configuration', '/knowledge', '/rag/chat']) {
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
    '@/components/AppSidebar.vue': { default: Stub }, '@/components/GlobalTopbar.vue': { default: Stub },
    '@/components/automation/GlobalApprovalInbox.vue': { default: Stub },
  }).default;
  const render = evaluate(template.code, { vue }).render;
  const reactiveProps = vue.reactive(props); const scope = vue.effectScope(); const emitted = [];
  const state = scope.run(() => component.setup(reactiveProps, { expose() {}, emit: (...args) => emitted.push(args) }));
  return { state, props: reactiveProps, emitted, stop: () => scope.stop(), async html() {
    const context = vue.proxyRefs({ ...state, ...reactiveProps });
    const app = vue.createSSRApp({ render: () => render(context, [], reactiveProps, context, {}, {}) });
    app.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', { href: p.to }, slots.default?.()) });
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
      assert.ok(html.includes('href="/rag/chat"'), 'The assistant remains a global entry for every role');
      assert.equal(html.includes('href="/admin"'), admin && !demo);
      assert.equal(html.includes('href="/notifications"'), admin && !demo);
      assert.equal(html.includes('href="/tickets?create=1"'), !demo);
      assert.ok(html.includes('待处理审批 2 项'));
      assert.ok(html.includes('通用问答与知识检索'));
    }
    route.name = 'ticket-detail'; assert.ok((await app.html()).includes('事件详情'));
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
    assert.deepEqual(pushes.at(-1), { path: '/automation', query: { tab: 'inspection' } });
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
      route.path = path; const html = await app.html();
      for (const item of navigation.eventNavigation) assert.ok(html.includes('href="' + item.to + '"'), path);
      assert.equal([...html.matchAll(/aria-current="page"/g)].length, 1);
    }
    route.path = '/tickets'; assert.ok(!(await app.html()).includes('module-secondary-navigation'), 'Event list owns its own tabs; shell must not duplicate them');
    route.path = '/rag/chat'; assert.ok((await app.html()).includes('具体事件的诊断、审批和执行记录保存在事件工作区'));
    route.path = '/admin'; auth.isAdmin = true; assert.ok((await app.html()).includes('href="/notifications"'));
    auth.isDemo = true; assert.ok(!(await app.html()).includes('href="/notifications"'));
  } finally { app.stop(); }
} finally { globalThis.window = previousWindow; globalThis.localStorage = previousStorage; }
console.log('PASS actual AppLayout: reachable secondary pages, no duplicate event-list tabs and clear assistant scope');
