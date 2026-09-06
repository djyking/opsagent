<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { Bot, ChevronRight, CircleHelp, X } from '@lucide/vue';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { useAuthStore } from '@/stores/auth';
const assistant = useAiAssistantStore(); const auth = useAuthStore();
const introduction = ref(false); const motion = ref(true);
const guideKey = computed(() => `opsagent-ai-guide:${auth.user?.userId || 'anonymous'}:v3`);
const motionKey = computed(() => `opsagent-ai-motion:${auth.user?.userId || 'anonymous'}`);
function stored(key: string) { try { return localStorage.getItem(key); } catch { return null; } }
function save(key: string, value: string) { try { localStorage.setItem(key, value); } catch { /* A private browser may disable storage. */ } }
async function introduce() {
  const actor = auth.user?.userId; introduction.value = false; motion.value = stored(motionKey.value) !== 'off';
  await nextTick();
  if (!actor || actor !== auth.user?.userId || assistant.open || stored(guideKey.value)) return;
  introduction.value = true; save(guideKey.value, 'seen');
}
function dismiss() { introduction.value = false; save(guideKey.value, 'seen'); }
function begin() { dismiss(); assistant.show(); }
function toggleMotion() { motion.value = !motion.value; save(motionKey.value, motion.value ? 'on' : 'off'); }
watch(() => auth.user?.userId, introduce);
watch(() => assistant.open, value => { if (value) dismiss(); });
onMounted(introduce);
</script>
<template>
  <aside v-if="!assistant.open" class="ai-orb-position" :class="{ minimized: assistant.minimized, 'has-motion': motion }" aria-label="AI 助手入口">
    <section v-if="introduction && !assistant.minimized" class="ai-orb-introduction" aria-label="认识 AI 助手">
      <button type="button" class="ai-guide-close" aria-label="关闭 AI 使用提示" @click="dismiss"><X :size="16" /></button>
      <strong><Bot :size="19" />我是 OpsAgent AI 助手</strong><p>可以结合当前服务或事件帮助分析。发送前，你可以确认或移除上下文。</p>
      <div><button type="button" class="button primary compact" @click="begin">开始使用</button><button type="button" class="text-button" :aria-pressed="motion" @click="toggleMotion">{{ motion ? '关闭动效' : '开启动效' }}</button></div>
    </section>
    <button v-if="assistant.minimized" class="ai-orb-restore" type="button" aria-label="展开 AI 悬浮入口" @click="assistant.minimized = false"><Bot :size="17" /><span>AI</span></button>
    <template v-else>
      <button class="ai-orb" type="button" aria-label="问问 OpsAgent AI" aria-haspopup="dialog" :aria-expanded="assistant.open" title="问问 OpsAgent AI" @click="begin"><Bot class="ai-orb-face" :size="24" :stroke-width="1.6" /><span>AI 助手</span><i v-if="assistant.busy" class="ai-orb-status" aria-label="回答生成中" /></button>
      <button class="ai-orb-minimize" type="button" aria-label="收起悬浮入口，仍可从顶部打开 AI" title="收起悬浮入口" @click="dismiss(); assistant.minimized = true"><ChevronRight :size="12" /></button>
      <button class="ai-orb-help" type="button" aria-label="再次查看 AI 使用提示与动效设置" title="使用提示与动效设置" @click="introduction = !introduction"><CircleHelp :size="14" /></button>
    </template>
  </aside>
</template>
<style scoped>
.ai-orb-position { position: fixed; right: 22px; bottom: max(22px, env(safe-area-inset-bottom)); z-index: 39; width: 128px; height: 48px; }
.ai-orb { display: flex; align-items: center; justify-content: center; gap: 8px; width: 128px; height: 48px; border-radius: 17px; border: 1px solid var(--oa-primary); color: var(--oa-text-inverse, white); background: var(--oa-primary); box-shadow: 0 6px 24px color-mix(in srgb, var(--oa-primary) 22%, transparent); cursor: pointer; }
.ai-orb > span { font-size: var(--oa-font-size-sm); font-weight: 600; }
.ai-orb-status { position: absolute; left: 34px; top: 8px; width: 7px; height: 7px; background: var(--oa-success); border: 1px solid var(--oa-bg-surface); border-radius: 50%; }
.ai-orb-minimize, .ai-orb-help, .ai-guide-close { display: grid; place-items: center; width: 22px; height: 22px; border: 1px solid var(--oa-border-default); border-radius: 50%; background: var(--oa-bg-surface); color: var(--oa-text-muted); cursor: pointer; }
.ai-orb-minimize { position: absolute; top: -7px; right: -6px; }
.ai-orb-help { position: absolute; bottom: -6px; right: -6px; }
.ai-orb-position.minimized { right: 0; width: 52px; height: 38px; }
.ai-orb-restore { display: flex; gap: 4px; align-items: center; justify-content: center; width: 52px; height: 38px; border: 1px solid var(--oa-border-default); border-radius: 10px 0 0 10px; background: var(--oa-bg-surface); color: var(--oa-primary); cursor: pointer; }
.ai-orb-introduction { position: absolute; right: 0; bottom: 64px; width: min(310px, calc(100vw - 40px)); padding: 19px; border: 1px solid var(--oa-border-default); border-radius: var(--oa-radius-panel); background: var(--oa-bg-surface); box-shadow: 0 8px 30px color-mix(in srgb, var(--oa-primary) 14%, transparent); }
.ai-orb-introduction strong { display: flex; align-items: center; gap: 7px; padding-right: 12px; font-size: var(--oa-font-size-sm); color: var(--oa-text-primary); }
.ai-orb-introduction p { font-size: var(--oa-font-size-xs); color: var(--oa-text-secondary); line-height: 1.8; margin: 11px 0 15px; }
.ai-orb-introduction > div { display: flex; align-items: center; gap: 14px; }
.ai-guide-close { position: absolute; top: 7px; right: 7px; border: 0; }
.has-motion .ai-orb-face { animation: ai-orb-greeting 2.4s ease-in-out 2; }
@keyframes ai-orb-greeting { 0%, 65%, 100% { transform: translateY(0); } 30% { transform: translateY(-3px) rotate(-7deg); } 45% { transform: translateY(-3px) rotate(7deg); } }
button:focus-visible { outline: 2px solid var(--oa-primary); outline-offset: 3px; }
@media (prefers-reduced-motion: reduce) { .has-motion .ai-orb-face { animation: none; } }
@media (max-width: 640px) { .ai-orb-position { right: 14px; bottom: max(14px, env(safe-area-inset-bottom)); } }
</style>
