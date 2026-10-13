import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports = {}) { const module = { exports: {} }; new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(name => imports[name] ?? {}, module, module.exports); return module.exports; }
function fixture(path, imports, props = {}) { const { descriptor } = compiler.parse(read(path)); const script = compiler.compileScript(descriptor, { id: path }); const unmount = [], scope = vue.effectScope(); const component = evaluate(script.content, { ...imports, vue: { ...vue, onMounted() {}, onBeforeUnmount: fn => unmount.push(fn) } }).default; return { state: scope.run(() => component.setup(props, { expose() {} })), stop() { unmount.forEach(fn => fn()); scope.stop(); } }; }
globalThis.window = { setTimeout: () => 1, clearTimeout() {} };
const actor = vue.reactive({ isAdmin: false, isDemo: true, user: { userId: -1, roles: ['DEMO'] } });
const route = vue.reactive({ query: { documentId: '7' } });
const library = { baseId: 3, expiresAt: '2026-09-09T00:00:00Z', documents: [{ id: 7, original_name: 'private.md', stage: 'INDEXED', chunk_count: 1 }], limits: { files: 3, fileBytes: 5 * 1024 * 1024, totalBytes: 15 * 1024 * 1024 } };
const imports = {
  '@/stores/auth': { useAuthStore: () => actor },
  'vue-router': { useRoute: () => route },
  '@/composables/usePageFeedback': { usePageFeedback: () => ({ show() {} }) },
  '@/utils/knowledge-stage': evaluate(read('utils/knowledge-stage.ts')),
  '@/api/visitor-knowledge': { visitorKnowledgeApi: { get: async () => library } },
  '@/api/http': { request: async ({ url }) => url === '/api/knowledge/bases' ? [{ id: 1 }, { id: 2 }] : url.includes('/1/') ? [{ id: 2 }] : [{ id: 8 }] },
};
const knowledge = fixture('views/KnowledgeView.vue', imports);
await knowledge.state.load();
assert.equal(knowledge.state.knowledgeScope.value, 'experience', 'private citation opens the owners experience library');
knowledge.stop();
route.query = { documentId: '8' };
const publicLink = fixture('views/KnowledgeView.vue', imports);
await publicLink.state.load();
assert.equal(publicLink.state.knowledgeScope.value, 'public');
assert.equal(publicLink.state.selectedBaseId.value, 2, 'public citation locates another authorized library');
assert.equal(publicLink.state.detailDocument.value.id, 8);
publicLink.stop();
route.query = { documentId: '999' };
const missing = fixture('views/KnowledgeView.vue', imports);
await missing.state.load();
assert.match(missing.state.error.value, /无权访问/);
assert.equal(missing.state.detailDocument.value, undefined);
missing.stop();
let chunkResolve, writes = 0;
const panel = fixture('components/knowledge/VisitorKnowledgeLibrary.vue', {
  '@/stores/auth': imports['@/stores/auth'],
  '@/stores/ai-assistant': { useAiAssistantStore: () => ({ setContext() {}, show() {} }) },
  '@/api/visitor-knowledge': { visitorKnowledgeApi: { get: async () => library, chunks: () => new Promise(resolve => { chunkResolve = resolve; }), upload: async () => { writes++; } } },
}, { initialDocumentId: 7 });
await panel.state.load();
assert.equal(panel.state.detailId.value, 7, 'experience citation opens its own document detail');
await panel.state.upload({ name: 'large.md', size: 5 * 1024 * 1024 + 1 });
assert.equal(writes, 0); assert.match(panel.state.error.value, /5 MB/);
const pending = panel.state.showChunks(library.documents[0]);
actor.user = { userId: -2, roles: ['DEMO'] }; await vue.nextTick();
chunkResolve([{ id: 1, content: 'previous visitor private text' }]); await pending;
assert.equal(panel.state.chunks.value.length, 0, 'old identity chunk results cannot reappear');
panel.stop();
console.log('PASS private/public citation routing, inaccessible reference, upload limit and identity race isolation');
