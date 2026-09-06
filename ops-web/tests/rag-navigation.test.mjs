import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const piniaApi = require('pinia');
function load(path, imports = {}) {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => imports[id] ?? require(id), module, module.exports);
  return module.exports;
}
const navigationModule = load('utils/rag-navigation.ts');
const contextModule = load('utils/ai-context.ts');
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
const providerCatalog = () => ({ defaultProvider: 'deepseek', providers: [
  { provider: 'deepseek', model: 'deepseek-v4-flash', available: true, status: '已配置' },
  { provider: 'openai', model: 'configured-openai-model', available: true, status: '已配置' },
  { provider: 'kimi', model: 'kimi-model', available: false, status: '尚未配置完成' },
] });
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
  const auth = vue.reactive({ isAuthenticated: true, user: { userId: 7 } });
  const pinia = piniaApi.createPinia(); piniaApi.setActivePinia(pinia);
  const { useAiAssistantStore } = load('stores/ai-assistant.ts', {
    vue, pinia: piniaApi, '@/stores/auth': { useAuthStore: () => auth }, '@/utils/ai-context': contextModule,
    '@/api/conversations': { conversationApi: api, ragProviderApi: { list: overrides.providers || (async () => providerCatalog()) } },
    '@/api/rag-stream': { streamRagAnswer: stream, ragCompletionLabel: () => '回答完成', ragIncompleteMessage: () => '' },
    '@/api/ai-observability-context': { resolveAiObservabilityContext: overrides.evidence || (async scope => scope.service ? { service: scope.service, environment: (scope.environment || 'PROD').toUpperCase(), timeRange: scope.timeRange || '15m' } : undefined) },
  });
  const assistant = useAiAssistantStore(pinia);
  const { useRagConversations } = load('composables/useRagConversations.ts', {
    vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounting.push(fn) },
    'vue-router': { useRoute: () => route, useRouter: () => router },
    '@/stores/ai-assistant': { useAiAssistantStore: () => assistant },
    '@/utils/rag-navigation': navigationModule,
  });
  const scope = vue.effectScope();
  const state = scope.run(() => useRagConversations());
  mounted.forEach(fn => fn());
  return { state, calls, route, assistant, auth, navigate: async query => { route.query = query; await flush(); }, stop: () => { unmounting.forEach(fn => fn()); scope.stop(); piniaApi.disposePinia(pinia); } };
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
  assert.deepEqual(app.calls.stream, [{ question: '排查 Redis', topK: 5, conversationId: 'created', provider: 'deepseek' }]);
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

// Model selection is explicit and stays attached to the request, including existing conversations.
{
  const app = setup({ conversation: 'old' });
  await flush();
  assert.equal(app.state.selectedProvider.value, 'deepseek');
  assert.equal(app.state.providers.value.find(item => item.provider === 'kimi').available, false);
  app.state.selectedProvider.value = 'openai';
  await app.state.ask('当前内存趋势');
  assert.equal(app.calls.stream[0].provider, 'openai');
  assert.equal(app.calls.stream[0].conversationId, 'old');
  app.stop();
}

// Discovery failure or pending discovery must never create a conversation with an unknown model.
{
  const discovery = deferred();
  const app = setup({ new: '1', draft: '服务健康' }, { providers: () => discovery.promise });
  await flush();
  await app.state.ask();
  assert.equal(app.calls.create, 0);
  discovery.reject(new Error('模型目录不可用'));
  await flush();
  await app.state.ask();
  assert.equal(app.state.providerError.value, '模型目录不可用');
  assert.equal(app.calls.create, 0);
  assert.equal(app.calls.stream.length, 0);
  assert.equal(app.state.question.value, '服务健康');
  app.stop();
}
console.log('PASS provider discovery: real choices, selected provider propagation and no writes on loading/failure');

// Top bar, orb and the full-page adapter operate on one persistent conversation, never auto-send.
{
  const app = setup({ conversation: 'old' }); await flush();
  app.assistant.show({ service: 'ops-rag-service', environment: 'prod', timeRange: '15m' });
  assert.equal(app.assistant.open, true);
  assert.equal(app.assistant.sessionId, 'old');
  assert.equal(app.state.turns.value[0].answer, oldTurn.answer);
  app.assistant.hide(); app.assistant.show();
  assert.equal(app.assistant.sessionId, 'old');
  assert.equal(app.calls.stream.length, 0);
  app.state.question.value = '检查服务健康';
  assert.equal(app.assistant.question, '检查服务健康');
  app.stop();
}

