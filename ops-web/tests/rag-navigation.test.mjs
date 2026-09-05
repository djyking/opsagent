import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
function load(path, imports = {}) {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => imports[id] ?? require(id), module, module.exports);
  return module.exports;
}
const navigationModule = load('utils/rag-navigation.ts');
const { ragNavigation } = navigationModule;
assert.deepEqual(ragNavigation({ new: '1', draft: '排查 Redis', conversation: 'old' }), { kind: 'new', draft: '排查 Redis' });
assert.deepEqual(ragNavigation({ conversation: 'old', draft: 'do not overwrite history' }), { kind: 'conversation', id: 'old' });
assert.deepEqual(ragNavigation({ new: '1', draft: ['one', 'two'] }), { kind: 'new', draft: '' });
assert.deepEqual(ragNavigation({ new: '1', draft: 'x'.repeat(2001) }), { kind: 'new', draft: 'x'.repeat(2000) });
assert.deepEqual(ragNavigation({}), { kind: 'new', draft: '' });

function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function flush() {
  for (let i = 0; i < 10; i++) { await Promise.resolve(); await vue.nextTick(); }
}
const oldSession = { id: 'old', title: '已保存的 Redis 问题', updateTime: '2026-09-05T10:00:00' };
const oldTurn = { id: 1, question: '之前的问题', answer: '之前的回答', status: 'COMPLETE', createTime: '2026-09-05T10:00:00' };
const historyPage = () => ({ records: [structuredClone(oldSession)], total: 1 });
const answer = { answer: '排查步骤', references: [], metadata: { generationComplete: true } };
function setup(query = {}, overrides = {}) {
  const route = vue.reactive({ path: '/rag/chat', query });
  const mounted = [], unmounting = [], calls = { create: 0, stream: [], remove: 0, rename: 0, messages: [] };
  const api = {
    list: async () => historyPage(),
    create: async () => { calls.create++; return { ...oldSession, id: 'created', title: '新会话' }; },
    messages: async id => { calls.messages.push(id); return { records: id === 'old' ? [structuredClone(oldTurn)] : [], hasMore: false }; },
    remove: async () => { calls.remove++; },
    rename: async () => { calls.rename++; },
    ...overrides.api,
  };
  const stream = async (...args) => {
    calls.stream.push(args[0]);
    return overrides.stream ? overrides.stream(...args) : answer;
  };
  const router = { replace: async location => { route.query = location.query || {}; } };
  const { useRagConversations } = load('composables/useRagConversations.ts', {
    vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounting.push(fn) },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    '@/api/conversations': { conversationApi: api },
    '@/api/rag-stream': { streamRagAnswer: stream, ragCompletionLabel: () => '回答完成', ragIncompleteMessage: () => '' },
    '@/utils/rag-navigation': navigationModule,
  });
  const scope = vue.effectScope();
  const state = scope.run(() => useRagConversations());
  mounted.forEach(fn => fn());
  return { state, calls, route, navigate: async query => { route.query = query; await flush(); }, stop: () => { unmounting.forEach(fn => fn()); scope.stop(); } };
}

// Initial drafts are usable even while history loads; consuming the URL cannot clear them.
{
  const history = deferred();
  const app = setup({ new: '1', draft: '排查 Redis', conversation: 'old' }, { api: { list: () => history.promise } });
  await flush();
  assert.equal(app.state.question.value, '排查 Redis');
  assert.equal(app.state.sessionId.value, '');
  assert.deepEqual(app.route.query, {});
  assert.equal(app.calls.create, 0);
  assert.equal(app.calls.stream.length, 0);
  history.resolve(historyPage());
  await flush();
  assert.equal(app.state.question.value, '排查 Redis');
  assert.deepEqual(app.state.sessions.value.map(item => item.id), ['old']);
  assert.equal(app.calls.messages.length, 0);
  app.stop();
}

