import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const source = readFileSync(new URL('../src/utils/automation-presentation.ts', import.meta.url), 'utf8');
const module = { exports: {} };
new Function('module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(module, module.exports);
const { runWriteSummary } = module.exports;
const { automationNodeDone } = module.exports;
{
  const completed = { status: 'COMPLETED', nodeId: 'end', state: { outputs: { diagnose: {} } }, snapshot: { graph: { nodes: [
    { id: 'diagnose', type: 'AGENT' }, { id: 'end', type: 'END' }, { id: 'alternate', type: 'END' },
  ] } } };
  assert.equal(automationNodeDone('end', completed, []), true, 'END has a completion receipt through run status without an output');
  assert.equal(automationNodeDone('alternate', completed, []), false, 'unvisited END branches stay incomplete');
  assert.equal(automationNodeDone('diagnose', completed, []), true);
  for (const status of ['RUNNING', 'CANCELLED', 'REJECTED', 'EXPIRED', 'NEEDS_ATTENTION', 'BUDGET_EXCEEDED'])
    assert.equal(automationNodeDone('end', { ...completed, status }, []), false, status);
  assert.equal(automationNodeDone('diagnose', { ...completed, state: {} }, [{ nodeId: 'diagnose', type: 'NODE_COMPLETED' }]), true);
  assert.equal(automationNodeDone('diagnose', { ...completed, state: {} }, [{ nodeId: 'diagnose', type: 'MODEL_INTENT' }]), false);
}
const run = { status: 'COMPLETED', approvals: [], state: {}, snapshot: { graph: { nodes: [] } } };
const event = (id, type, call, result) => ({ id, type, nodeId: 'repair', payload: result ? { call, result } : call });
const call = { id: 'write1', name: 'demo_config_restore' };
assert.equal(runWriteSummary(run, []).applied, 0, 'completion without receipts never proves a write');
assert.equal(runWriteSummary(run, [event(1, 'APPROVAL_GRANTED', call)]).applied, 0);
assert.equal(runWriteSummary(run, [event(1, 'TOOL_INTENT', call)]).uncertain, 1);
assert.equal(runWriteSummary(run, [event(1, 'TOOL_OBSERVATION', call, { actionAccepted: false, configurationStatus: 'APPLIED' })]).applied, 0);
assert.equal(runWriteSummary(run, [event(1, 'TOOL_OBSERVATION', call, { actionAccepted: true, configurationStatus: 'APPLIED' })]).applied, 1);
assert.equal(runWriteSummary(run, [event(1, 'TOOL_OBSERVATION', { id: 'read', name: 'demo_target_inspect' }, { configurationStatus: 'APPLIED' })]).applied, 0, 'a read of an applied target is not a write receipt');
const partial = runWriteSummary(run, [event(1, 'TOOL_OBSERVATION', call, { actionAccepted: true, configurationStatus: 'APPLIED' }), event(2, 'TOOL_INTENT', { id: 'write2', name: 'config_change_apply' })]);
assert.deepEqual([partial.applied, partial.uncertain], [1, 1]);
const config = { ...run, state: { observations: { write1: { operation: { status: 'VERIFYING' } } } } };
assert.equal(runWriteSummary(config, [event(1, 'TOOL_INTENT', { ...call, name: 'config_change_apply' })]).uncertain, 1);
config.state.observations.write1.operation.status = 'APPLIED';
assert.equal(runWriteSummary(config, [event(1, 'TOOL_INTENT', { ...call, name: 'config_change_apply' })]).applied, 1);
console.log('PASS run receipts: read/approval/completion cannot imply writes; partial and uncertain outcomes remain distinct');
