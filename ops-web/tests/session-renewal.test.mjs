import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import { webcrypto } from 'node:crypto';

const require = createRequire(import.meta.url);
const ts = require('typescript');
const source = readFileSync(new URL('../src/api/session.ts', import.meta.url), 'utf8').replaceAll('import.meta.env.VITE_API_BASE_URL', "''");
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
let now = Date.parse('2026-09-07T10:00:00Z');
class TestDate extends Date {
  constructor(...args) { super(...(args.length ? args : [now])); }
  static now() { return now; }
}
function storage() {
  const items = new Map();
  return { getItem: k => items.get(k) ?? null, setItem: (k, v) => items.set(k, String(v)), removeItem: k => items.delete(k) };
}
function locks() {
  let tail = Promise.resolve();
  return { request: (_key, action) => { const next = tail.then(action); tail = next.catch(() => undefined); return next; } };
}
function tab(shared = storage(), lock = locks()) {
  const events = new Map();
  const timers = [];
  const context = {
    module: { exports: {} }, exports: {}, Date: TestDate, localStorage: shared,
    navigator: { locks: lock }, crypto: webcrypto, document: { hidden: false },
    window: { addEventListener: (type, handler) => events.set(type, handler) },
    setTimeout, clearTimeout, setInterval: action => timers.push(action),
    Headers, Response, AbortSignal, atob,
    fetch: (...args) => context.transport(...args),
  };
  context.exports = context.module.exports;
  vm.runInNewContext(js, context);
  return { api: context.module.exports, context, shared, events, timers };
}
const initial = (extra = {}) => ({
  accessToken: 'test-access-old', refreshToken: 'test-refresh-old', tokenType: 'Bearer',
  sessionId: 'test-session', sessionStartedAt: new Date(now - 3600_000).toISOString(),
  lastActivityAt: new Date(now - 20 * 60_000).toISOString(),
  sessionExpiresAt: new Date(now + 23 * 3600_000).toISOString(),
  expiresAt: new Date(now + 60_000).toISOString(), ...extra,
});
const response = (request, old = initial()) => new Response(JSON.stringify({ code: 0, data: {
  ...old, accessToken: 'test-access-new', refreshToken: 'test-refresh-new',
  expiresAt: new Date(now + 30 * 60_000).toISOString(), lastActivityAt: JSON.parse(request.body).lastActivityAt,
} }), { status: 200 });

