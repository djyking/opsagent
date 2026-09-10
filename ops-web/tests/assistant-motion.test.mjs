import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const placementModule = { exports: {} };
new Function('module', 'exports', ts.transpileModule(readFileSync(new URL('../src/utils/assistant-placement.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(placementModule, placementModule.exports);
const { assistantPlacement } = placementModule.exports;
function freshWelcomeModule() {
  const module = { exports: {} };
  new Function('module', 'exports', ts.transpileModule(readFileSync(new URL('../src/utils/assistant-welcome.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText)(module, module.exports);
  return module.exports;
}
const welcomeModule = freshWelcomeModule();
const obstacle = { left: 1220, top: 580, right: 1330, bottom: 690 };
const placed = assistantPlacement({ left: 1238, top: 594 }, { width: 64, height: 78 }, { width: 1366, height: 768 }, [obstacle]);
assert(placed.left + 64 + 8 <= obstacle.left || placed.left - 8 >= obstacle.right || placed.top + 78 + 8 <= obstacle.top || placed.top - 16 >= obstacle.bottom, 'the nearest available position must avoid the service node');
assert.deepEqual(assistantPlacement({ left: 500, top: 400 }, { width: 64, height: 78 }, { width: 1366, height: 768 }, []), { left: 500, top: 400 });
const { descriptor } = compiler.parse(readFileSync(new URL('../src/components/ai/AiAssistantOrb.vue', import.meta.url), 'utf8'));
const script = compiler.compileScript(descriptor, { id: 'assistant-motion-test' });
const actor = vue.reactive({ user: { userId: 1 }, identity: 'login-1' });
const assistant = vue.reactive({ open: false, minimized: false, busy: false, show() { this.open = true; } });
const mounted = [], unmount = [], stored = new Map();
stored.set('opsagent-assistant-welcome:v2', JSON.stringify({ 1: Date.now() + 24 * 60 * 60 * 1000 }));
let tick;
let placementRequests = 0;
class Element { constructor(topology = false) { this.topology = topology; } closest() { return this.topology ? {} : null; } }
const document = { hidden: false, activeElement: null, body: {}, modal: false, querySelector() { return this.modal ? {} : null; }, addEventListener() {}, removeEventListener() {} };
const reduced = { matches: false, addEventListener() {}, removeEventListener() {} };
const imports = { vue: { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn) }, '@lucide/vue': {},
  '@/stores/auth': { useAuthStore: () => actor }, '@/stores/ai-assistant': { useAiAssistantStore: () => assistant }, '@/utils/assistant-placement': placementModule.exports,
  '@/utils/assistant-welcome': welcomeModule };
const module = { exports: {} };
new Function('require', 'module', 'exports', 'document', 'window', 'MutationObserver', 'localStorage', 'setInterval', 'clearInterval', 'Element', 'setTimeout', 'clearTimeout',
  ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(
  id => imports[id], module, module.exports, document, { matchMedia: () => reduced }, class { observe() {} disconnect() {} },
  { getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value) }, fn => { tick = fn; return 1; }, () => { tick = undefined; },
  Element, () => { placementRequests++; return 1; }, () => {});
const scope = vue.effectScope();
const state = scope.run(() => module.exports.default.setup({}, { expose() {} }));
mounted.forEach(fn => fn());
assert.equal(state.welcomeVisible.value, true, 'a fresh page visit welcomes even an actor with the old 24-hour suppression entry');
state.welcomeVisible.value = false; state.offerWelcome();
assert.equal(state.welcomeVisible.value, false, 'dismissed greeting does not return on repeated offers');
placementRequests = 0;
state.contentChanged([{ target: new Element(true) }]);
state.contentScrolled({ target: new Element(true) });
assert.equal(placementRequests, 0, 'topology-internal movement cannot continuously relocate the orb');
state.contentChanged([{ target: new Element(false) }]);
assert.equal(placementRequests, 1, 'new page controls still trigger obstacle avoidance');
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
assert.equal(state.welcomeVisible.value, true, 'a new experience receives its own greeting');
actor.user = { userId: 1 }; await vue.nextTick(); assert.equal(state.motion.value, false); assert.equal(assistant.minimized, true);
assert.equal(state.welcomeVisible.value, false, 'returning to an existing experience does not repeat the greeting');
state.minimize(false);
const drag = { button: 0, pointerId: 1, clientX: 1238, clientY: 594, currentTarget: { setPointerCapture() {} }, preventDefault() {} };
state.dragStart(drag); state.dragMove({ ...drag, clientX: 1240 }); assert.equal(state.dragging.value, false, 'pointer jitter remains a click');
state.dragMove({ ...drag, clientX: -500, clientY: -500 }); assert.equal(state.dragging.value, true); state.dragEnd(drag); state.openAssistant(); assert.equal(assistant.open, false, 'a drag cannot accidentally submit/open chat');
assert.equal(state.preferredPosition.value.left, 12); assert.equal(state.preferredPosition.value.top, 36, 'drag clamps inside viewport');
actor.user = { userId: 2 }; await vue.nextTick(); assert.equal(state.position.value, undefined);
actor.user = { userId: 1 }; await vue.nextTick(); assert.equal(state.preferredPosition.value.left, 12, 'drag position survives account switching');
state.resetPosition(); assert.equal(state.position.value, undefined); state.openAssistant(); assert.equal(assistant.open, true, 'a subsequent deliberate click opens the assistant');
unmount.forEach(fn => fn()); scope.stop(); assert.equal(tick, undefined);
assistant.open = false;
const nextMounted = mounted.length, nextUnmount = unmount.length;
const remountScope = vue.effectScope();
const remounted = remountScope.run(() => module.exports.default.setup({}, { expose() {} }));
mounted.slice(nextMounted).forEach(fn => fn());
assert.equal(remounted.welcomeVisible.value, false, 'returning from chat or an approval modal does not repeat the greeting');
actor.identity = 'login-2'; await vue.nextTick();
assert.equal(remounted.welcomeVisible.value, true, 'logging in again greets the same actor within the same page runtime');
remounted.welcomeVisible.value = false;
remounted.offerWelcome();
assert.equal(remounted.welcomeVisible.value, false, 'dismissal is retained throughout this login');
stored.set('opsagent-assistant-appearance:3', JSON.stringify({ minimized: true, motion: false }));
actor.user = { userId: 3 }; await vue.nextTick();
assert.equal(assistant.minimized, true, 'a new page entry keeps the saved minimized preference');
assert.equal(remounted.welcomeVisible.value, false, 'welcome does not override a minimized orb');
remounted.minimize(false);
assert.equal(remounted.welcomeVisible.value, true, 'explicitly restoring the orb offers a greeting that has not been shown yet');
assert.equal(remounted.motion.value, false, 'restoring the orb keeps the saved motion preference');
assistant.open = true; actor.user = { userId: 4 }; await vue.nextTick();
assert.equal(remounted.welcomeVisible.value, false, 'an already open assistant does not consume an invisible greeting');
assistant.open = false; await vue.nextTick();
assert.equal(remounted.welcomeVisible.value, true, 'a deferred greeting is offered when the assistant closes');
unmount.slice(nextUnmount).forEach(fn => fn()); remountScope.stop();
const freshVisit = freshWelcomeModule();
assert.equal(freshVisit.claimAssistantWelcome(1, 'login-1'), true, 'a new page runtime greets a returning login again');
assert.equal(freshVisit.claimAssistantWelcome(1, 'login-1'), false, 'one page runtime only greets once');
assert.equal(freshVisit.claimAssistantWelcome(undefined, null), false, 'an anonymous loading state is not welcomed');
console.log('PASS assistant: per-visit greeting, remount/relogin/deferred welcome, quiet motion, drag persistence, viewport bounds, reset and node avoidance');
