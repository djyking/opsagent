import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), pinia = require('pinia');
function load(path, imports = {}) {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8').replaceAll('import.meta.env.VITE_API_BASE_URL', "''");
  const module = { exports: {} };
  new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(name => imports[name] || require(name), module, module.exports);
  return module.exports;
}
globalThis.window = { setTimeout, clearTimeout };
const encoder = new TextEncoder();
let sent, cancellation = false;
const stream = load('api/rag-stream.ts', { './session': { sessionFetch: async (url, options) => {
  sent = JSON.parse(options.body);
  return new Response(new ReadableStream({ start(controller) {
    controller.enqueue(encoder.encode('event: status\ndata: {"phase":"preparing"}\n\nevent: status\ndata: {"phase":"retrieval"}\n\nevent: status\ndata: {"phase":"generating"}\n\nevent: token\ndata: {"delta":"真实正文"}\n\nevent: done\ndata: {"answer":"真实正文","references":[],"provider":"deepseek","model":"test","answerStyle":"detailed","timing":{"totalMs":200,"preparationMs":100,"generationMs":100,"firstTokenMs":150}}\n\n'));
    // Deliberately never close: done must release the composer without waiting for socket EOF.
  }, cancel() { cancellation = true; } }), { headers: { 'Content-Type': 'text/event-stream' } });
} } });
const status = [];
const answer = await stream.streamRagAnswer({ question: '检查服务', answerStyle: 'detailed' }, { onStatus: message => status.push(message) });
assert.equal(sent.answerStyle, 'detailed');
assert.equal(answer.answerStyle, 'detailed');
assert.equal(cancellation, true);
assert(status.indexOf('请求已接收，正在准备') < status.indexOf('正在检索相关资料'));
assert(answer.clientTiming.firstContentMs >= 0 && answer.clientTiming.totalMs >= answer.clientTiming.firstContentMs);
assert.match(stream.ragTimingLabel(answer), /本次用时.*首段/);
assert.doesNotMatch(stream.ragTimingLabel({ ...answer, timing: { totalMs: 200 }, clientTiming: undefined }), /首段/);

const denied = load('api/rag-stream.ts', { './session': { sessionFetch: async () => new Response('event: error\ndata: {"code":40300,"message":"无权读取证据"}\n\n', { headers: { 'Content-Type': 'text/event-stream' } }) } });
await assert.rejects(denied.streamRagAnswer({ question: 'x' }), error => error.code === 40300 && error.message === '无权读取证据');

const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
let refresh = [], generation = [];
const auth = vue.reactive({ isAuthenticated: true, user: { userId: 1 } });
const context = load('utils/ai-context.ts');
pinia.setActivePinia(pinia.createPinia());
const storeModule = load('stores/ai-assistant.ts', {
  vue, pinia, '@/stores/auth': { useAuthStore: () => auth }, '@/utils/ai-context': context,
  '@/api/conversations': { conversationApi: {
    create: async () => ({ id: 'one', title: '问答' }),
    messages: () => { const wait = deferred(); refresh.push(wait); return wait.promise; },
    list: async () => ({ records: [], total: 0 }),
  }, ragProviderApi: {} },
  '@/api/rag-stream': { ...stream, streamRagAnswer: async (data, handlers) => {
    generation.push(data); handlers.onToken('本次答案');
    return { answer: '本次答案', references: [], answerStyle: data.answerStyle, clientTiming: { totalMs: 120 }, metadata: { generationComplete: true } } } },
});
const store = storeModule.useAiAssistantStore();
store.providersReady = true; store.selectedProvider = 'deepseek'; store.answerStyle = 'detailed';
await store.ask('解释 Redis');
assert.equal(store.busy, false, 'pending background messages cannot hold busy');
assert.equal(refresh.length, 1);
assert.equal(generation[0].answerStyle, 'detailed');
const oldId = store.turns[0].id;
await store.ask('解释 Nacos');
const selected = store.selectedTurnId;
refresh[0].resolve({ records: [{ id: 100, question: store.turns[0].question, answer: '本次答案', status: 'COMPLETE' }] });
await new Promise(resolve => setImmediate(resolve));
assert.equal(store.selectedTurnId, selected, 'older history refresh cannot select an earlier turn');
assert.equal(store.turns[0].id, oldId, 'stale refresh cannot replace the conversation during a newer question');
auth.user = { userId: 2 };
refresh[1].resolve({ records: [{ id: 200, answer: '其他身份的答案', status: 'COMPLETE' }] });
await new Promise(resolve => setImmediate(resolve));
assert.equal(store.turns.length, 0, 'late history from previous identity cannot repopulate new identity');
assert.equal(store.answerStyle, 'concise');
store.$dispose();
console.log('PASS early stages, real content timing, done without EOF, style payload, forbidden code, nonblocking history and identity/turn races');
