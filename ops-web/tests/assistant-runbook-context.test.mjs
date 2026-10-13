import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), { renderToString } = require('vue/server-renderer');
function load(path, imports = {}) {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8').replaceAll('import.meta.env.VITE_API_BASE_URL', "''");
  const module = { exports: {} };
  new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(name => imports[name] || (name === './session' ? {} : require(name)), module, module.exports);
  return module.exports;
}
const context = load('utils/ai-context.ts');
const scope = { service: 'ops-gateway', environment: 'ALL', timeRange: '15m' };
assert.equal(context.requestContextForQuestion(scope, '推荐相关 Runbook').service, 'ops-gateway');
assert.deepEqual(context.serverObservabilityContext(scope, '推荐相关 Runbook'), { service: 'ops-gateway', environment: 'PROD', timeRange: '15m' });
assert.match(context.contextualQuestion('推荐相关 Runbook', scope), /ops-gateway · ALL · 15m/);
assert.deepEqual(context.requestContextForQuestion(scope, '什么是 Runbook？'), {}, 'a general definition still ignores an incidental page');
assert.deepEqual(context.requestContextForQuestion({ ...scope, documentId: 7 }, '推荐相关 Runbook'), { documentId: 7 }, 'an explicit private document keeps its scope');
const { renderAnswer } = load('utils/answer-markdown.ts');
const html = await renderToString(vue.createSSRApp({ render: () => renderAnswer([
  '[具体流程](/automation?tab=workflows&definition=isolated-recovery)',
  '[本人演练](/automation?tab=experience&target=ops-demo-notification-service)',
  '[执行记录](/automation?tab=runs)',
  '[不可信参数](/automation?tab=workflows&definition=javascript:bad)',
  '[外部跳转](/automation?redirect=https://example.com)',
  '[脚本](javascript:alert(1))',
].join('\n\n')) }));
assert.match(html, /href="\/automation\?tab=workflows&amp;definition=isolated-recovery"/);
assert.match(html, /href="\/automation\?tab=experience&amp;target=ops-demo-notification-service"/);
assert.match(html, /href="\/automation\?tab=runs"/);
assert.doesNotMatch(html, /href="[^"\n]*(?:javascript|redirect)/);
const { ragAnswerLabel, ragIncompleteMessage } = load('api/rag-stream.ts');
const incomplete = { provider: 'deepseek', model: 'deepseek-v4-flash', metadata: { generationComplete: false, finishReason: 'budget_exhausted', budgetLimit: 50000, requestAttempts: 0 } };
assert.equal(ragAnswerLabel(incomplete), '本次额度不足 · 未发送模型请求');
assert.match(ragIncompleteMessage(incomplete), /50,000 token/);
assert.match(ragAnswerLabel({ ...incomplete, metadata: { ...incomplete.metadata, finishReason: 'provider_timeout', requestAttempts: 1 } }), /deepseek\/deepseek-v4-flash/);
assert.equal(ragAnswerLabel({ metadata: { retrievalMode: 'RUNBOOK_CATALOG', degraded: false } }), '实际流程目录 · 未执行操作');
const history = load('api/conversations.ts', {
  './rag-stream': load('api/rag-stream.ts'),
  './http': { request: async () => ({ records: [
    { id: 1, errorMessage: '回答未完整生成，可重新提问', result: { ...incomplete, references: [] } },
    { id: 2, errorMessage: '回答未完整生成，可重新提问', result: { ...incomplete, references: [], metadata: { generationComplete: false, finishReason: 'provider_timeout' } } },
    { id: 3, errorMessage: '旧连接中断原因，无结果元数据' },
  ], hasMore: false }) },
});
const turns = (await history.conversationApi.messages('existing-id')).records;
assert.match(turns[0].errorMessage, /50,000 token/);
assert.match(turns[1].errorMessage, /模型响应超时/);
assert.equal(turns[2].errorMessage, '旧连接中断原因，无结果元数据');
console.log('PASS Runbook service scope, private boundary, safe precise navigation links, and truthful 50k failure labels');