// A late response from selecting old history must not overwrite the user's new draft.
{
  const messages = deferred();
  const app = setup({ conversation: 'old' }, { api: { messages: () => messages.promise } });
  await flush();
  assert.equal(app.state.loading.value, true);
  await app.navigate({ new: '1', draft: '分析 RabbitMQ 消息堆积' });
  messages.resolve({ records: [structuredClone(oldTurn)], hasMore: true });
  await flush();
  assert.equal(app.state.question.value, '分析 RabbitMQ 消息堆积');
  assert.equal(app.state.sessionId.value, '');
  assert.equal(app.state.loading.value, false);
  assert.equal(app.state.turns.value.length, 0);
  assert.equal(app.state.hasEarlier.value, false);
  assert.equal(app.calls.stream.length, 0);
  app.stop();
}

// Existing history remains saved and selectable, and only an explicit send creates a conversation.
{
  const app = setup({ conversation: 'old' });
  await flush();
  assert.equal(app.state.turns.value[0].answer, oldTurn.answer);
  await app.navigate({ new: '1', draft: '先检查 Redis 连接数' });
  assert.equal(app.state.turns.value.length, 0);
  assert.equal(app.state.sessions.value[0].id, 'old');
  assert.equal(app.calls.create + app.calls.stream.length + app.calls.remove + app.calls.rename, 0);
  await app.state.selectSession('old');
  assert.equal(app.state.turns.value[0].answer, oldTurn.answer);
  await app.navigate({ new: '1', draft: '排查 Redis' });
  await app.state.ask();
  await flush();
  assert.equal(app.calls.create, 1);
  assert.deepEqual(app.calls.stream, [{ question: '排查 Redis', topK: 5, conversationId: 'created' }]);
  assert.equal(app.state.sessionId.value, 'created');
  assert.deepEqual(app.route.query, { conversation: 'created' });
  app.stop();
}

// An active stream stays attached to its original conversation; a new draft waits without auto-sending.
{
  const stream = deferred();
  const app = setup({ conversation: 'old' }, { stream: () => stream.promise });
  await flush();
  const submitted = app.state.ask('原会话追问');
  await flush();
  await app.navigate({ new: '1', draft: '另一个问题' });
  assert.equal(app.state.busy.value, true);
  assert.equal(app.state.sessionId.value, 'old');
  assert.deepEqual(app.route.query, { conversation: 'old' });
  stream.resolve(answer);
  await submitted;
  await flush();
  assert.equal(app.state.sessionId.value, '');
  assert.equal(app.state.question.value, '另一个问题');
  assert.equal(app.calls.stream.length, 1);
  assert.equal(app.calls.stream[0].conversationId, 'old');
  assert.equal(app.calls.create, 0);
  app.stop();
}

// Repeated imports keep the newest draft, including when history refresh fails.
{
  const history = deferred();
  const app = setup({}, { api: { list: () => history.promise } });
  await app.navigate({ new: '1', draft: '第一个问题' });
  await app.navigate({ new: '1', draft: '第二个问题' });
  history.reject(new Error('历史列表不可用'));
  await flush();
  assert.equal(app.state.question.value, '第二个问题');
  assert.equal(app.state.historyError.value, '历史列表不可用');
  assert.equal(app.calls.stream.length, 0);
  app.stop();
}

// Route watchers can run before unmount. Leaving RAG must not erase the destination's form query.
{
  const app = setup({ conversation: 'old' });
  await flush();
  app.route.path = '/tickets';
  await app.navigate({ create: '1' });
  assert.deepEqual(app.route.query, { create: '1' });
  assert.equal(app.state.sessionId.value, 'old');
  assert.equal(app.state.turns.value[0].answer, oldTurn.answer);
  app.stop();
}

// A queued draft also must not rewrite another page's URL when the old stream finishes.
{
  const stream = deferred();
  const app = setup({ conversation: 'old' }, { stream: () => stream.promise });
  await flush();
  const submitted = app.state.ask('原会话追问');
  await flush();
  await app.navigate({ new: '1', draft: '待导入的问题' });
  app.route.path = '/tickets';
  await app.navigate({ create: '1' });
  assert.deepEqual(app.route.query, { create: '1' });
  stream.resolve(answer);
  await submitted;
  await flush();
  assert.deepEqual(app.route.query, { create: '1' });
  assert.equal(app.state.sessionId.value, 'old');
  assert.equal(app.calls.stream.length, 1);
  app.stop();
}
console.log('PASS RAG draft handoff: query consumption, limits, no automatic send, saved history, stale selection, active stream races and preserving destination queries on exit');
