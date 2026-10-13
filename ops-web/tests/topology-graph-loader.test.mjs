import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const vue = require('vue');
const compiler = require('vue/compiler-sfc');
const source = path => readFileSync(new URL('../' + path, import.meta.url), 'utf8');
function evaluate(text, imports) {
  const js = ts.transpileModule(text, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(id => imports[id] ?? require(id), module, module.exports);
  return module.exports;
}
const { createTopologyGraphLoader } = evaluate(source('src/utils/load-topology-graph.ts'), {
  'virtual:opsagent-graph-runtime-url': { runtimePath: '/assets/graph.js', cdnOrigin: '' },
});
const origin = 'https://opsagent.cloud';
const cdn = 'https://www.opsagent.cloud';
const localRuntime = { Graph: class LocalGraph {} }, cdnRuntime = { Graph: class CdnGraph {} };
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function loader(importer, extra = {}) {
  return createTopologyGraphLoader({ path: '/assets/graph.js', cdnOrigin: cdn, pageOrigin: () => origin, importer, timeoutMs: 20, ...extra });
}
{
  const requests = [], cdnResult = deferred();
  const load = loader(url => { requests.push(url); return cdnResult.promise; });
  const first = load(), second = load();
  assert.equal(first, second, 'prefetch/component mounts share one promise');
  cdnResult.resolve(cdnRuntime);
  assert.equal(await first, cdnRuntime); assert.equal(await load(), cdnRuntime);
  assert.deepEqual(requests, [cdn + '/assets/graph.js']);
}
{
  const requests = [];
  const load = loader(async url => { requests.push(url); return localRuntime; }, { cdnOrigin: '' });
  assert.equal(await load(), localRuntime); assert.deepEqual(requests, [origin + '/assets/graph.js']);
}
for (const failure of ['network', 'missing-export']) {
  const requests = [];
  const load = loader(async url => {
    requests.push(url);
    if (url.startsWith(cdn)) { if (failure === 'network') throw Error('503/CORS failure'); return {}; }
    return localRuntime;
  });
  assert.equal(await load(), localRuntime); assert.equal(await load(), localRuntime);
  assert.deepEqual(requests, [cdn + '/assets/graph.js', origin + '/assets/graph.js']);
}
{
  const cdnResult = deferred(), originResult = deferred(), fallbackStarted = deferred(), requests = [];
  const load = loader(url => {
    requests.push(url);
    if (url.startsWith(cdn)) return cdnResult.promise;
    fallbackStarted.resolve(); return originResult.promise;
  });
  const selected = load();
  await fallbackStarted.promise;
  cdnResult.resolve(cdnRuntime);
  assert.equal(await selected, cdnRuntime, 'CDN completing after fallback starts must not wait for slower origin');
  originResult.resolve(localRuntime); await Promise.resolve();
  assert.equal(await load(), cdnRuntime, 'late origin cannot replace the already selected CDN');
  assert.deepEqual(requests, [cdn + '/assets/graph.js', origin + '/assets/graph.js']);
}
{
  const cdnResult = deferred(), fallbackFailed = deferred();
  const load = loader(async url => {
    if (url.startsWith(cdn)) return cdnResult.promise;
    fallbackFailed.resolve(); throw Error('origin unavailable');
  });
  const selected = load();
  await fallbackFailed.promise;
  cdnResult.resolve(cdnRuntime);
  assert.equal(await selected, cdnRuntime, 'a failed fallback must not reject a successful CDN import');
}
{
  const late = deferred(), requests = [];
  const load = loader(url => { requests.push(url); return url.startsWith(cdn) ? late.promise : Promise.resolve(localRuntime); });
  assert.equal(await load(), localRuntime);
  late.resolve(cdnRuntime); await Promise.resolve();
  assert.equal(await load(), localRuntime, 'late CDN resolution never replaces selected runtime');
  assert.deepEqual(requests, [cdn + '/assets/graph.js', origin + '/assets/graph.js']);
}
{
  const requests = []; let recovered = false;
  const load = loader(async url => {
    requests.push(url);
    if (url.startsWith(cdn)) return new Promise(() => {});
    if (!recovered) throw Error('fallback unavailable');
    return localRuntime;
  }, { failureGraceMs: 20 });
  await assert.rejects(load(), /fallback unavailable/, 'stalled CDN plus failed origin must settle after bounded grace');
  recovered = true;
  assert.equal(await load(), localRuntime, 'failure releases pending and permits an origin retry');
  assert.deepEqual(requests, [cdn + '/assets/graph.js', origin + '/assets/graph.js', origin + '/assets/graph.js']);
}
{
  const requests = []; let recovered = false;
  const load = loader(async url => { requests.push(url); if (!recovered) throw Error('offline'); return localRuntime; });
  await assert.rejects(load(), /offline/); recovered = true;
  assert.equal(await load(), localRuntime);
  assert.deepEqual(requests, [cdn + '/assets/graph.js', origin + '/assets/graph.js', origin + '/assets/graph.js'], 'retry remains on origin');
}
console.log('PASS graph loader CDN/disabled/failure/deadline/first-success/late resolution/concurrent calls/retry');

// Exercise the actual component lifecycle: an import completed after navigation must not initialize G6.
{
  const mounted = [], unmounted = [], pending = deferred(); let graphs = 0;
  const { descriptor } = compiler.parse(source('src/components/observability/ServiceTopology.vue'));
  const script = compiler.compileScript(descriptor, { id: 'graph-loader-unmount' });
  const component = evaluate(script.content, {
    vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmounted.push(fn) },
    '@/utils/load-topology-graph': { loadTopologyGraphRuntime: () => pending.promise },
    '@/utils/observability-icons': {}, '@/utils/observability': {},
    '@/utils/observability-layout': {}, '@/utils/observability-graph-lifecycle': { destroyTopologyGraph() {} },
    '@/styles/pages/observability.css': {},
  }).default;
  const previousDocument = globalThis.document, previousWindow = globalThis.window;
  globalThis.document = { removeEventListener() {} }; globalThis.window = { removeEventListener() {} };
  const scope = vue.effectScope();
  try {
    const state = scope.run(() => component.setup({ nodes: [], edges: [] }, { expose() {}, emit() {} }));
    state.host.value = { removeEventListener() {} };
    const mounting = Promise.all(mounted.map(fn => fn()));
    await vue.nextTick(); unmounted.forEach(fn => fn());
    pending.resolve({ Graph: class { constructor() { graphs++; } } });
    await mounting; assert.equal(graphs, 0);
  } finally { scope.stop(); globalThis.document = previousDocument; globalThis.window = previousWindow; }
}
console.log('PASS actual ServiceTopology unmounted during runtime download creates no graph');
