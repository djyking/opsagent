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
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function flush() { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); }
const content = () => ({ catalogTitle: '订单目录', notice: '当前业务提示', discountPercent: 5 });
const detail = (overrides = {}) => ({ id: 'order-business', name: '订单业务配置', group: 'OPSAGENT', dataId: 'order-business.json',
  editable: true, description: '实际业务配置', content: content(), revision: 'a'.repeat(64), appliedRevision: 'a'.repeat(64),
  canPublish: true, blockedReason: '', nacosStatus: 'CONNECTED', applicationStatus: 'APPLIED',
  observedAt: '2026-09-06T06:00:00Z', business: { httpStatus: 200, ...content(), basePrice: 100, quotedPrice: 95 }, ...overrides });
const version = (overrides = {}) => ({ id: 91, version: 1, action: 'BASELINE', status: 'APPLIED', content: { ...content(), discountPercent: 0 },
  previousContent: {}, revision: '0'.repeat(64), expectedRevision: '', comment: '真实初始配置', actorName: '系统',
  createdAt: '2026-09-06T06:00:00Z', finishedAt: null, rollbackVersionId: null, ...overrides });
function fixture() {
  const auth = vue.reactive({ userId: 7, admin: true });
  const calls = [];
  let currentDetail = detail();
  const api = {
    list: async () => ({ items: [detail()] }), detail: async () => currentDetail,
    history: async () => ({ items: [version()], total: 1, page: 1, size: 8 }),
    validate: async (id, value) => { calls.push(['validate', id, structuredClone(value)]); return { valid: true, normalizedContent: { ...value } }; },
    publish: async (id, request) => { calls.push(['publish', id, JSON.parse(JSON.stringify(request))]);
      currentDetail = detail({ content: { ...request.content }, revision: 'b'.repeat(64) });
      return { operation: version({ action: 'PUBLISH' }), configuration: currentDetail }; },
    rollback: async (id, request) => { calls.push(['rollback', id, { ...request }]);
      currentDetail = detail({ content: version().content, revision: 'c'.repeat(64) });
      return { operation: version({ action: 'ROLLBACK' }), configuration: currentDetail }; },
  };
  const scope = vue.effectScope();
  const module = evaluate(read('composables/useManagedConfiguration.ts'), { vue, '@/api/configuration': { configurationApi: api } });
  const manager = scope.run(() => module.useManagedConfiguration(() => auth.admin, () => auth.userId));
  return { manager, api, auth, calls, stop() { manager.dispose(); scope.stop(); } };
}
async function prepare(app) {
  await app.manager.select('order-business'); app.manager.draft.value.catalogTitle = '新订单目录';
  await app.manager.prepare(); app.manager.comment.value = '核对后的业务变更';
}

// Editing a projection must never alter the read-only runtime document or expose writes to another role.
{
  const app = fixture(); const runtime = { redisPort: 6380, sentinel: { qps: 2 }, nested: ['retain', 3] };
  try {
    app.api.detail = async () => detail({ id: 'order-runtime', editable: false, canPublish: false, content: runtime });
    await app.manager.select('order-runtime'); app.manager.draft.value.catalogTitle = '不能写入运行配置';
    await app.manager.prepare(); await app.manager.confirm();
    assert.equal(app.manager.dirty.value, false); assert.equal(app.manager.writable.value, false);
    assert.deepEqual(JSON.parse(JSON.stringify(app.manager.detail.value.content)), runtime); assert.equal(app.calls.length, 0);
    app.auth.admin = false; app.api.detail = async () => detail(); await app.manager.select('order-business');
    app.manager.draft.value.notice = '只读账号'; await app.manager.prepare(version()); assert.equal(app.calls.length, 0);
  } finally { app.stop(); }
}
console.log('PASS runtime content preservation and administrator-only validate/publish/rollback gates');

// Both paths send the reviewed CAS revision and one stable request identity; double clicks cannot post twice.
{
  const app = fixture();
  try {
    await prepare(app); const request = JSON.parse(JSON.stringify(app.manager.plan.value));
    app.manager.comment.value = ' '; await app.manager.confirm(); assert.equal(app.calls.filter(x => x[0] === 'publish').length, 0);
    app.manager.comment.value = '  已核对变更  '; await Promise.all([app.manager.confirm(), app.manager.confirm()]);
    const writes = app.calls.filter(x => x[0] === 'publish'); assert.equal(writes.length, 1);
    assert.deepEqual(writes[0], ['publish', 'order-business', { expectedRevision: request.expectedRevision,
      requestId: request.requestId, comment: '已核对变更', content: request.content }]);
    await app.manager.prepare(version({ status: 'UNCONFIRMED' })); assert.match(app.manager.error.value, /只能回退/);
    await app.manager.prepare(version()); assert.equal(app.manager.plan.value.versionId, 91); assert.equal(app.manager.plan.value.versionNumber, 1);
    app.manager.comment.value = '回退到初始基线'; await app.manager.confirm();
    assert.equal(app.calls.filter(x => x[0] === 'rollback').length, 1);
    assert.equal(app.calls.find(x => x[0] === 'rollback')[2].versionId, 91);
  } finally { app.stop(); }
}
console.log('PASS reviewed publish/rollback payloads, baseline identity, comment validation and duplicate-click exclusion');

