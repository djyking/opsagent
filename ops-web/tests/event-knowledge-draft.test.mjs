import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
const read = name => readFileSync(new URL('../src/' + name, import.meta.url), 'utf8');
function evaluate(source, imports) {
  const module = { exports: {} };
  new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(id => imports[id] ?? require(id), module, module.exports);
  return module.exports;
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
async function flush() { await Promise.resolve(); await Promise.resolve(); await vue.nextTick(); }
class ApiError extends Error { constructor(message, status, code) { super(message); this.status = status; this.code = code; } }
const Stub = { setup: (_, { slots }) => () => vue.h('section', slots.default?.()) };
function fixture({ visitor = true, get = async () => ({ baseId: 91, name: '我的体验库' }), upload = async () => 501, request = async () => [] } = {}) {
  const { descriptor } = compiler.parse(read('components/events/EventKnowledgeDraft.vue'));
  const script = compiler.compileScript(descriptor, { id: 'draft-test' });
  const template = compiler.compileTemplate({ source: descriptor.template.content, filename: 'EventKnowledgeDraft.vue', id: 'draft-test', compilerOptions: { bindingMetadata: script.bindings } });
  assert.deepEqual(template.errors, []);
  const auth = vue.reactive({ identity: 'session-1', isDemo: visitor, user: { userId: visitor ? -1 : 1 } });
  const props = vue.reactive({ open: true, ticketId: 1178, ticketTitle: '演练复盘', initialContent: '# 复盘\n已恢复', canSave: true });
  const unmount = [], emits = [], calls = [];
  const component = evaluate(script.content, {
    vue: { ...vue, onBeforeUnmount: fn => unmount.push(fn) },
    '@/stores/auth': { useAuthStore: () => auth },
    '@/api/visitor-knowledge': { visitorKnowledgeApi: { get: () => { calls.push(['experience-get']); return get(); }, upload: (...args) => { calls.push(['experience-upload', ...args]); return upload(...args); } } },
    '@/api/http': { ApiError, request: options => { calls.push(['request', options]); return request(options); } },
    '@/components/BaseModal.vue': { default: Stub }, '@/components/FormField.vue': { default: Stub }, '@/components/InlineError.vue': { default: Stub },
  }).default;
  const scope = vue.effectScope();
  const state = scope.run(() => component.setup(props, { expose() {}, emit: (...args) => emits.push(args) }));
  const render = evaluate(template.code, { vue }).render;
  return { state, props, auth, calls, emits, stop() { unmount.forEach(fn => fn()); scope.stop(); }, async html() {
    const context = vue.proxyRefs({ ...state, ...props });
    const app = vue.createSSRApp({ render: () => render(context, [], props, context, {}, {}) });
    app.component('RouterLink', { setup: (_, { slots }) => () => vue.h('a', slots.default?.()) });
    return renderToString(app);
  } };
}

// Exercise the actual API multipart contract, including the existing plain upload path.
{
  const calls = [];
  const { visitorKnowledgeApi } = evaluate(read('api/visitor-knowledge.ts'), { '@/api/http': { request: async options => { calls.push(options); return 501; } } });
  const file = new File(['# edited'], 'test.md', { type: 'text/markdown' });
  await visitorKnowledgeApi.upload(file, 1178); await visitorKnowledgeApi.upload(file);
  assert.equal(calls[0].url, '/api/knowledge/experience/documents');
  assert.equal(calls[0].data.get('ticketId'), '1178');
  assert.equal(calls[0].data.get('visibility'), null, 'the private experience API decides visibility');
  assert.equal(await calls[0].data.get('file').text(), '# edited');
  assert.equal(calls[1].data.get('ticketId'), null, 'ordinary experience uploads remain unbound');
}
{
  const library = deferred();
  const f = fixture({ get: () => library.promise });
  try {
    await f.state.save(); assert.equal(f.calls.filter(call => call[0] === 'experience-upload').length, 0, 'loading a library must not enable saving');
    library.resolve({ baseId: 91, name: '我的体验库' }); await flush();
    assert.equal(f.state.baseId.value, 91); assert.equal(f.calls.some(call => call[0] === 'request'), false, 'visitors never fetch public bases');
    const before = await f.html(); assert.doesNotMatch(before, /<select/); assert.match(before, /仅本人可见/); assert.match(before, /体验到期/);
    f.state.content.value = '# 本人核对后的复盘'; await f.state.save();
    const write = f.calls.find(call => call[0] === 'experience-upload');
    assert.equal(write[2], 1178); assert.equal(await write[1].text(), '# 本人核对后的复盘');
    assert.deepEqual(f.emits, [['saved', 501]]);
    assert.deepEqual(f.state.documentLink.value, { path: '/knowledge', query: { documentId: 501 } });
    assert.match(await f.html(), /不会覆盖原正文/);
    await f.state.save(); assert.equal(f.calls.filter(call => call[0] === 'experience-upload').length, 1, 'a successful dialog does not submit twice');
  } finally { f.stop(); }
}
{
  let attempt = 0;
  const f = fixture({ upload: async () => { if (++attempt === 1) throw Error('network lost'); return 501; } });
  try {
    await flush(); await f.state.save(); assert.equal(f.state.uncertain.value, true);
    assert.match(await f.html(), /重试确认保存/);
    await f.state.save(); assert.equal(attempt, 2); assert.equal(f.state.saved.value.id, 501); assert.equal(f.state.uncertain.value, false);
    assert(f.calls.filter(call => call[0] === 'experience-upload').every(call => call[2] === 1178), 'retry keeps the idempotent ticket binding');
  } finally { f.stop(); }
}
{
  let writes = 0;
  const f = fixture({ visitor: false, request: async options => {
    if (!options.method) return [{ id: 2, name: '原知识库' }];
    writes++; assert.equal(options.url, '/api/knowledge/bases/2/documents');
    assert.equal(options.data.get('visibility'), 'PRIVATE'); assert.equal(options.data.get('ticketId'), '1178');
    throw Error('network lost');
  } });
  try {
    await flush(); assert.match(await f.html(), /<select/); assert.equal(f.state.baseId.value, undefined);
    f.state.baseId.value = 2; await f.state.save(); await f.state.save();
    assert.equal(writes, 1, 'ordinary non-idempotent upload retains uncertain-result retry protection');
    assert.equal(f.calls.some(call => call[0].startsWith('experience-')), false);
  } finally { f.stop(); }
}
{
  const oldLibrary = deferred(); let first = true;
  const f = fixture({ get: () => { if (first) { first = false; return oldLibrary.promise; } return Promise.resolve({ baseId: 92, name: '新身份体验库' }); } });
  try {
    f.auth.identity = 'session-2'; await flush();
    oldLibrary.resolve({ baseId: 91, name: '旧身份体验库' }); await flush();
    assert.equal(f.state.baseId.value, 92); assert.equal(f.state.bases.value[0].name, '新身份体验库');
  } finally { f.stop(); }
}
for (const leave of ['identity', 'ticket', 'unmount']) {
  const pending = deferred(); const f = fixture({ upload: () => pending.promise });
  try {
    await flush(); const save = f.state.save();
    if (leave === 'identity') f.auth.identity = 'session-2';
    else if (leave === 'ticket') f.props.ticketId = 1179;
    else f.stop();
    pending.resolve(501); await save; await flush();
    assert.equal(f.state.saved.value, undefined, `${leave}: stale upload results cannot reappear`);
    assert.deepEqual(f.emits, [], `${leave}: stale uploads cannot refresh another event`);
  } finally { if (leave !== 'unmount') f.stop(); }
}
{
  let attempt = 0; const f = fixture({ get: async () => { if (++attempt === 1) throw Error('library unavailable'); return { baseId: 91, name: '我的体验库' }; } });
  try {
    await flush(); await f.state.save(); assert.equal(f.calls.some(call => call[0] === 'experience-upload'), false);
    await f.state.loadBases(); f.props.canSave = false; await f.state.save();
    assert.equal(f.calls.some(call => call[0] === 'experience-upload'), false, 'permission changes remain authoritative');
  } finally { f.stop(); }
}
console.log('PASS event knowledge draft: visitor-private binding, editable content, idempotent retry, ordinary flow, load/permission guards and stale identity/event/unmount isolation');