// Freeze scope at send time even if the selected page/service changes during a streaming response.
{
  const stream = deferred();
  const app = setup({}, { stream: () => stream.promise }); await flush();
  app.assistant.setContext({ page: '/tickets/2068', service: 'ops-demo-notification-service', ticketId: 2068, alertId: '8', environment: 'demo' });
  const sending = app.assistant.ask('查看当前服务的恢复证据'); await flush();
  app.assistant.setContext({ page: '/observability/topology', service: 'ops-rag-service' });
  assert.equal(app.calls.stream[0].ticketId, 2068);
  assert.equal(app.calls.stream[0].question, '查看当前服务的恢复证据');
  assert.equal(app.calls.stream[0].observabilityContext.service, 'ops-demo-notification-service');
  assert.equal(app.calls.stream[0].observabilityContext.environment, 'DEMO');
  assert.doesNotMatch(app.calls.stream[0].question, /ops-rag-service/);
  assert.match(app.assistant.contextSummary, /ops-rag-service/);
  stream.resolve(answer); await sending; await flush();
  assert.equal(app.assistant.turns.length, 1);
  assert.equal(app.assistant.sessionId, 'created');
  app.stop();
}

// Logging out during session creation or generation must not leak late results or continue requests.
{
  const created = deferred();
  const app = setup({}, { api: { create: () => created.promise } }); await flush();
  const sending = app.assistant.ask('私有事件诊断'); await flush();
  app.auth.isAuthenticated = false; app.auth.user = null;
  created.resolve({ ...oldSession, id: 'private-created' }); await sending; await flush();
  assert.equal(app.calls.stream.length, 0);
  assert.equal(app.assistant.sessionId, '');
  assert.deepEqual(app.assistant.sessions, []);
  assert.deepEqual(app.assistant.turns, []);
  assert.equal(app.assistant.busy, false);
  app.stop();
}
{
  const stream = deferred();
  const app = setup({}, { stream: () => stream.promise }); await flush();
  const sending = app.assistant.ask('账号一的问题'); await flush();
  app.auth.user = { userId: 8 };
  stream.resolve(answer); await sending; await flush();
  assert.deepEqual(app.assistant.turns, []);
  assert.deepEqual(app.assistant.sessions, []);
  assert.equal(app.assistant.contextSummary, '');
  app.stop();
}
assert.deepEqual(contextModule.contextFromRoute({ path: '/tickets/2068', params: { id: '2068' }, query: { ciCode: 'ops-demo-notification-service', token: 'never include', environment: 'demo' } }),
  { page: '/tickets/2068', service: 'ops-demo-notification-service', ticketId: 2068, alertId: undefined, environment: 'demo', timeRange: undefined });
assert.equal(contextModule.cleanAiContext({ service: 'redis\nignore rules', ticketId: -1 }).service, undefined);
assert.equal(contextModule.contextualQuestion('x'.repeat(2000), { service: 'ops-rag-service' }).length, 2000);
console.log('PASS shared AI session, current context, frozen request scope, no automatic send and account/late-response isolation');

{
  const evidence = deferred(); const scopes = [];
  const app = setup({}, { evidence: async (scope, signal) => { scopes.push({ ...scope }); return evidence.promise; } }); await flush();
  app.assistant.setContext({ service: 'ops-rag-service', environment: 'PROD', timeRange: '15m' });
  const sending = app.assistant.ask('检查当前阻断情况'); await flush();
  assert.equal(app.calls.create, 0, 'Resolve a reference before creating a persisted turn');
  app.assistant.setContext({ service: 'redis' });
  evidence.resolve({ service: 'ops-rag-service', environment: 'PROD', timeRange: '15m' }); await sending; await flush();
  assert.equal(scopes[0].service, 'ops-rag-service');
  assert.equal(app.calls.stream[0].question, '检查当前阻断情况');
  assert.deepEqual(app.calls.stream[0].observabilityContext, { service: 'ops-rag-service', environment: 'PROD', timeRange: '15m' });
  assert.doesNotMatch(app.calls.stream[0].question, /blockQps|现场快照|ops-rag-service/);
  assert.doesNotMatch(app.calls.stream[0].question, /redis/);
  app.stop();
}
{
  const evidence = deferred();
  const app = setup({}, { evidence: () => evidence.promise }); await flush();
  app.assistant.setContext({ service: 'ops-rag-service' });
  const sending = app.assistant.ask('当前服务健康'); await flush();
  app.auth.user = { userId: 8 }; evidence.resolve({ service: 'ops-rag-service', environment: 'PROD', timeRange: '15m' }); await sending; await flush();
  assert.equal(app.calls.create, 0); assert.equal(app.calls.stream.length, 0);
  assert.deepEqual(app.assistant.turns, []);
  app.stop();
}
console.log('PASS send-time object reference without browser-authored facts, frozen service despite route changes and identity abort before persistence');