{
  const app = fixture();
  try {
    await prepare(app); app.api.detail = async () => detail({ revision: 'd'.repeat(64), content: { ...content(), notice: '其他人已发布' } });
    await app.manager.confirm(); assert.equal(app.calls.filter(x => ['publish', 'rollback'].includes(x[0])).length, 0);
    assert.equal(app.manager.plan.value, undefined); assert.equal(app.manager.draft.value.catalogTitle, '新订单目录');
    assert.match(app.manager.error.value, /编辑内容已保留/); assert.equal(app.manager.detail.value.content.notice, '其他人已发布');
    await app.manager.prepare(); assert.equal(app.manager.plan.value.expectedRevision, 'd'.repeat(64));
  } finally { app.stop(); }
}
console.log('PASS CAS change blocks posting, preserves user draft and requires a newly reviewed diff');

{
  const app = fixture();
  try {
    await app.manager.select('order-business'); app.manager.draft.value.catalogTitle = '第一次编辑';
    const validation = deferred(); app.api.validate = () => validation.promise;
    const pending = app.manager.prepare(); app.manager.draft.value.catalogTitle = '更新的编辑';
    validation.resolve({ valid: true, normalizedContent: { ...content(), catalogTitle: '第一次编辑' } }); await pending;
    assert.equal(app.manager.plan.value, undefined); assert.equal(app.manager.draft.value.catalogTitle, '更新的编辑');
    app.api.validate = async (_, value) => ({ valid: true, normalizedContent: { ...value } });
    await app.manager.prepare(); app.manager.comment.value = '准备提交';
    const preflight = deferred(); app.api.detail = () => preflight.promise;
    const confirmation = app.manager.confirm(); app.manager.draft.value.notice = '预读期间的新编辑';
    preflight.resolve(detail()); await confirmation;
    assert.equal(app.calls.filter(x => x[0] === 'publish').length, 0); assert.equal(app.manager.plan.value, undefined);
  } finally { app.stop(); }
}
console.log('PASS draft changes invalidate pending validation and cancel a stale preflight before any write');

{
  const app = fixture();
  try {
    await prepare(app); let posts = 0;
    app.api.publish = async () => { posts++; throw new Error('网络连接中断'); };
    await app.manager.confirm(); assert.equal(posts, 1); assert.equal(app.manager.canPublish.value, false);
    assert.equal(app.manager.plan.value, undefined); assert.match(app.manager.detail.value.blockedReason, /刷新/);
    await app.manager.prepare(); await app.manager.confirm(); assert.equal(posts, 1, 'An uncertain response must not create another request');
    app.api.detail = async () => detail({ canPublish: false, blockedReason: '已有配置发布正在确认' });
    await app.manager.select('order-business'); app.manager.draft.value.notice = '仍需等待';
    await app.manager.prepare(); assert.equal(app.manager.plan.value, undefined); assert.equal(posts, 1);
  } finally { app.stop(); }
  const uncertain = fixture();
  try {
    await prepare(uncertain);
    uncertain.api.publish = async () => ({ operation: version({ status: 'UNCONFIRMED', message: '目标版本尚未确认一致' }), configuration: detail() });
    await uncertain.manager.confirm(); assert.equal(uncertain.manager.writable.value, false);
    assert.equal(uncertain.manager.notice.value, '目标版本尚未确认一致');
  } finally { uncertain.stop(); }
}
console.log('PASS uncertain transport/result locks further writes and uses operation.message from the real response contract');

{
  const app = fixture();
  try {
    const directory = deferred(); app.api.list = () => directory.promise;
    const initial = app.manager.load(); await app.manager.select('order-business'); app.manager.draft.value.notice = '较新的编辑';
    directory.resolve({ items: [detail()] }); await initial; assert.equal(app.manager.draft.value.notice, '较新的编辑');
    const stale = deferred(); app.api.detail = id => id === 'order-business' ? stale.promise
      : Promise.resolve(detail({ id, editable: false, canPublish: false, content: { redisPort: 6379 } }));
    const old = app.manager.select('order-business'); await app.manager.select('order-runtime'); stale.resolve(detail()); await old;
    assert.equal(app.manager.detail.value.id, 'order-runtime'); assert.deepEqual(Object.keys(app.manager.detail.value.content), ['redisPort']);
    assert.equal(app.manager.historyLoading.value, false);
  } finally { app.stop(); }
}
console.log('PASS late directory/detail responses cannot overwrite the latest selected configuration or draft');

{
  const app = fixture();
  try {
    await prepare(app); const publication = deferred(); app.api.publish = () => publication.promise;
    const old = app.manager.confirm(); await flush(); assert.equal(app.manager.busy.value, true);
    app.auth.userId = 8; assert.equal(app.manager.detail.value, undefined); assert.equal(app.manager.plan.value, undefined);
    assert.equal(app.manager.busy.value, false); await app.manager.select('order-business'); app.manager.draft.value.notice = '新账号的编辑';
    publication.resolve({ operation: version(), configuration: detail({ content: { ...content(), notice: '旧账号结果' } }) }); await old;
    assert.equal(app.manager.draft.value.notice, '新账号的编辑'); assert.notEqual(app.manager.detail.value.content.notice, '旧账号结果');
    await app.manager.prepare(); app.auth.admin = false; assert.equal(app.manager.plan.value, undefined);
    await app.manager.confirm(); assert.equal(app.manager.writable.value, false);
  } finally { app.stop(); }
}
console.log('PASS actor/role transitions invalidate confirmations and suppress old publication responses');

