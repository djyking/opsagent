import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
function evaluate(source, imports = {}) { const module = { exports: {} }; new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(name => imports[name] ?? require(name), module, module.exports); return module.exports; }
const { renderAnswer } = evaluate(read('utils/answer-markdown.ts'));
const raw = '# 真实标题\n\n原文件标记 [chunk:42]\n\n<script>alert(1)</script>\n<img src=x onerror=alert(1)>\n\n[恶意](javascript:alert(1))\n\n![远程图片](https://example.invalid/tracker.png)\n\n```html\n<iframe src=x></iframe>\n```\n\n**完整结尾**';
const html = await renderToString(vue.createSSRApp({ render: () => renderAnswer(raw, { stripChunkMarkers: false }) }));
assert.ok(html.includes('[chunk:42]') && html.includes('<strong>完整结尾</strong>'));
assert.ok(!html.includes('<script') && !html.includes('<img') && !html.includes('<iframe') && !html.includes('href="javascript:'), 'source Markdown must never execute HTML, JS URLs or load images');
assert.ok(html.includes('&lt;script&gt;') && html.includes('&lt;iframe'));
const defaultAnswer = await renderToString(vue.createSSRApp({ render: () => renderAnswer('旧回答 [chunk:42]') }));
assert.ok(!defaultAnswer.includes('[chunk:42]'), 'existing answer default behavior stays unchanged');

const actor = vue.reactive({ identity: 'visitor-A', user: { userId: -1 } });
const reads = [];
function fixture() {
  const props = vue.reactive({ documentId: 1036, version: 1 });
  const { descriptor } = compiler.parse(read('components/knowledge/KnowledgeDocumentContent.vue'));
  const script = compiler.compileScript(descriptor, { id: 'document-content' });
  const unmount = [], scope = vue.effectScope();
  const component = evaluate(script.content, {
    vue: { ...vue, onBeforeUnmount: callback => unmount.push(callback) },
    '@/stores/auth': { useAuthStore: () => actor },
    '@/utils/answer-markdown': { renderAnswer },
    '@/api/http': { request: config => new Promise((resolve, reject) => reads.push({ config, resolve, reject })) },
  }).default;
  const state = scope.run(() => component.setup(props, { expose() {} }));
  return { props, state, stop() { unmount.forEach(callback => callback()); scope.stop(); } };
}
const flush = async () => { await Promise.resolve(); await vue.nextTick(); await Promise.resolve(); };
const response = (extra = {}) => ({ documentId: 1036, version: 1, status: 'AVAILABLE', format: 'MARKDOWN', source: 'ORIGINAL_FILE_TEXT', text: raw, pageNum: 1, totalPages: 1, totalCharacters: raw.length, notice: '', ...extra });
const panel = fixture();
assert.equal(reads.length, 1, 'body is loaded only when its detail component is mounted');
assert.equal(reads[0].config.url, '/api/knowledge/documents/1036/content');
assert.deepEqual(reads[0].config.params, { pageNum: 1, version: 1 });
reads.shift().resolve(response()); await flush();
assert.equal(panel.state.page.value.text, raw); assert.equal(panel.state.markdown.value, true);
const next = panel.state.load(2); assert.equal(panel.state.page.value, undefined, 'previous private text is cleared while changing pages');
const second = reads.shift(); assert.equal(second.config.params.pageNum, 2);
second.resolve(response({ pageNum: 2, totalPages: 3 })); await next;
assert.equal(panel.state.markdown.value, false, 'long Markdown is shown as exact text instead of a broken partial Markdown document');
const stale = panel.state.load(); const pending = reads.shift();
actor.identity = 'visitor-B'; actor.user = { userId: -2 }; await flush();
assert.equal(pending.config.signal.aborted, true);
pending.resolve(response({ text: 'visitor A private text' })); await stale;
assert.equal(panel.state.page.value, undefined, 'late response from old visitor cannot reappear');
panel.stop();

const unavailable = fixture();
reads.shift().resolve(response({ status: 'SOURCE_MISSING', text: '', source: 'UNAVAILABLE', totalPages: 0, notice: '原文件缺失，已有切片仍可查看' })); await flush();
assert.equal(unavailable.state.page.value.status, 'SOURCE_MISSING'); assert.equal(unavailable.state.page.value.text, '');
const failed = unavailable.state.load(); reads.shift().reject(new Error('VISITOR_REVOKED')); await failed;
assert.equal(unavailable.state.page.value, undefined); assert.match(unavailable.state.error.value, /VISITOR_REVOKED/);
unavailable.stop();
console.log('PASS source Markdown safety/preservation, lazy detail loading, pagination, missing body, revocation and identity-race clearing');