{
  const t = tab(); let calls = 0; let activity;
  t.api.saveLogin(initial());
  t.context.transport = async (_url, options) => { calls++; activity = JSON.parse(options.body).lastActivityAt; return response(options); };
  const results = await Promise.all(Array.from({ length: 20 }, () => t.api.ensureAccessToken()));
  assert.equal(calls, 1); assert.ok(results.every(value => value === 'test-access-new'));
  assert.equal(activity, initial().lastActivityAt, 'background requests must not invent human activity');
  console.log('PASS single flight for 20 requests; background polling keeps the original activity clock');
}
{
  const t = tab(); t.api.saveLogin(initial({ sessionId: 'legacy-session' }));
  const identity = t.api.readSession().identity;
  t.context.transport = async (_url, options) => response(options, initial({ sessionId: 'upgraded-server-session' }));
  await t.api.ensureAccessToken();
  assert.equal(t.api.readSession().identity, identity, 'legacy adoption and rotation must preserve the UI identity');
  assert.equal(t.api.readSession().sessionId, 'upgraded-server-session');
  t.api.saveLogin(initial({ sessionId: 'new-login-session' }));
  assert.notEqual(t.api.readSession().identity, identity, 'a new login must invalidate the previous UI identity');
  console.log('PASS stable UI identity survives token rotation and legacy adoption, but changes on new login');
}
{
  const shared = storage(), lock = locks(), a = tab(shared, lock), b = tab(shared, lock); let calls = 0;
  a.api.saveLogin(initial());
  a.context.transport = b.context.transport = async (_url, options) => { calls++; return response(options); };
  assert.deepEqual(await Promise.all([a.api.ensureAccessToken(), b.api.ensureAccessToken()]), ['test-access-new', 'test-access-new']);
  assert.equal(calls, 1);
  console.log('PASS separate tabs coordinate refresh through one shared lock');
}
{
  const t = tab(); t.api.saveLogin(initial());
  t.context.transport = async () => { throw new TypeError('offline'); };
  await assert.rejects(t.api.ensureAccessToken(), error => !error.expired);
  assert.equal(t.api.readSession().refreshToken, 'test-refresh-old');
  assert.equal(t.api.getSessionNotice().kind, 'network');
  t.context.transport = async () => new Response('{}', { status: 503 });
  await assert.rejects(t.api.ensureAccessToken(), error => !error.expired);
  assert.equal(t.api.readSession().accessToken, 'test-access-old');
  console.log('PASS offline and 503 keep the current session and expose a retry message');
}
{
  const t = tab(); let calls = 0;
  t.api.saveLogin(initial({ lastActivityAt: new Date(now - 121 * 60_000).toISOString() }));
  t.context.transport = async () => { calls++; throw new Error('must not send'); };
  await assert.rejects(t.api.ensureAccessToken(), error => error.expired);
  assert.equal(calls, 0); assert.equal(t.api.readSession(), null);
  assert.equal(t.api.getSessionNotice().kind, 'expired');
  console.log('PASS idle expiration cannot be revived by polling');
}
for (const method of ['POST', 'GET']) {
  const t = tab(); let businessCalls = 0, refreshCalls = 0;
  t.api.saveLogin(initial({ expiresAt: new Date(now + 20 * 60_000).toISOString() }));
  t.context.transport = async (url, options) => {
    if (url.endsWith('/refresh')) { refreshCalls++; return response(options); }
    businessCalls++;
    return new Response('{}', { status: businessCalls === 1 ? 401 : 200 });
  };
  const promise = t.api.sessionFetch('/test-operation', { method }, method === 'GET' ? 'read' : 'never');
  if (method === 'POST') await assert.rejects(promise, /未自动重试/);
  else assert.equal((await promise).status, 200);
  assert.equal(businessCalls, method === 'POST' ? 1 : 2); assert.equal(refreshCalls, 1);
  console.log(`PASS ${method}: ${method === 'POST' ? 'AI/approval writes never replay' : 'a read retries at most once'}`);
}
{
  const t = tab(); let release;
  t.api.saveLogin(initial());
  t.context.transport = (_url, options) => new Promise(resolve => { release = () => resolve(response(options)); });
  const old = t.api.ensureAccessToken();
  await Promise.resolve(); await Promise.resolve();
  t.api.saveLogin(initial({ sessionId: 'different-account', accessToken: 'other-access', refreshToken: 'other-refresh' }));
  release(); await assert.rejects(old, /登录状态已变化/);
  assert.equal(t.api.readSession().accessToken, 'other-access');
  console.log('PASS an old refresh response cannot overwrite another account login');
}
{
  const t = tab(); t.api.saveLogin(initial({ expiresAt: new Date(now + 20 * 60_000).toISOString() }));
  t.api.installSessionLifecycle();
  const before = t.api.lastActivity(t.api.readSession());
  t.timers[0](); assert.equal(t.api.lastActivity(t.api.readSession()), before);
  t.events.get('wheel')({ isTrusted: false }); assert.equal(t.api.lastActivity(t.api.readSession()), before);
  t.events.get('wheel')({ isTrusted: true }); assert.equal(t.api.lastActivity(t.api.readSession()), now);
  assert.equal(t.api.safeReturnPath('//external.example'), '/dashboard');
  assert.equal(t.api.safeReturnPath('/tickets/42?tab=evidence'), '/tickets/42?tab=evidence');
  console.log('PASS only trusted human activity advances idle time; return paths stay local');
}
{
  const t = tab(); let reads = 0, writes = 0, renewals = 0;
  t.api.saveLogin(initial({ expiresAt: new Date(now + 20 * 60_000).toISOString() }));
  t.context.transport = async (_url, options) => { renewals++; return response(options); };
  const axios = require('axios');
  const fakeAxios = { default: { create: options => axios.create({ ...options, adapter: async config => {
    const result = { config, headers: {}, status: 200, statusText: 'OK', data: { code: 0, data: 'read-ok' } };
    if (config.method === 'get') {
      reads++; if (reads === 1) result.data = { code: 40100, message: 'expired', data: null };
      return result;
    }
    writes++;
    result.status = 401;
    result.data = { code: 40100, message: 'expired', data: null };
    throw new axios.AxiosError('unauthorized', 'ERR_BAD_REQUEST', config, {}, result);
  } }) } };
  const httpSource = readFileSync(new URL('../src/api/http.ts', import.meta.url), 'utf8').replaceAll('import.meta.env.VITE_API_BASE_URL', "''");
  const httpJs = ts.transpileModule(httpSource, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  vm.runInNewContext(httpJs, { module, exports: module.exports, require: id => id === 'axios' ? fakeAxios : t.api });
  assert.equal(await module.exports.request({ url: '/test-read' }), 'read-ok');
  assert.equal(reads, 2); assert.equal(renewals, 1);
  await assert.rejects(module.exports.request({ url: '/test-approval', method: 'POST' }), /未自动重试/);
  assert.equal(writes, 1);
  const before = t.api.readSession().accessToken;
  await assert.rejects(module.exports.request({ url: '/api/auth/login', method: 'POST' }));
  assert.equal(t.api.readSession().accessToken, before);
  console.log('PASS real Axios interceptors: business 40100 refreshes reads, writes never replay, login errors retain existing state');
}
{
  const vue = require('vue'), pinia = require('pinia');
  pinia.setActivePinia(pinia.createPinia());
  const t = tab(); t.api.saveLogin(initial({ expiresAt: new Date(now + 20 * 60_000).toISOString() }));
  const loadStore = (path, dependencies) => {
    const source = readFileSync(new URL(`../src/${path}`, import.meta.url), 'utf8');
    const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
    const module = { exports: {} };
    vm.runInNewContext(code, { module, exports: module.exports, require: id => dependencies[id] || require(id) });
    return module.exports;
  };
  const authModule = loadStore('stores/auth.ts', {
    '@/api/session': t.api, '@/api/modules': { authApi: { me: async () => ({ userId: 42, roles: ['ADMIN'] }) } },
  });
  const auth = authModule.useAuthStore();
  await auth.fetchMe();
  const observationModule = loadStore('stores/observability.ts', {
    '@/stores/auth': authModule, '@/api/observability': { observabilityApi: {} },
    '@/utils/observability': { filterTopology: (nodes, edges) => ({ nodes, edges }) },
  });
  const observation = observationModule.useObservabilityStore();
  observation.selectedCiCode = 'keep-selected-draft-node';
  t.context.transport = async (_url, options) => response(options);
  await t.api.ensureAccessToken(auth.token); await vue.nextTick();
  assert.equal(auth.token, 'test-access-new');
  assert.equal(auth.user.userId, 42);
  assert.equal(observation.selectedCiCode, 'keep-selected-draft-node');
  t.api.saveLogin(initial({ sessionId: 'a-new-login' })); await vue.nextTick();
  assert.equal(auth.user, null); assert.equal(observation.selectedCiCode, '');
  console.log('PASS actual Pinia auth/observability stores preserve selection on renewal and clear it on new login');
}
