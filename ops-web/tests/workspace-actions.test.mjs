import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
function load(path) {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
  const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function('require', 'module', 'exports', js)(require, module, module.exports);
  return module.exports;
}
const { workspaceActions, searchWorkspaceActions, actionDestination } = load('data/workspace-actions.ts');
const action = id => workspaceActions.find(item => item.id === id);
for (const [query, expected] of [
  ['创建工单', 'ticket-create'], ['新建工单', 'ticket-create'], ['工单', 'ticket-search'],
  ['排查 Redis', 'rag'], ['分析 RabbitMQ 消息堆积', 'rag'], ['ＲＥＤＩＳ', 'rag'],
  ['今天谁在值班', 'oncall'], ['查询 SLA', 'sla'], ['服务拓扑', 'cmdb'],
  ['知识库', 'knowledge'], ['上传文档', 'knowledge-upload'], ['系统监控', 'monitor'],
  ['知识审核', 'review'], ['索引管理', 'index'], ['操作审计', 'audit'],
]) assert.equal(searchWorkspaceActions(query, true)[0]?.id, expected, query);
assert.deepEqual(searchWorkspaceActions('完全不相关的苹果派配方', true), []);
assert.equal(searchWorkspaceActions('', false).length, 6);
assert.equal(searchWorkspaceActions('   ', false).length, 6);
assert.deepEqual(searchWorkspaceActions('知识', false), searchWorkspaceActions('知识', false), 'ranking is deterministic');
for (const query of ['', '审核', '索引', '告警', '审计', '知识']) {
  assert.ok(searchWorkspaceActions(query, false).every(item => !item.admin), query);
}
for (const id of ['alerts', 'review', 'index', 'audit']) {
  assert.ok(searchWorkspaceActions(action(id).label, true).includes(action(id)));
  assert.ok(!searchWorkspaceActions(action(id).label, false).includes(action(id)));
}
console.log('PASS capability matching: goals, feature names, aliases, normalization, stable ranking and permissions');

assert.deepEqual(actionDestination(action('ticket-create'), '创建工单'), { path: '/tickets', query: { create: '1' } });
assert.deepEqual(actionDestination(action('knowledge-upload')), { path: '/knowledge', query: { upload: '1' } });
for (const query of ['查询工单', '工单', '工单中心', ' 搜索工单 ']) {
  assert.deepEqual(actionDestination(action('ticket-search'), query), { path: '/tickets' });
}
assert.deepEqual(actionDestination(action('ticket-search'), '搜索工单 Redis'), { path: '/tickets', query: { keyword: 'Redis' } });
assert.deepEqual(actionDestination(action('ticket-search'), '查询工单：网关超时'), { path: '/tickets', query: { keyword: '网关超时' } });
const original = '  网关 502 / 帮我看看  ';
assert.deepEqual(actionDestination(action('ticket-search'), original), { path: '/tickets', query: { keyword: original } });
assert.deepEqual(actionDestination(action('rag'), original), { path: '/rag/chat', query: { new: '1', draft: original } });
assert.deepEqual(actionDestination(action('rag'), '排查 Redis'), { path: '/rag/chat', query: { new: '1', draft: '排查 Redis' } });
for (const query of ['', '智能问答', '智能排障']) {
  assert.deepEqual(actionDestination(action('rag'), query), { path: '/rag/chat', query: { new: '1' } });
}
const destination = actionDestination(action('ticket-create'));
destination.query.create = 'changed';
assert.equal(action('ticket-create').query.create, '1', 'navigation must not mutate the catalog');
const routerSource = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8');
const routerPaths = new Set([...routerSource.matchAll(/path:\s*['"]([^'"]+)['"]/g)].map(match => '/' + match[1].replace(/^\//, '')));
assert.equal(new Set(workspaceActions.map(item => item.id)).size, workspaceActions.length);
for (const item of workspaceActions) assert.ok(routerPaths.has(item.path), `${item.id} must navigate to an existing route`);
for (const capability of load('data/experience.ts').capabilities) {
  assert.equal(action(capability.key).path, capability.to);
  assert.equal(action(capability.key).admin, capability.admin);
}
console.log('PASS destinations: real routes, create/upload forms, feature-only search, original fallback text and new RAG draft');
