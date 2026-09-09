<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Bot, ChevronRight, Pause, Play, RotateCcw, X } from '@lucide/vue';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { useAuthStore } from '@/stores/auth';
import { assistantPlacement } from '@/utils/assistant-placement';
const assistant = useAiAssistantStore();
const auth = useAuthStore();
const motion = ref(true), jumping = ref(false), hovering = ref(false);
const position = ref<{ x: number; y: number }>();
const viewport = ref({ width: 1366, height: 768 });
const dragging = ref(false);
const welcomeVisible = ref(false);
const welcomedActors = new Set<number>();
const welcomeKey = 'opsagent-assistant-welcome:v2';
const safePosition = ref<{ left: number; top: number }>();
let placementTimer: ReturnType<typeof setTimeout> | undefined;
let mounted = false;
let pointer: { id: number; x: number; y: number; left: number; top: number } | undefined;
let suppressClick = false;
const preferredPosition = computed(() => {
  const width = assistant.minimized ? 28 : viewport.value.width <= 640 ? 48 : 64;
  const height = assistant.minimized ? 38 : viewport.value.width <= 640 ? 60 : 78;
  const left = position.value ? position.value.x * Math.max(1, viewport.value.width - width) : viewport.value.width - width - 64;
  const top = position.value ? position.value.y * Math.max(1, viewport.value.height - height) : viewport.value.height - height - 96;
  return { left: Math.max(12, Math.min(viewport.value.width - width - 12, left)), top: Math.max(36, Math.min(viewport.value.height - height - 20, top)), width, height };
});
const orbStyle = computed(() => { const point = dragging.value ? preferredPosition.value : safePosition.value || preferredPosition.value; return { left: `${point.left}px`, top: `${point.top}px`, right: 'auto', bottom: 'auto' }; });
const welcomeStyle = computed(() => ({ left: `${Math.max(12, Math.min(viewport.value.width - 312, Number.parseFloat(orbStyle.value.left) - 312))}px`, top: `${Math.max(76, Math.min(viewport.value.height - 210, Number.parseFloat(orbStyle.value.top) - 100))}px` }));
function offerWelcome() {
  const actor = auth.user?.userId;
  if (!mounted || !actor || welcomedActors.has(actor) || assistant.minimized) return;
  try {
    const previous = JSON.parse(localStorage.getItem(welcomeKey) || '{}');
    if (Number(previous[actor]) > Date.now()) return;
    const active = Object.fromEntries(Object.entries(previous).filter(([, until]) => Number(until) > Date.now()));
    localStorage.setItem(welcomeKey, JSON.stringify({ ...active, [actor]: Date.now() + 24 * 60 * 60 * 1000 }));
  } catch { /* The in-memory identity guard also works when storage is unavailable. */ }
  welcomedActors.add(actor); welcomeVisible.value = true;
}
function avoidContent() {
  if (dragging.value || pointer || assistant.open || document.hidden) return;
  const rects = Array.from(document.querySelectorAll?.<HTMLElement>('button,a[href],input,select,textarea,.obs-inline-drawer,.managed-file-heading,.managed-file-actions,.event-action-bar') || [])
    .filter(element => !element.closest('.ai-orb-position,.ai-welcome-card,.obs-topology-surface') && element.getClientRects().length)
    .map(element => element.getBoundingClientRect()).filter(rect => rect.width > 0 && rect.height > 0 && rect.bottom > 0 && rect.top < viewport.value.height);
  const point = assistantPlacement(preferredPosition.value, preferredPosition.value, viewport.value, rects);
  if (safePosition.value?.left !== point.left || safePosition.value?.top !== point.top) safePosition.value = point;
}
function schedulePlacement() { if (!mounted) return; clearTimeout(placementTimer); placementTimer = setTimeout(avoidContent, 90); }
function resizeViewport() { viewport.value = { width: window.innerWidth || 1366, height: window.innerHeight || 768 }; safePosition.value = undefined; schedulePlacement(); }
function resetPosition() { position.value = undefined; savePreference(); }
function dragStart(event: PointerEvent) {
  if (event.button !== 0) return;
  pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, left: Number.parseFloat(orbStyle.value.left), top: Number.parseFloat(orbStyle.value.top) };
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId); jumping.value = false; suppressClick = false;
}
function dragMove(event: PointerEvent) {
  if (!pointer || event.pointerId !== pointer.id) return;
  const dx = event.clientX - pointer.x, dy = event.clientY - pointer.y;
  if (!dragging.value && Math.hypot(dx, dy) < 6) return;
  dragging.value = true; suppressClick = true; event.preventDefault();
  const width = viewport.value.width <= 640 ? 48 : 64, height = viewport.value.width <= 640 ? 60 : 78;
  position.value = { x: Math.max(0, Math.min(1, (pointer.left + dx) / Math.max(1, viewport.value.width - width))), y: Math.max(0, Math.min(1, (pointer.top + dy) / Math.max(1, viewport.value.height - height))) };
}
function dragEnd(event: PointerEvent) { if (!pointer || pointer.id !== event.pointerId) return; if (dragging.value) { savePreference(); safePosition.value = { left: preferredPosition.value.left, top: preferredPosition.value.top }; } dragging.value = false; pointer = undefined; schedulePlacement(); }
function openAssistant() { if (suppressClick) { suppressClick = false; return; } welcomeVisible.value = false; assistant.show(); }
let timer: ReturnType<typeof setInterval> | undefined;
let observer: MutationObserver | undefined;
let reduced: MediaQueryList | undefined;
const key = () => `opsagent-assistant-appearance:${auth.user?.userId ?? 'none'}`;
function loadPreference() {
  position.value = undefined; safePosition.value = undefined; pointer = undefined; dragging.value = false; welcomeVisible.value = false;
  try { const saved = JSON.parse(localStorage.getItem(key()) || '{}'); motion.value = saved.motion !== false; assistant.minimized = saved.minimized === true; if (Number.isFinite(saved.position?.x) && Number.isFinite(saved.position?.y)) position.value = saved.position; }
  catch { motion.value = true; assistant.minimized = false; }
  offerWelcome();
}
function savePreference() {
  try { localStorage.setItem(key(), JSON.stringify({ motion: motion.value, minimized: assistant.minimized, position: position.value })); } catch { /* Session preference still applies. */ }
}
function minimize(value: boolean) { assistant.minimized = value; jumping.value = false; welcomeVisible.value = false; savePreference(); }
function toggleMotion() { motion.value = !motion.value; jumping.value = false; savePreference(); }
function blocked() {
  return !motion.value || dragging.value || reduced?.matches || document.hidden || hovering.value || assistant.open || assistant.minimized || assistant.busy
    || !!document.activeElement?.closest('.ai-orb-position')
    || document.activeElement?.matches('input,textarea,select,[contenteditable="true"]')
    || !!document.querySelector('[aria-modal="true"],dialog[open],.el-overlay-dialog');
}
function stopWhenBlocked() { if (blocked()) jumping.value = false; }
watch(() => auth.user?.userId, loadPreference);
watch(preferredPosition, schedulePlacement);
watch(() => assistant.open, () => { jumping.value = false; });
function contentChanged(records: MutationRecord[]) {
  stopWhenBlocked();
  // G6 updates HTML node styles/classes throughout zooming, dragging and refreshes.
  // Graph-internal motion is not a new page obstruction and must not relocate the orb.
  if (records.some(record => !(record.target instanceof Element) || !record.target.closest('.ai-orb-position,.ai-welcome-card,.obs-topology-surface'))) schedulePlacement();
}
function contentScrolled(event: Event) { if (!(event.target instanceof Element) || !event.target.closest('.obs-topology-surface')) schedulePlacement(); }
onMounted(() => {
  mounted = true;
  resizeViewport(); window.addEventListener?.('resize', resizeViewport); loadPreference(); reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  reduced.addEventListener('change', stopWhenBlocked);
  document.addEventListener('visibilitychange', stopWhenBlocked); document.addEventListener('focusin', stopWhenBlocked);
  observer = new MutationObserver(contentChanged);
  observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-modal', 'open', 'style', 'class'] });
  window.addEventListener?.('scroll', contentScrolled, true);
  timer = setInterval(() => { jumping.value = !blocked(); }, 12000);
});
onBeforeUnmount(() => {
  mounted = false;
  if (timer) clearInterval(timer);
  clearTimeout(placementTimer);
  observer?.disconnect(); reduced?.removeEventListener('change', stopWhenBlocked);
  window.removeEventListener?.('resize', resizeViewport);
  window.removeEventListener?.('scroll', contentScrolled, true);
  document.removeEventListener('visibilitychange', stopWhenBlocked); document.removeEventListener('focusin', stopWhenBlocked);
});
</script>
<template>
  <section v-if="welcomeVisible && !assistant.open && !assistant.minimized" class="ai-welcome-card" :style="welcomeStyle" aria-label="OpsAgent 欢迎指引">
    <button type="button" class="ai-welcome-close" aria-label="关闭欢迎指引" @click="welcomeVisible = false"><X :size="15" /></button>
    <strong>你好，我是 OpsAgent 助手</strong><p>可以问我系统问题，也可以先看一个处置案例，或体验隔离演练。</p>
    <div><RouterLink to="/automation?tab=cases" @click="welcomeVisible = false">查看公共案例</RouterLink><RouterLink to="/automation?tab=experience" @click="welcomeVisible = false">选择演练 →</RouterLink></div>
  </section>
  <aside v-if="!assistant.open" class="ai-orb-position" :style="orbStyle" :class="{ minimized: assistant.minimized, dragging }" aria-label="AI 助手入口，可拖动调整位置" @mouseenter="hovering = true; jumping = false" @mouseleave="hovering = false" @focusin="jumping = false">
    <button v-if="assistant.minimized" class="ai-orb-restore" type="button" aria-label="展开 AI 悬浮入口" @click="minimize(false)"><Bot :size="18" /></button>
    <template v-else>
      <button class="ai-orb" :class="{ jumping }" type="button" aria-label="问问 OpsAgent AI，可拖动调整位置" aria-haspopup="dialog" :aria-expanded="assistant.open" title="点击提问 · 拖动调整位置" @pointerdown="dragStart" @pointermove="dragMove" @pointerup="dragEnd" @pointercancel="dragEnd" @click="openAssistant" @animationend="jumping = false">
        <svg viewBox="0 0 76 84" aria-hidden="true"><ellipse cx="38" cy="78" rx="22" ry="4" fill="#d8e5fa"/><path d="M38 16V8m-3 0h6" stroke="#4383f6" stroke-width="3" stroke-linecap="round"/><circle cx="38" cy="5" r="3" fill="#4383f6"/><path d="M24 54c-5 5-6 13-5 20 11 6 27 6 38 0 1-7 0-15-5-20" fill="#f6faff" stroke="#b4cefa" stroke-width="2"/><rect x="5" y="27" width="7" height="19" rx="3.5" fill="#d7e6ff" stroke="#87aff7"/><rect x="64" y="27" width="7" height="19" rx="3.5" fill="#d7e6ff" stroke="#87aff7"/><rect x="11" y="17" width="54" height="41" rx="18" fill="#fff" stroke="#b4cefa" stroke-width="2"/><rect x="18" y="24" width="40" height="27" rx="12" fill="#2364ed"/><ellipse cx="29" cy="36" rx="4" ry="5" fill="#acf0ff"/><ellipse cx="47" cy="36" rx="4" ry="5" fill="#acf0ff"/><path d="M34 44q4 3 8 0" fill="none" stroke="#acf0ff" stroke-width="2" stroke-linecap="round"/><path d="m30 66 4-4 4 6 4-6 4 4" fill="none" stroke="#4383f6" stroke-width="2" stroke-linecap="round"/></svg>
        <span v-if="assistant.busy" class="ai-orb-status" aria-label="回答生成中" />
      </button>
      <div class="ai-orb-controls"><button type="button" aria-label="恢复 AI 入口默认位置" title="恢复默认位置" @click="resetPosition"><RotateCcw :size="11" /></button><button type="button" :aria-label="motion ? '关闭小精灵动效' : '开启小精灵动效'" :title="motion ? '关闭动效' : '开启动效'" :aria-pressed="motion" @click="toggleMotion"><component :is="motion ? Pause : Play" :size="11" /></button><button type="button" aria-label="收起悬浮入口，仍可从顶部打开 AI" title="收起悬浮入口" @click="minimize(true)"><ChevronRight :size="12" /></button></div>
    </template>
  </aside>
