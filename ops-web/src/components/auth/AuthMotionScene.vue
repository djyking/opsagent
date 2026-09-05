<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Activity, ArrowUpRight, BookOpen, Check, FileCheck2, GitBranch, Pause, Play, Radio, ShieldCheck, Sparkles, TicketCheck } from "@lucide/vue";
import { useReducedMotion } from "@/composables/useReducedMotion";

const props = defineProps<{ formFocused: boolean }>();
const reduced = useReducedMotion();
const compactQuery = window.matchMedia('(max-width: 900px)');
const compact = ref(compactQuery.matches);
const hidden = ref(document.hidden);
const paused = ref(false);
const activeStep = ref(0);
const playing = computed(() => !paused.value && !props.formFocused && !reduced.value && !hidden.value && !compact.value);
const steps = [
  { label: '告警联动', detail: '让需要关注的信号，进入清晰的处理流程。', icon: Radio },
  { label: '工单协作', detail: '关联服务与责任人，让每个问题有人跟进。', icon: TicketCheck },
  { label: '知识依据', detail: '从知识中找到线索，在建议旁核对引用。', icon: BookOpen },
  { label: '处置留痕', detail: '记录诊断、执行与验证，让经验可以复用。', icon: FileCheck2 },
];
let timer: ReturnType<typeof setTimeout> | undefined;
const stepDuration = 2200;
let remaining = stepDuration;
let startedAt = 0;
watch([playing, activeStep], ([isPlaying, step], previous) => {
  clearTimeout(timer);
  if (previous?.[1] !== step) remaining = stepDuration;
  else if (previous?.[0]) remaining = Math.max(0, remaining - (performance.now() - startedAt));
  if (isPlaying) {
    startedAt = performance.now();
    timer = setTimeout(() => { activeStep.value = (activeStep.value + 1) % steps.length; }, remaining);
  }
}, { immediate: true });
function selectStep(index: number) { activeStep.value = index; paused.value = true; }
function toggle() { paused.value = !paused.value; }
function updateVisibility() { hidden.value = document.hidden; }
function updateCompact() { compact.value = compactQuery.matches; }
onMounted(() => { document.addEventListener('visibilitychange', updateVisibility); compactQuery.addEventListener('change', updateCompact); });
onBeforeUnmount(() => { clearTimeout(timer); document.removeEventListener('visibilitychange', updateVisibility); compactQuery.removeEventListener('change', updateCompact); });
</script>

