import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const read = name => readFileSync(new URL('../src/' + name, import.meta.url), 'utf8');
function evaluate(source, imports = {}) {
  const module = { exports: {} };
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  new Function('require', 'module', 'exports', js)(id => Object.hasOwn(imports, id) ? imports[id] : require(id), module, module.exports);
  return module.exports;
}
const presentation = evaluate(read('utils/knowledge-review-summary.ts'));
assert.equal(presentation.knowledgeRetrievalLabel('PUBLISHED', 'PENDING'), '已发布，等待索引');
assert.equal(presentation.knowledgeRetrievalLabel('IN_REVIEW', 'SUCCESS'), '审核发布后再建立索引');
assert.match(presentation.knowledgeSummary('普通原文').actions, /原文未提供/);
assert.equal(presentation.knowledgeSummary('普通原文').structured, false);
assert.equal(presentation.knowledgeSummary('普通原文').excerpt, '普通原文');
const fullText = '# 事件复盘\n\n## 症状与事实\nRAG请求失败\n\n## 处置运行\n人工修复凭据\n\n## 恢复验证\n真实探针通过\n\n## 证据缺口与适用条件\n根因待复核';
assert.match(presentation.knowledgeSummary(fullText).verification, /真实探针/);
const descriptor = compiler.parse(read('views/KnowledgeReviewView.vue')).descriptor;
const script = compiler.compileScript(descriptor, { id: 'review-hierarchy' });
assert.deepEqual(compiler.compileTemplate({ source: descriptor.template.content, filename: 'KnowledgeReviewView.vue', id: 'review-hierarchy', compilerOptions: { bindingMetadata: script.bindings } }).errors, []);
let version = 2; const approved = [];
const row = { id: 1, originalName: '复盘.md', reviewStatus: 'IN_REVIEW', indexStatus: 'PENDING' };
const imports = {
  vue: { ...vue, onMounted() {}, onBeforeUnmount() {} },
  'vue-router': { useRoute: () => vue.reactive({ query: {} }) },
  '@lucide/vue': new Proxy({}, { get: () => ({}) }),
  '@/api/modules': { itsmApi: { reviewDocuments: async () => [row], approveDocument: async (...args) => { approved.push(args); return { index_status: 'PENDING' }; } } },
  '@/api/knowledge-review': { knowledgeReviewApi: { preview: async () => ({ documentId: 1, version, parseStatus: 'PARSED', reviewStatus: 'IN_REVIEW', total: 1, pageNum: 1, pageSize: 8, chunks: [{ content: fullText }], sourceAvailable: true }), text: async () => ({ version, chunkCount: 1, text: fullText }) } },
  '@/api/http': { request: async () => [] },
  '@/utils/knowledge-review-summary': presentation,
  '@/utils/datetime': { formatDateTime: x => x, formatShortDateTime: x => x },
  '@/composables/usePageFeedback': { usePageFeedback: () => ({ show() {} }) },
  '@/styles/pages/knowledge-review.css': {},
};
for (const component of ['PageHeader','FilterBar','EmptyState','InlineError','LoadingState','ListSurface','DetailPanel','BaseModal','StatusBadge','FormField','PaginationBar']) imports[`@/components/${component}.vue`] = {};
imports['@/components/feedback/ActionButton.vue'] = {};
const scope = vue.effectScope();
const state = scope.run(() => evaluate(script.content, imports).default.setup({}, { expose() {} }));
async function flush() { for (let i=0; i<8; i++) await Promise.resolve(); }
try {
  state.open(row); await flush();
  assert(state.parsedText.value.includes('真实探针'));
  assert.equal(state.fullTextSeen.value, false, 'summary loading must not count as reviewing full text');
  state.acknowledged.value = true; assert.equal(state.canApprove.value, false);
  state.openFullText(); assert.equal(state.canApprove.value, true);
  version = 3; await state.loadPreview(1); await flush();
  assert.equal(state.acknowledged.value, false, 'new content revision must invalidate approval acknowledgement');
  state.acknowledged.value = true; await state.approve(1);
  assert.equal(approved[0][2], 3, 'approval sends the exact content revision reviewed');
  assert.match(state.retrievalLabel.value, /等待索引/);
} finally { scope.stop(); }
console.log('PASS actual knowledge review: summary does not grant approval, version changes reset acknowledgement, publication remains separate from index success');
