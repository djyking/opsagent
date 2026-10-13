import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const source = readFileSync(new URL('../src/utils/automation-usage.ts', import.meta.url), 'utf8');
const module = { exports: {} };
new Function('module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(module, module.exports);
const { modelUsageSummary, tokenAmount } = module.exports;
const sample = { model: { availability: 'AVAILABLE', coverage: 'COMPLETE', knownInputTokens: 26365,
  knownOutputTokens: 3609, knownTotalTokens: 29974, unknownUsageAttempts: 1 },
  budget: { chargedTokens: 44008 }, embedding: { reservedTokens: 1812 } };
assert.equal(modelUsageSummary(sample), '29,974 Token 已知 · 1 次未知', 'Budget charge and embedding reserves must never inflate known model usage');
assert.equal(modelUsageSummary({ model: { availability: 'UNAVAILABLE', knownTotalTokens: 0 } }), '模型用量暂不可用');
assert.equal(tokenAmount(null), '未知');
assert.equal(tokenAmount(undefined), '未知');
assert.equal(tokenAmount(0), '0');
assert.match(modelUsageSummary({ ...sample, model: { ...sample.model, unknownCountIsLowerBound: true } }), /至少 1 次未知/);
assert.match(modelUsageSummary({ ...sample, model: { ...sample.model, unknownCountIsLowerBound: true, unknownUsageAttempts: 0 } }), /未知次数未完整记录/);
assert.match(modelUsageSummary({ ...sample, model: { ...sample.model, unknownUsageAttempts: 0, pendingCalls: 1 } }), /待回执/);
console.log('PASS usage presentation: confirmed usage separated from reservations, unknown is never zero, incomplete attempts disclosed');
