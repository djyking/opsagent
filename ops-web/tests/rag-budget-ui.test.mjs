import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc'), { renderToString } = require('vue/server-renderer');
function component(path, imports = {}) {
  const { descriptor } = compiler.parse(readFileSync(new URL('../src/' + path, import.meta.url), 'utf8'));
  const source = compiler.compileScript(descriptor, { id: path, inlineTemplate: true }).content;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(name => name === 'vue' ? vue : imports[name] ?? {}, module, module.exports);
  return module.exports.default;
}
const Budget = component('components/RagBudgetEvidence.vue');
const evidence = await renderToString(vue.createSSRApp(Budget, { result: { metadata: { budgetLimit: 10000, budgetChargedTokens: 9600, budgetUsageKnown: false, requestAttempts: 2 } } }));
assert.match(evidence, /10,000 token/); assert.match(evidence, /含未知用量的保守预留/); assert.match(evidence, /不代表精确计费用量/); assert.doesNotMatch(evidence, /<details[^>]* open/);
const slot = { setup: (_, { slots }) => () => vue.h('section', slots.default?.()) };
const conversation = { question: '', sessions: [], turns: [{ id: 1, status: 'PROCESSING', question: '问题', answer: '已经开始返回的内容' }], providers: [], references: [], total: 0, busy: true, turnLabel: () => '生成中' };
const Workspace = component('views/RagWorkspaceView.vue', {
  '@/api/rag-stream': { ragAnswerLabel: () => '实际配置模型' }, '@/composables/useRagConversations': { useRagConversations: () => conversation },
  '@/stores/ai-assistant': { useAiAssistantStore: () => ({}) }, '@/utils/ai-context': { assistantQuestionBody: value => value, assistantQuestionEvidence: () => '' },
  '@/components/PageHeader.vue': { default: slot }, '@/components/BaseModal.vue': { default: slot }, '@/components/AnswerContent.vue': { default: { props: ['content'], setup: props => () => vue.h('p', props.content) } }, '@/components/RagSources.vue': { default: slot }, '@/components/RagBudgetEvidence.vue': { default: Budget },
});
const app = vue.createSSRApp(Workspace); app.component('RouterLink', slot); app.config.warnHandler = () => {};
const html = await renderToString(app); assert.match(html, /已经开始返回的内容/); assert.doesNotMatch(html, /class="answer-skeleton"/, 'a partially streamed answer must not also display an empty-answer skeleton');
console.log('PASS RAG presentation: partial stream stays visible, budget details collapsed, unknown usage never presented as exact billing');