const Stub = { setup: (_, { slots }) => () => vue.h('section', [slots.default?.(), slots.actions?.(), slots.footer?.()]) };
const icons = new Proxy({}, { get: () => Stub });
function compile(path, imports = {}) {
  const { descriptor, errors } = compiler.parse(read(path), { filename: path }); assert.deepEqual(errors, []);
  const script = compiler.compileScript(descriptor, { id: 'configuration-test' });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename: path,
    id: 'configuration-test', compilerOptions: { bindingMetadata: script.bindings } }); assert.deepEqual(template.errors, []);
  const component = evaluate(script.content, { vue, '@lucide/vue': icons, ...imports }).default;
  return { ...component, render: evaluate(template.code, { vue }).render };
}
function view(app) {
  const component = compile('views/ConfigurationView.vue', {
    vue: { ...vue, onMounted() {}, onBeforeUnmount() {} },
    '@/stores/auth': { useAuthStore: () => vue.reactive({ get isAdmin() { return app.auth.admin; }, get user() { return { userId: app.auth.userId }; } }) },
    '@/composables/useManagedConfiguration': { useManagedConfiguration: () => app.manager },
    ...Object.fromEntries(['PageHeader', 'InlineError', 'LoadingState', 'EmptyState', 'BaseModal', 'PaginationBar'].map(name => ['@/components/' + name + '.vue', Stub])),
    '@/components/FormField.vue': compile('components/FormField.vue'), '@/styles/pages/configuration.css': {},
  });
  const state = component.setup({}, { expose() {} });
  return { state, async html() {
    const context = vue.proxyRefs(state);
    const rendering = vue.createSSRApp({ render: () => component.render(context, [], {}, context, {}, {}) });
    rendering.component('RouterLink', { props: ['to'], setup: (p, { slots }) => () => vue.h('a', { href: p.to }, slots.default?.()) });
    return renderToString(rendering);
  } };
}
{
  const app = fixture();
  try {
    await app.manager.select('order-business'); const page = view(app);
    let html = await page.html(); assert.match(html, /校验并预览变更/); assert.match(html, /<label class="oa-form-field">/);
    assert.equal([...html.matchAll(/<input/g)].length, 2); assert.equal([...html.matchAll(/<textarea/g)].length, 1);
    page.state.tab.value = 'history'; html = await page.html(); assert.match(html, /初始基线/); assert.match(html, /回退到此版本/);
    await app.manager.prepare(version()); html = await page.html(); assert.match(html, /以版本 #1 的内容创建新的发布版本/);
    assert.ok(!html.includes('以版本 #91'));
    page.state.destination.value = 'order-runtime'; app.auth.admin = false;
    assert.equal(page.state.destination.value, undefined, 'An old actor cannot leave a discard prompt over a new draft');
    await app.manager.select('order-business'); page.state.tab.value = 'content';
    html = await page.html(); assert.equal([...html.matchAll(/readonly/g)].length, 3);
    for (const label of ['校验并预览变更', '还原编辑', '确认发布配置', '回退到此版本']) assert.ok(!html.includes(label), label);
    page.state.tab.value = 'history'; assert.ok(!(await page.html()).includes('回退到此版本'));
    app.auth.admin = true; app.api.detail = async () => detail({ id: 'order-runtime', editable: false, canPublish: false,
      content: { redisPort: 6379, preservationMarker: 'ACTUAL-RUNTIME-CONTENT' } });
    await app.manager.select('order-runtime'); page.state.tab.value = 'content'; html = await page.html();
    assert.ok(html.includes('ACTUAL-RUNTIME-CONTENT')); assert.ok(!html.includes('<form')); assert.ok(!html.includes('<input')); assert.ok(!html.includes('<textarea'));
    assert.equal(app.manager.dirty.value, false); page.state.tab.value = 'history'; assert.ok(!(await page.html()).includes('回退到此版本'));
  } finally { app.stop(); }
}
console.log('PASS actual ConfigurationView/FormField SSR: editable geometry, baseline label, readonly controls and runtime-only JSON');

{
  const calls = []; const api = evaluate(read('api/configuration.ts'), { './http': { request: async value => { calls.push(value); return {}; } } }).configurationApi;
  const request = { versionId: 91, expectedRevision: 'e'.repeat(64), requestId: '00000000-0000-4000-8000-000000000007', comment: '核对版本' };
  await api.rollback('order-business', request);
  assert.deepEqual(calls, [{ method: 'POST', url: '/api/platform/configuration/managed/order-business/rollback', data: request }]);
}
console.log('PASS real configuration API rollback method, route and exact version/CAS/requestId payload');
