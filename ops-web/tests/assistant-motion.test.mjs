import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const placementModule = { exports: {} };
new Function('module', 'exports', ts.transpileModule(readFileSync(new URL('../src/utils/assistant-placement.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(placementModule, placementModule.exports);
const { assistantPlacement } = placementModule.exports;
const obstacle = { left: 1220, top: 580, right: 1330, bottom: 690 };
const placed = assistantPlacement({ left: 1238, top: 594 }, { width: 64, height: 78 }, { width: 1366, height: 768 }, [obstacle]);
assert(placed.left + 64 + 8 <= obstacle.left || placed.left - 8 >= obstacle.right || placed.top + 78 + 8 <= obstacle.top || placed.top - 16 >= obstacle.bottom, 'the nearest available position must avoid the service node');
assert.deepEqual(assistantPlacement({ left: 500, top: 400 }, { width: 64, height: 78 }, { width: 1366, height: 768 }, []), { left: 500, top: 400 });
const { descriptor } = compiler.parse(readFileSync(new URL('../src/components/ai/AiAssistantOrb.vue', import.meta.url), 'utf8'));
const script = compiler.compileScript(descriptor, { id: 'assistant-motion-test' });
const actor = vue.reactive({ user: { userId: 1 } });
const assistant = vue.reactive({ open: false, minimized: false, busy: false, show() { this.open = true; } });
const mounted = [], unmount = [], stored = new Map();
let tick;
const document = { hidden: false, activeElement: null, body: {}, modal: false, querySelector() { return this.modal ? {} : null; }, addEventListener() {}, removeEventListener() {} };
const reduced = { matches: false, addEventListener() {}, removeEventListener() {} };
const imports = { vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn) }, '@lucide/vue': {},
  '@/stores/auth': { useAuthStore: () => actor }, '@/stores/ai-assistant': { useAiAssistantStore: () => assistant }, '@/utils/assistant-placement': placementModule.exports };
const module = { exports: {} };
new Function('require', 'module', 'exports', 'document', 'window', 'MutationObserver', 'localStorage', 'setInterval', 'clearInterval',
  ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(
  id => imports[id], module, module.exports, document, { matchMedia: () => reduced }, class { observe() {} disconnect() {} },
  { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) }, fn => { tick = fn; return 1; }, () => { tick = undefined; });
const scope = vue.effectScope();
const state = scope.run(() => module.exports.default.setup({}, { expose() {} }));
mounted.forEach(fn => fn());
tick(); assert.equal(state.jumping.value, true);
for (const condition of ['hidden', 'input', 'orbFocus', 'modal', 'reduced', 'busy']) {
  state.jumping.value = false;
  document.hidden = condition === 'hidden'; document.modal = condition === 'modal'; reduced.matches = condition === 'reduced'; assistant.busy = condition === 'busy';
  document.activeElement = { closest: () => condition === 'orbFocus' ? {} : null, matches: () => condition === 'input' };
  tick(); assert.equal(state.jumping.value, false, `${condition} must suppress the next scheduled hop`);
}
document.hidden = document.modal = reduced.matches = assistant.busy = false; document.activeElement = null;
state.toggleMotion(); state.minimize(true); tick(); assert.equal(state.jumping.value, false);
actor.user = { userId: 2 }; await vue.nextTick(); assert.equal(state.motion.value, true); assert.equal(assistant.minimized, false);
actor.user = { userId: 1 }; await vue.nextTick(); assert.equal(state.motion.value, false); assert.equal(assistant.minimized, true);
state.minimize(false);
const drag = { button: 0, pointerId: 1, clientX: 1238, clientY: 594, currentTarget: { setPointerCapture() {} }, preventDefault() {} };
state.dragStart(drag); state.dragMove({ ...drag, clientX: 1240 }); assert.equal(state.dragging.value, false, 'pointer jitter remains a click');
state.dragMove({ ...drag, clientX: -500, clientY: -500 }); assert.equal(state.dragging.value, true); state.dragEnd(drag); state.openAssistant(); assert.equal(assistant.open, false, 'a drag cannot accidentally submit/open chat');
assert.equal(state.preferredPosition.value.left, 12); assert.equal(state.preferredPosition.value.top, 36, 'drag clamps inside viewport');
actor.user = { userId: 2 }; await vue.nextTick(); assert.equal(state.position.value, undefined);
actor.user = { userId: 1 }; await vue.nextTick(); assert.equal(state.preferredPosition.value.left, 12, 'drag position survives account switching');
state.resetPosition(); assert.equal(state.position.value, undefined); state.openAssistant(); assert.equal(assistant.open, true, 'a subsequent deliberate click opens the assistant');
unmount.forEach(fn => fn()); scope.stop(); assert.equal(tick, undefined);
console.log('PASS assistant: quiet motion, per-account drag persistence, click/drag distinction, viewport bounds, reset and node avoidance');
