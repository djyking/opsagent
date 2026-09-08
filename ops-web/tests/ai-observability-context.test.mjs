import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
function load(path, imports = {}) {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => imports[id] ?? require(id), module, module.exports);
  return module.exports;
}
const context = load('utils/ai-context.ts');
assert.deepEqual(context.serverObservabilityContext({ service: 'ops-rag-service', environment: 'PROD', timeRange: '30m' }),
  { service: 'ops-rag-service', environment: 'PROD', timeRange: '30m' });
assert.deepEqual(context.serverObservabilityContext({ service: 'ops-demo-order-service' }),
  { service: 'ops-demo-order-service', environment: 'DEMO', timeRange: '15m' });
assert.equal(context.serverObservabilityContext({ service: 'ops-rag-service' }, 'Sentinel 限流原理'), undefined);
assert.equal(context.serverObservabilityContext({}), undefined);
assert.equal(context.serverObservabilityContext({ service: 'bad/service' }), undefined);
const requests = []; let failService = false, failTraffic = false;
const observedAt = new Date().toISOString();
const service = { node: { ciCode: 'ops-rag-service', health: 'DEGRADED', statusReason: '发现限流；password=private-secret', observedAt,
  endpoint: 'http://admin:private-password@host', description: 'private-free-text', metrics: { rps: 2, errorRate: 0.5, p95Ms: 125, healthyInstances: 1, totalInstances: 1 },
  activeAlertCount: 1, secret: 'private-key' }, alertsAvailable: true, alerts: [{ title: 'RAG Block', severity: 'WARNING', raw: 'private-alert' }],
  recentChanges: [{ content: 'private-config' }], recentRuns: [{ payload: 'private-run' }] };
const traffic = { status: 'AVAILABLE', serviceId: 'ops-rag-service', passQps: 2, blockQps: 1, avgRt: 125, activeThreads: 1, observedAt, raw: 'private-traffic' };
const api = load('api/ai-observability-context.ts', {
  '@/utils/ai-context': context,
  './http': { request: async config => { requests.push(config); if (config.url.includes('/observability/')) { if (failService) throw new Error('private upstream credentials'); return service; } if (failTraffic) throw new Error('private token'); return traffic; } },
});
const projection = api.projectServiceEvidence(service, 'ops-rag-service');
assert.equal(projection.health, 'DEGRADED'); assert.equal(projection.errorRatePercent, 0.5, 'Prometheus API contract already expresses error rate in percent');
assert.equal(projection.healthyInstances, 1); assert.equal(projection.activeAlertCount, 1);
assert.doesNotMatch(JSON.stringify(projection), /private-/);
const stale = api.projectServiceEvidence(service, 'ops-rag-service', Date.now() + 120000);
assert.equal(stale.health, 'UNKNOWN'); assert.equal(stale.rps, null);
assert.equal(api.projectServiceEvidence(service, 'redis').status, 'UNAVAILABLE');
assert.equal(api.projectTrafficEvidence({ ...traffic, status: 'NOT_INTEGRATED' }, 'ops-rag-service').blockQps, null);
assert.equal(api.projectTrafficEvidence(traffic, 'redis').status, 'UNAVAILABLE');
assert.equal(api.projectTrafficEvidence(traffic, 'ops-rag-service', Date.now() + 120000).blockQps, null);

const scope = { service: 'ops-rag-service', environment: 'PROD', timeRange: '15m' };
const evidence = await api.readAiObservabilityEvidence(scope);
assert.equal(requests.length, 2); assert.deepEqual(requests[0].params, { environment: 'PROD', timeRange: '15m' });
assert.deepEqual(requests[1].params, { ciCode: 'ops-rag-service' }); assert.equal(requests[0].timeout, 8000);
assert.match(evidence, /observability\/services/); assert.match(evidence, /traffic\/summary/); assert.match(evidence, /RAG Block/);
assert.doesNotMatch(evidence, /private-/); assert.match(evidence, /"errorRatePercent":0.5/);
assert.ok(context.contextualQuestion('检查当前服务', scope, evidence).length <= 2000);
const original = context.contextualQuestion('检查当前服务', scope, evidence);
assert.equal(context.assistantQuestionBody(original), '检查当前服务');
assert.match(context.assistantQuestionEvidence(original), /服务端只读现场快照/);
assert.equal(context.contextualQuestion(original, scope, evidence), original, 'Repeating a question replaces rather than duplicates its generated evidence');
failService = true;
let degraded = await api.readAiObservabilityEvidence(scope);
assert.match(degraded, /服务观测证据未获取/); assert.match(degraded, /"blockQps":1/); assert.doesNotMatch(degraded, /private/);
failTraffic = true; degraded = await api.readAiObservabilityEvidence(scope);
assert.match(degraded, /Sentinel 证据未获取/); assert.doesNotMatch(degraded, /private/);
const length = requests.length;
assert.equal(await api.readAiObservabilityEvidence({ service: 'bad/id?token=x' }), '');
const controller = new AbortController(); controller.abort();
assert.equal(await api.readAiObservabilityEvidence(scope, controller.signal), ''); assert.equal(requests.length, length);
console.log('PASS authorized service/traffic reads, bounded safe evidence projection, source/time metadata, percentage units, stale/wrong-service exclusion, independent failure and aborted/invalid scope');