<template>
  <section class="auth-motion-scene" :data-playing="playing" :data-step="activeStep" :data-reduced="reduced" aria-label="OpsAgent 产品介绍">
    <div class="login-scene-brand"><span><Activity :size="25" :stroke-width="1.8" /></span><strong>OpsAgent</strong><small>智能运维工作台</small></div>
    <div class="login-scene-main">
      <div class="login-scene-copy">
        <span class="login-scene-kicker"><Sparkles :size="14" />让协作发生，让经验流动</span>
        <h1>让运维更从容。<br /><em>让每一步都有依据。</em></h1>
        <p>从告警到协作，从知识到行动。<br />把复杂的现场，梳理成清晰的下一步。</p>
      </div>
      <div class="login-scene-canvas" aria-hidden="true">
        <div class="scene-grid" />
        <svg class="scene-connections" viewBox="0 0 640 350" preserveAspectRatio="none" fill="none">
          <path d="M170 84 C260 84 233 175 320 175" :class="['scene-wire', { active: activeStep === 0 }]" />
          <path d="M320 175 C407 175 380 84 470 84" :class="['scene-wire', { active: activeStep === 1 }]" />
          <path d="M170 265 C260 265 233 175 320 175" :class="['scene-wire', { active: activeStep === 2 }]" />
          <path d="M320 175 C407 175 380 265 470 265" :class="['scene-wire', { active: activeStep === 3 }]" />
          <path d="M170 84 C260 84 233 175 320 175" :class="['scene-signal', { active: activeStep === 0 }]" />
          <path d="M320 175 C407 175 380 84 470 84" :class="['scene-signal', { active: activeStep === 1 }]" />
          <path d="M170 265 C260 265 233 175 320 175" :class="['scene-signal', { active: activeStep === 2 }]" />
          <path d="M320 175 C407 175 380 265 470 265" :class="['scene-signal', { active: activeStep === 3 }]" />
        </svg>
        <div class="scene-core"><div class="scene-core-halo" /><span><Activity :size="34" :stroke-width="1.7" /></span><strong>OpsAgent</strong><small>连接每一步</small></div>
        <div class="scene-node scene-node-alert" :class="{ active: activeStep === 0 }"><div class="scene-node-heading"><span class="scene-node-icon"><Radio :size="18" /></span><strong>告警联动</strong><ArrowUpRight :size="15" /></div><p>让问题被看见</p><div class="scene-node-bottom"><span class="scene-status-dot" />关联事件与服务</div></div>
        <div class="scene-node scene-node-ticket" :class="{ active: activeStep === 1 }"><div class="scene-node-heading"><span class="scene-node-icon"><TicketCheck :size="18" /></span><strong>工单协作</strong><GitBranch :size="15" /></div><p>让处理有方向</p><div class="scene-node-bottom"><span class="scene-avatars"><i>协</i><i>作</i></span>明确责任与进度</div></div>
        <div class="scene-node scene-node-knowledge" :class="{ active: activeStep === 2 }"><div class="scene-node-heading"><span class="scene-node-icon"><BookOpen :size="18" /></span><strong>知识依据</strong><Sparkles :size="15" /></div><p>让回答有来源</p><div class="scene-node-bottom"><span class="scene-source-mark">[1]</span>建议与引用，一起呈现</div></div>
        <div class="scene-node scene-node-trace" :class="{ active: activeStep === 3 }"><div class="scene-node-heading"><span class="scene-node-icon"><FileCheck2 :size="18" /></span><strong>处置留痕</strong><ShieldCheck :size="15" /></div><p>让经验被留下</p><div class="scene-node-bottom"><Check :size="14" />诊断 · 执行 · 验证</div></div>
        <span class="scene-demo-label">产品流程示意</span>
      </div>
      <div class="login-scene-story">
        <div class="login-scene-steps" aria-label="选择流程示意"><button v-for="(step, index) in steps" :key="step.label" type="button" :aria-pressed="activeStep === index" :aria-label="`查看${step.label}示意`" @click="selectStep(index)"><span>{{ String(index + 1).padStart(2, '0') }}</span>{{ step.label }}<i :class="{ current: activeStep === index }" /></button></div>
        <p class="login-scene-detail">{{ steps[activeStep]!.detail }}</p>
      </div>
      <div class="login-scene-mobile-flow">告警联动<span>→</span>工单协作<span>→</span>知识与处置</div>
    </div>
    <footer class="login-scene-footer"><span><ShieldCheck :size="14" />连接运维现场，让处理过程清晰可见。</span><button v-if="!reduced" type="button" class="scene-playback" :aria-label="paused ? '播放流程演示' : '暂停流程演示'" @click="toggle"><Play v-if="paused" :size="14" /><Pause v-else :size="14" />{{ paused ? '播放演示' : formFocused ? '输入中 · 演示暂停' : '暂停演示' }}</button><span v-else class="scene-motion-note">已遵循减少动态效果设置</span></footer>
  </section>
</template>

