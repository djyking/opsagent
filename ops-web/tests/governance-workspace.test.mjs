import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports) {
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const flush = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };
const quietVue = { ...vue, onBeforeUnmount() {} };
const set = () => ({ type: 'FLOW', label: '流控', supported: true, editable: true, revision: 'a'.repeat(64), persistedRules: [{ resource: 'ops-rag-request', grade: 1, count: 5 }], appliedRules: [], applicationStatus: 'APPLIED' });
function trafficFixture() {
  const auth = vue.reactive({ token: 'token-a', identity: 'actor-a', isAdmin: true }); const ci = vue.ref('ops-rag-service'); const calls = [];
  const api = {
    workspace: async id => ({ summary: { serviceId: id, status: 'AVAILABLE' }, resources: [], ruleSets: [set()] }),
    history: async () => ({ items: [] }), validate: async (_, rules) => ({ valid: true, rules: JSON.parse(JSON.stringify(rules)) }),
    publish: async (type, data) => { calls.push({ type, ...JSON.parse(JSON.stringify(data)) }); throw new Error('response lost'); },
  };
  const scope = vue.effectScope();
  const module = evaluate(read('composables/useTrafficGovernance.ts'), { vue: quietVue, '@/stores/auth': { useAuthStore: () => auth }, '@/api/trafficGovernance': { trafficGovernanceApi: api } });
  const manager = scope.run(() => module.useTrafficGovernance(() => ci.value));
  return { auth, ci, api, calls, manager, stop: () => scope.stop() };
}
{
  const app = trafficFixture();
  try {
    await app.manager.load();
    await app.manager.prepare(app.manager.workspace.value.ruleSets[0], [{ resource: 'ops-rag-request', count: 9 }]);
    assert.equal(app.manager.plan.value.before[0].count, 5, 'Vue proxy must be copied safely');
    const id = app.manager.plan.value.requestId;
    app.auth.token = 'refreshed-token-a';
    assert.equal(app.manager.plan.value.requestId, id, 'Token refresh for the same actor must preserve the reviewed plan');
    await app.manager.confirm('reviewed'); await app.manager.confirm('reviewed');
    assert.equal(app.calls.length, 2); assert.equal(app.calls[0].requestId, id); assert.equal(app.calls[1].requestId, id);
    assert.equal(app.calls[0].expectedRevision, 'a'.repeat(64));
    app.auth.isAdmin = false;
    assert.equal(app.manager.plan.value, undefined); await app.manager.prepare(set(), []); await app.manager.confirm('not permitted');
    assert.equal(app.calls.length, 2);
  } finally { app.stop(); }
}
console.log('PASS reactive rule snapshots, reviewed CAS payload, stable retry identity and immediate role downgrade');
{
  const app = trafficFixture();
  try {
    const delayed = deferred(); app.api.workspace = id => id === 'ops-rag-service' ? delayed.promise : Promise.resolve({ summary: { serviceId: id, status: 'NOT_INTEGRATED' }, ruleSets: [], resources: [] });
    const old = app.manager.load(); app.ci.value = 'redis'; await flush();
    delayed.resolve({ summary: { serviceId: 'ops-rag-service', status: 'AVAILABLE' }, ruleSets: [set()], resources: [] }); await old;
    assert.equal(app.manager.workspace.value.summary.serviceId, 'redis'); assert.deepEqual(app.manager.workspace.value.ruleSets, []);
    const validation = deferred(); app.api.validate = () => validation.promise;
    const pending = app.manager.prepare(set(), []); app.auth.identity = 'actor-b';
    validation.resolve({ valid: true, rules: [] }); await pending;
    assert.equal(app.manager.plan.value, undefined);
  } finally { app.stop(); }
}
console.log('PASS older service snapshots and old actor validation cannot restore actionable rules');
{
  const auth = vue.reactive({ token: 'token-a', identity: 'actor-a' }); const ci = vue.ref('');
  const delayed = deferred();
  const api = {
    list: async () => ({ status: 'AVAILABLE', items: [] }),
    detail: id => id === 'old' ? delayed.promise : Promise.resolve({ item: { id }, content: '******' }),
    history: async () => ({ status: 'AVAILABLE', items: [] }), diff: async () => ({ status: 'AVAILABLE', currentContent: '******', previousContent: '******' }),
  };
  const scope = vue.effectScope();
  const module = evaluate(read('composables/useConfigCenter.ts'), { vue: quietVue, '@/stores/auth': { useAuthStore: () => auth }, '@/api/configCenter': { configCenterApi: api } });
  const manager = scope.run(() => module.useConfigCenter(() => ci.value));
  try {
    const old = manager.select('old'); await manager.select('new');
    delayed.resolve({ item: { id: 'old' }, content: 'old-projection' }); await old;
    assert.equal(manager.detail.value.item.id, 'new'); assert.equal(manager.detail.value.content, '******');
    const compare = deferred(); api.diff = () => compare.promise;
    const pending = manager.compare(3); await manager.select('another');
    compare.resolve({ status: 'AVAILABLE', currentContent: 'old', previousContent: 'old' }); await pending;
    assert.equal(manager.diff.value, undefined);
  } finally { scope.stop(); }
}
console.log('PASS config selection and version comparison suppress stale responses');
