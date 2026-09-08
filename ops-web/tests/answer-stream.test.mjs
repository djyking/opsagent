import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const { createSSRApp } = require('vue');
const { renderToString } = require('vue/server-renderer');
function load(path, replace = source => source) {
  const source = replace(readFileSync(fileURLToPath(new URL('../src/' + path, import.meta.url)), 'utf8'));
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  const localRequire = name => name === './session'
    ? { sessionFetch: (url, options) => globalThis.fetch(url, options) }
    : require(name);
  new Function('require', 'module', 'exports', js)(localRequire, module, module.exports);
  return module.exports;
}
const { renderAnswer } = load('utils/answer-markdown.ts');
const sample = '# 完整回答\n\n**风险**与`redis-cli`\n\n| 步骤 | 说明 |\n| --- | --- |\n| 1 | 检查连接 |\n\n---\n\n```sh\nredis-cli ping\n```\n\n<script>alert(1)</script>\n\n[坏链接](javascript:alert(1))\n\n## 三、证据\n最后一段 [S1]';
const html = await renderToString(createSSRApp({render: () => renderAnswer(sample)}));
assert.ok(html.includes('<strong>风险</strong>'));
assert.ok(html.includes('<table>') && html.includes('检查连接'));
assert.ok(html.includes('<pre>') && html.includes('<hr>'));
assert.ok(html.includes('最后一段 [S1]'));
assert.ok(!html.includes('<script>') && !html.includes('href="javascript:'));
console.log('PASS markdown: headings, emphasis, code, tables, trailing content and HTML/link safety');
globalThis.window = { setTimeout, clearTimeout };
globalThis.localStorage = { getItem: () => null };
const { streamRagAnswer, ragCompletionLabel, ragIncompleteMessage, ragAnswerLabel, normalizeReferences } = load('api/rag-stream.ts', source => source.replaceAll('import.meta.env.VITE_API_BASE_URL', "''"));
const encoder = new TextEncoder();
const events = 'event: token\r\ndata: {"delta":"三、证据"}\r\n\r\nevent: token\r\ndata: {"delta":"\\n结尾完整。"}\r\n\r\nevent: done\r\ndata: {"answer":"三、证据\\n结尾完整。","provider":"test","model":"test","references":[],"metadata":{"generationComplete":true}}\r\n\r\n';
const bytes = encoder.encode(events);
globalThis.fetch = async () => new Response(new ReadableStream({ start(controller) { for (let i = 0; i < bytes.length; i++) controller.enqueue(bytes.slice(i, i + 1)); controller.close(); } }), {headers:{"Content-Type":"text/event-stream"}});
let collected = '';
const result = await streamRagAnswer({question:'x'}, {onToken: token => { collected += token; }});
assert.equal(collected, '三、证据\n结尾完整。');
assert.equal(result.answer, collected);
assert.equal(ragCompletionLabel({...result, metadata:{generationComplete:false}}), '回答未完成');
globalThis.fetch = async () => new Response('event: token\ndata: {"delta":"残文"}\n\n', {headers:{'Content-Type':'text/event-stream'}});
await assert.rejects(streamRagAnswer({question:'x'}), /提前结束/);
console.log('PASS SSE: byte-split UTF-8, split CRLF, complete final answer, incomplete metadata and missing done detection');
globalThis.fetch = async () => new Response(JSON.stringify({code:40400,message:'会话不存在或不可访问'}), {headers:{'Content-Type':'application/json'}});
await assert.rejects(streamRagAnswer({question:'x'}), /会话不存在或不可访问/);
console.log('PASS SSE endpoint preserves JSON business errors');