</template>
<style scoped>
.ai-welcome-card { position:fixed; z-index:39; width:300px; max-width:calc(100vw - 24px); box-sizing:border-box; padding:18px; border:1px solid #d8e6fb; border-radius:14px; background:#fff; color:#35547e; box-shadow:0 8px 28px #254d8520; font-size:13px; line-height:1.6; }
.ai-welcome-card strong { display:block; padding-right:15px; color:#244872; font-size:14px; }.ai-welcome-card p { margin:9px 0 13px; color:#6b809b; }.ai-welcome-card > div { display:flex; gap:18px; flex-wrap:wrap; }.ai-welcome-card a { color:#286bdb; font-weight:500; }.ai-welcome-close { position:absolute; right:8px; top:8px; display:grid; place-items:center; width:24px; height:24px; border:0; border-radius:6px; background:transparent; color:#7187a4; cursor:pointer; }
.ai-orb-position { position:fixed; right:20px; bottom:max(16px, env(safe-area-inset-bottom)); z-index:39; width:64px; height:78px; }
.ai-orb { display:grid; place-items:center; width:64px; height:74px; padding:0; border:0; background:transparent; cursor:pointer; filter:drop-shadow(0 3px 6px #4c7ec226); }
.ai-orb { touch-action:none; user-select:none; }.dragging .ai-orb { cursor:grabbing; }.ai-orb svg { width:64px; height:74px; }
.ai-orb-status { position:absolute; right:3px; top:15px; width:7px; height:7px; background:#2474fa; border:2px solid white; border-radius:50%; }
.ai-orb-controls { position:absolute; top:-9px; right:0; display:flex; gap:3px; }
.ai-orb-controls button { display:grid; place-items:center; width:19px; height:19px; padding:0; border:1px solid var(--oa-border-default); border-radius:50%; background:var(--oa-bg-surface); color:var(--oa-text-muted); cursor:pointer; }
.ai-orb-position.minimized { right:0; width:28px; height:38px; }
.ai-orb-restore { width:28px; height:38px; border:1px solid var(--oa-border-default); border-radius:10px 0 0 10px; background:var(--oa-bg-surface); color:var(--oa-primary); cursor:pointer; }
.jumping { animation:assistant-hop 700ms ease-in-out; }
@keyframes assistant-hop { 0%,100% { transform:translateY(0); } 40% { transform:translateY(-6px); } 70% { transform:translateY(0); } 85% { transform:translateY(-2px); } }
button:focus-visible { outline:2px solid var(--oa-primary); outline-offset:3px; border-radius:12px; }
@media(prefers-reduced-motion:reduce) { .jumping { animation:none; } }
@media(max-width:640px) { .ai-orb-position { right:12px; width:48px; height:60px; bottom:max(12px, env(safe-area-inset-bottom)); }.ai-orb,.ai-orb svg { width:48px; height:56px; }.ai-orb-position.minimized { width:28px; height:38px; } }
</style>