<style scoped>
.auth-motion-scene { --scene-blue: var(--oa-primary); --scene-ink: #234366; --scene-muted: #667e97; position: relative; isolation: isolate; display: flex; flex-direction: column; padding: 36px clamp(36px, 5vw, 96px) 24px; min-width: 0; overflow: hidden; color: var(--scene-ink); background: radial-gradient(ellipse at 0 0, #deecff 0, transparent 55%), radial-gradient(ellipse at 95% 80%, #dcf3f2 0, transparent 42%), #f0f6fe; }
.auth-motion-scene::before { content: ''; position: absolute; z-index: -1; width: 650px; height: 650px; right: -350px; top: -290px; border: 1px solid #b7ccec50; border-radius: 50%; box-shadow: 0 0 0 60px #b7ccec0c, 0 0 0 120px #b7ccec0a; pointer-events: none; }
.login-scene-brand { display: flex; align-items: center; gap: 12px; }
.login-scene-brand > span { display: grid; place-items: center; width: 44px; height: 44px; color: var(--scene-blue); background: white; border: 1px solid #d9e5f7; border-radius: 13px; box-shadow: 0 5px 16px #527ba312; }
.login-scene-brand strong { font-size: 24px; letter-spacing: -.5px; font-weight: 650; }
.login-scene-brand small { margin-left: 4px; padding-left: 16px; border-left: 1px solid #c3d5e9; font-size: 12px; color: var(--scene-muted); }
.login-scene-main { width: 100%; max-width: 740px; margin: auto; padding: 38px 0 26px; }
.login-scene-kicker { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: #537798; margin-bottom: 18px; }
.login-scene-kicker svg { color: var(--scene-blue); }
.login-scene-copy h1 { margin: 0; font-size: clamp(34px, 2.9vw, 52px); font-weight: 650; line-height: 1.3; letter-spacing: -.025em; }
.login-scene-copy em { font-style: normal; color: var(--scene-blue); }
.login-scene-copy > p { margin: 18px 0 0; color: var(--scene-muted); font-size: 14px; line-height: 1.85; }
.login-scene-canvas { margin: 28px -10px 10px; position: relative; aspect-ratio: 640 / 350; min-width: 0; }
.scene-grid { position: absolute; inset: 0; background-image: radial-gradient(#82a4c746 .8px, transparent .8px); background-size: 18px 18px; mask-image: radial-gradient(ellipse, black 36%, transparent 73%); }
.scene-connections { position: absolute; inset: 0; width: 100%; height: 100%; overflow: visible; }
.scene-wire { stroke: #bfd0e5; stroke-width: 1.5; transition: stroke var(--oa-motion-panel); }
.scene-wire.active { stroke: #88a6ed; }
.scene-signal { stroke: var(--scene-blue); stroke-width: 2.5; stroke-linecap: round; stroke-dasharray: 9 240; opacity: 0; animation: scene-signal 2.2s linear infinite; }
.scene-signal.active { opacity: .85; }
@keyframes scene-signal { from { stroke-dashoffset: 30; } to { stroke-dashoffset: -220; } }
.scene-core { position: absolute; top: 50%; left: 50%; translate: -50% -50%; width: 116px; height: 116px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; color: white; border: 5px solid #f5f9ff; border-radius: 30px; background: linear-gradient(145deg, #6a92f5, var(--scene-blue)); box-shadow: 0 10px 28px #496ded24; }
.scene-core-halo { position: absolute; inset: -14px; border: 1px solid #7199e833; border-radius: 39px; animation: scene-breathe 4.4s ease-in-out infinite; }
@keyframes scene-breathe { 0%, 100% { transform: scale(.96); opacity: .45; } 50% { transform: scale(1.045); opacity: 1; } }
.scene-core > span { display: flex; }
.scene-core strong { font-size: 14px; font-weight: 600; }
.scene-core small { font-size: 10px; color: #e6edff; }
.scene-node { position: absolute; width: 31%; max-width: 226px; padding: 15px; background: #ffffffee; border: 1px solid #d4e1f0; border-radius: 15px; box-shadow: 0 8px 20px #375e8510; transition: transform .5s var(--oa-ease-emphasized), border-color .5s, box-shadow .5s; }
.scene-node.active { transform: translateY(-4px); border-color: #91acef; box-shadow: 0 12px 26px #466dba1d; }
.scene-node-alert { left: 1%; top: 5%; }.scene-node-ticket { right: 1%; top: 5%; }.scene-node-knowledge { left: 1%; bottom: 6%; }.scene-node-trace { right: 1%; bottom: 6%; }
.scene-node-heading { display: flex; align-items: center; gap: 8px; }
.scene-node-heading strong { font-size: 13px; font-weight: 600; color: #34506e; }
.scene-node-heading > svg { margin-left: auto; color: #8aa1b9; flex-shrink: 0; }
.scene-node-icon { display: grid; place-items: center; width: 30px; height: 30px; color: var(--scene-blue); border-radius: 9px; background: #edf2ff; flex-shrink: 0; }
.scene-node-knowledge .scene-node-icon { background: #e9f5f0; color: #388c77; }.scene-node-trace .scene-node-icon { background: #eef0fc; color: #8170bc; }
.scene-node > p { font-size: 12px; margin: 9px 0 12px; color: var(--scene-muted); }
.scene-node-bottom { display: flex; align-items: center; gap: 6px; border-top: 1px solid #edf1f7; padding-top: 10px; font-size: 10px; white-space: nowrap; color: #698098; }
.scene-status-dot { width: 5px; height: 5px; border-radius: 50%; background: #6b8cee; box-shadow: 0 0 0 3px #edf2ff; }
.scene-avatars { display: inline-flex; padding-left: 4px; }.scene-avatars i { display: grid; place-items: center; width: 18px; height: 18px; margin-left: -4px; border: 2px solid white; border-radius: 50%; background: #d9e8ff; color: #5b7faa; font-size: 8px; font-style: normal; }.scene-avatars i + i { background: #ddf0e9; color: #6b998b; }
.scene-source-mark { color: var(--scene-blue); font-weight: 600; }
.scene-demo-label { position: absolute; bottom: 0; left: 50%; translate: -50% 0; font-size: 10px; letter-spacing: .06em; color: #8ba0b5; }
.login-scene-story { margin-top: 12px; }
.login-scene-steps { display: grid; grid-template-columns: repeat(4, 1fr); gap: 15px; }
.login-scene-steps button { position: relative; padding: 12px 0; display: flex; align-items: center; gap: 7px; border: 0; background: transparent; color: #71879d; font-size: 12px; text-align: left; cursor: pointer; }
.login-scene-steps button span { font-family: var(--oa-font-mono); font-size: 10px; color: #97aabd; }
.login-scene-steps button[aria-pressed="true"] { color: var(--scene-blue); font-weight: 600; }
.login-scene-steps i { position: absolute; bottom: 0; left: 0; right: 0; height: 2px; border-radius: 2px; background: #dce6f2; overflow: hidden; }
.login-scene-steps i.current::after { content: ''; position: absolute; inset: 0; background: var(--scene-blue); transform-origin: left; animation: scene-progress 2.2s linear both; }
@keyframes scene-progress { from { transform: scaleX(0); } to { transform: scaleX(1); } }
.login-scene-detail { min-height: 20px; margin: 14px 0 0; font-size: 12px; line-height: 1.7; color: #71879d; }
.login-scene-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #8398ad; font-size: 11px; }
.login-scene-footer > span { display: inline-flex; gap: 6px; align-items: center; }
.scene-playback { display: inline-flex; align-items: center; gap: 5px; padding: 7px 9px; border: 1px solid #cdddf0; border-radius: 8px; background: #ffffff70; color: #6984a0; cursor: pointer; font-size: 11px; white-space: nowrap; }
.scene-playback:hover { background: white; color: var(--scene-blue); }
.login-scene-mobile-flow { display: none; }
.auth-motion-scene[data-playing="false"] *, .auth-motion-scene[data-playing="false"] *::before, .auth-motion-scene[data-playing="false"] *::after { animation-play-state: paused !important; }
.auth-motion-scene[data-reduced="true"] *, .auth-motion-scene[data-reduced="true"] *::before, .auth-motion-scene[data-reduced="true"] *::after { animation: none !important; transition: none !important; }
@media (max-width: 1200px) and (min-width: 901px) { .auth-motion-scene { padding: 28px 28px 20px; }.login-scene-brand small { display: none; }.login-scene-canvas { min-height: 310px; }.scene-node { padding: 10px; }.scene-node-heading { gap: 5px; }.scene-node-heading > svg { display: none; }.scene-node-bottom { font-size: 9px; }.scene-node-icon { width: 25px; height: 25px; }.scene-core { width: 94px; height: 94px; border-radius: 26px; }.scene-core-halo { border-radius: 34px; }.login-scene-steps { gap: 9px; }.login-scene-footer > span { max-width: 180px; font-size: 10px; } }
@media (max-height: 950px) and (min-width: 901px) { .login-scene-main { padding: 24px 0 18px; }.login-scene-copy h1 { font-size: 36px; }.login-scene-kicker { margin-bottom: 12px; }.login-scene-copy > p { margin-top: 12px; }.login-scene-canvas { max-width: 620px; margin: 18px auto 8px; }.login-scene-story { margin-top: 6px; } }
@media (max-width: 900px) { .auth-motion-scene { padding: 22px 28px 20px; border-bottom: 1px solid #dfe9f5; }.login-scene-brand strong { font-size: 20px; }.login-scene-brand > span { width: 34px; height: 34px; border-radius: 10px; }.login-scene-brand small { margin-left: auto; border: 0; }.login-scene-main { max-width: none; padding: 18px 0 0; margin: 0; }.login-scene-copy h1 { font-size: 26px; }.login-scene-copy br, .login-scene-copy em, .login-scene-copy p, .login-scene-kicker, .login-scene-canvas, .login-scene-story, .login-scene-footer { display: none; }.login-scene-mobile-flow { display: flex; gap: 10px; margin-top: 10px; font-size: 11px; color: #71879d; }.login-scene-mobile-flow span { color: #a6b8cb; } }
@media (prefers-reduced-motion: reduce) { .auth-motion-scene *, .auth-motion-scene *::before, .auth-motion-scene *::after { animation: none !important; transition: none !important; } }
</style>