const directorySource = { sourceId: 'S1', sourceType: 'CMDB', sourceUrl: '/itsm/cmdb', documentId: 0, chunkId: 0, chunkIndex: 0, documentName: '服务目录', sourceUpdatedAt: '2026-09-05T10:00:00', sourceRetrievedAt: '2026-09-05T11:00:00Z' };
const directoryResult = { answer: '当前有 6 个服务。 [S1]', provider: 'cmdb', model: 'cmdb-readonly', references: [directorySource], metadata: { generationComplete: true } };
const statuses = [];
let sentRequest;
globalThis.fetch = async (url, options) => {
  sentRequest = { url, body: JSON.parse(options.body) };
  return new Response(`event: status\ndata: {"phase":"cmdb"}\n\nevent: sources\ndata: ${JSON.stringify({ references: [directorySource] })}\n\nevent: token\ndata: {"delta":"当前有 6 个服务。 [S1]"}\n\nevent: done\ndata: ${JSON.stringify(directoryResult)}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } });
};
let emittedSources;
const catalogAnswer = await streamRagAnswer({ question: '服务清单', ticketId: 2053, documentId: 1021 }, { onStatus: value => statuses.push(value), onSources: value => emittedSources = value });
assert.equal(sentRequest.body.ticketId, 2053, 'ticket scope must be sent to the backend');
assert.equal(sentRequest.body.documentId, 1021, 'explicit document scope must survive alongside ticket scope');
assert.deepEqual(statuses, ['正在读取服务目录', '正在返回查询结果']);
assert.equal(ragCompletionLabel(catalogAnswer), '查询完成');
assert.equal(ragAnswerLabel(catalogAnswer), '服务目录 · 实时读取');
const unavailableCatalog = { ...catalogAnswer, metadata: { generationComplete: true, degraded: true, degradedReason: 'CMDB_UNAVAILABLE' } };
assert.equal(ragCompletionLabel(unavailableCatalog), '目录暂不可用');
assert.equal(ragAnswerLabel(unavailableCatalog), '服务目录 · 读取失败');
for (const key of ['sourceId', 'sourceType', 'sourceUrl', 'sourceUpdatedAt', 'sourceRetrievedAt']) {
  assert.equal(catalogAnswer.references[0][key], directorySource[key]);
  assert.equal(emittedSources[0][key], directorySource[key]);
  assert.equal(normalizeReferences(catalogAnswer.references)[0][key], directorySource[key], 'history normalization must preserve directory provenance');
}
await streamRagAnswer({ question: '服务依赖', ticketId: 2053, conversationId: 'session-test' });
assert.equal(sentRequest.url, '/api/rag/conversations/session-test/stream');
assert.equal(sentRequest.body.ticketId, 2053);
console.log('PASS scoped Q&A: ticket/document scope on both stream routes, CMDB progress, provenance and history normalization');

await streamRagAnswer({ question: '当前服务健康', provider: 'openai', conversationId: 'model-selection' });
assert.equal(sentRequest.body.provider, 'openai', 'selected provider must reach conversation stream');
assert.equal(sentRequest.url, '/api/rag/conversations/model-selection/stream');
await streamRagAnswer({ question: '解释连接池', provider: 'kimi' });
assert.equal(sentRequest.body.provider, 'kimi', 'selected provider must reach standalone stream without frontend fallback');
const observationScope = { service: 'ops-rag-service', environment: 'PROD', timeRange: '15m' };
await streamRagAnswer({ question: '当前服务健康', observabilityContext: observationScope, conversationId: 'scoped-observation' });
assert.deepEqual(sentRequest.body.observabilityContext, observationScope, 'structured observation scope must actually reach the backend JSON payload');
assert.equal(sentRequest.url, '/api/rag/conversations/scoped-observation/stream');
const observationReference = normalizeReferences([{ ...directorySource, sourceType: 'OBSERVABILITY_EVIDENCE', evidenceBundleId: 'bundle-safe', evidenceId: 'prometheus:rag' }])[0];
assert.equal(observationReference.evidenceBundleId, 'bundle-safe');
assert.equal(observationReference.evidenceId, 'prometheus:rag');
const runtimeSource = { ...directorySource, sourceType: 'OPERATIONS', sourceUrl: '/operations', documentName: 'Prometheus 指标与趋势' };
const normalizedRuntime = normalizeReferences([runtimeSource])[0];
for (const key of ['sourceType', 'sourceUrl', 'sourceUpdatedAt', 'sourceRetrievedAt']) assert.equal(normalizedRuntime[key], runtimeSource[key]);
assert.equal(ragCompletionLabel({ ...result, provider: 'operations', metadata: { degradedReason: 'OPERATIONS_UNAVAILABLE' } }), '运行数据暂不可用');
assert.equal(ragAnswerLabel({ ...result, provider: 'operations' }), '实时运行数据 · 直接读取');
console.log('PASS model selection payload and runtime source provenance survive both stream routes and history normalization');

const budgetResult = { answer: '保留已生成的内容', metadata: { generationComplete: false, finishReason: 'budget_exhausted', budgetLimit: 10000, budgetUsageKnown: false } };
assert.match(ragIncompleteMessage(budgetResult), /输入、输出和重试累计额度 10,000 token/);
assert.match(ragIncompleteMessage(budgetResult), /已保留生成内容.*缩小问题范围/);
assert.doesNotMatch(ragIncompleteMessage(budgetResult), /继续追问/);
assert.equal(budgetResult.answer, '保留已生成的内容');
console.log('PASS cumulative per-question budget stops preserve partial output and request narrower scope');
