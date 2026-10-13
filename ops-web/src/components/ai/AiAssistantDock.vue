<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { storeToRefs } from 'pinia';
import { Bot, ChevronDown, Maximize2, MessageSquare, Plus, Send, X } from '@lucide/vue';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { ragAnswerLabel, ragCanRetry, ragTimingLabel } from '@/api/rag-stream';
import { assistantQuestionBody, assistantQuestionEvidence } from '@/utils/ai-context';
import AnswerContent from '@/components/AnswerContent.vue';
import RagSources from '@/components/RagSources.vue';
import RagBudgetEvidence from '@/components/RagBudgetEvidence.vue';
const assistant = useAiAssistantStore();
const { chatScroll, questionInput } = storeToRefs(assistant);
const route = useRoute(), router = useRouter();
const dock = ref<HTMLElement>();
const historyOpen = ref(false);
const visible = computed(() => assistant.open && route.path !== '/rag/chat');
const suggestions = computed(() => assistant.context.ticketId
  ? ['总结当前事件的现象与证据', '分析可能原因与待补充证据', '系统使用指南']
  : assistant.context.service ? ['分析当前服务健康状态', '推荐相关 Runbook', '系统使用指南']
    : ['系统使用指南', '功能介绍', '访客能做什么？']);
const showGlobalError = computed(() => assistant.error && !assistant.turns.some(turn => turn.errorMessage === assistant.error));
let previousFocus: HTMLElement | null = null;
watch(visible, async value => {
  if (value) { previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; await nextTick(); questionInput.value?.focus(); }
  else { historyOpen.value = false; if (previousFocus?.isConnected) { previousFocus.focus(); previousFocus = null; } }
});
function expand() { assistant.hide(); void router.push({ path: '/rag/chat', query: assistant.sessionId ? { conversation: assistant.sessionId } : {} }); }
function close() { assistant.hide(); }
function prompt(value: string) { assistant.question = value; void nextTick(() => questionInput.value?.focus()); }
function selectHistory(event: Event) { const value = (event.target as HTMLSelectElement).value; historyOpen.value = false; void (value ? assistant.selectSession(value) : assistant.newSession()); }
onBeforeUnmount(() => { if (previousFocus?.isConnected) previousFocus.focus(); });
</script>
<template>
  <section v-if="visible" ref="dock" class="ai-assistant-dock" role="dialog" aria-modal="false" aria-labelledby="ai-dock-title" @keydown.esc.stop="close">
    <header class="ai-dock-header">
      <span class="ai-dock-symbol"><Bot :size="21" /></span><div class="ai-dock-title"><h2 id="ai-dock-title">OpsAgent AI</h2><p>系统指南 · 问答与分析</p></div>
      <div class="ai-dock-tools"><button class="icon-button" aria-label="我的会话" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen"><MessageSquare :size="17" /></button><button class="icon-button" aria-label="新建助手会话" :disabled="assistant.busy" @click="assistant.newSession"><Plus :size="17" /></button><button class="icon-button" aria-label="在完整页面继续当前会话" @click="expand"><Maximize2 :size="16" /></button><button class="icon-button" aria-label="收起 AI 助手" @click="close"><X :size="18" /></button></div>
    </header>
    <div v-if="historyOpen" class="ai-dock-history"><label for="ai-dock-history-select">我的会话</label><select id="ai-dock-history-select" :value="assistant.sessionId" :disabled="assistant.busy || assistant.loading" aria-label="切换助手会话" @change="selectHistory"><option value="">新会话</option><option v-for="session in assistant.sessions" :key="session.id" :value="session.id">{{ session.title }}</option></select></div>
    <details class="ai-dock-context"><summary><span>上下文</span><strong>{{ assistant.contextSummary || '全局问答' }}</strong><ChevronDown :size="14" /></summary><p>服务或事件信息用于辅助分析。回答中的引用说明实际采用的依据，切换页面后仍可继续当前会话。</p></details>
    <div ref="chatScroll" class="ai-dock-messages" :aria-busy="assistant.busy || assistant.loading">
      <p v-if="assistant.loading" role="status">正在读取会话…</p>
      <button v-if="assistant.hasEarlier" class="button text" :disabled="assistant.busy || assistant.loading" @click="assistant.earlier">加载更早消息</button>
      <article v-for="turn in assistant.turns" :key="turn.id" class="ai-dock-turn">
        <p class="ai-dock-question">{{ assistantQuestionBody(turn.question) }}</p>
        <details v-if="assistantQuestionEvidence(turn.question)" class="ai-dock-evidence"><summary>提问上下文与现场快照</summary><pre>{{ assistantQuestionEvidence(turn.question) }}</pre></details>
        <div class="ai-dock-answer">
          <strong v-if="turn.status === 'PROCESSING' || (turn.status !== 'COMPLETE' && !turn.errorMessage)" class="ai-dock-answer-status" role="status">{{ assistant.turnLabel(turn) }}</strong>
          <AnswerContent v-if="turn.answer" :content="turn.answer" />
          <small v-if="turn.result" class="ai-dock-model">{{ ragAnswerLabel(turn.result) }} · {{ ragTimingLabel(turn.result) }}</small>
          <p v-if="turn.errorMessage && turn.errorMessage !== turn.answer" class="inline-error">{{ turn.errorMessage }}</p>
          <button v-if="turn.result && ragCanRetry(turn.result)" type="button" class="button secondary ai-dock-retry" :disabled="assistant.busy" @click="assistant.retry(turn)">重新提问</button>
          <details v-if="turn.result?.references.length" class="ai-dock-sources"><summary>{{ turn.result.references.length }} 条引用依据</summary><RagSources :references="turn.result.references" /></details>
          <RagBudgetEvidence v-if="turn.result" :result="turn.result" />
        </div>
      </article>
      <div v-if="!assistant.turns.length && !assistant.loading" class="ai-dock-welcome"><Bot :size="27" /><h3>有什么可以帮你？</h3><p>了解系统功能，或从当前服务与事件开始提问。</p><button v-for="item in suggestions" :key="item" :disabled="assistant.busy || assistant.loading" @click="prompt(item)">{{ item }}</button></div>
      <p v-if="assistant.historyError" class="inline-error">{{ assistant.historyError }} <button class="button text" @click="assistant.refreshHistory()">重试</button></p><p v-if="showGlobalError" class="inline-error" role="alert">{{ assistant.error }}</p>
    </div>
    <div class="ai-dock-composer-shell">
      <p v-if="assistant.providerError" class="ai-dock-provider-error">{{ assistant.providerError }} <button type="button" class="text-button" :disabled="assistant.providersLoading" @click="assistant.loadProviders">重试连接</button></p>
      <form class="ai-dock-composer" @submit.prevent="assistant.ask()">
        <textarea ref="questionInput" v-model="assistant.question" rows="3" maxlength="2000" placeholder="询问系统功能、服务或事件…" aria-label="AI 助手问题" @keydown.enter.exact.prevent="assistant.ask()" />
        <div class="ai-dock-composer-bar"><select v-model="assistant.selectedProvider" aria-label="助手回答模型" :disabled="assistant.busy || assistant.providersLoading || !assistant.providersReady"><option v-if="!assistant.selectedProvider" value="">{{ assistant.providersLoading ? '读取模型配置…' : '系统指南与知识检索' }}</option><option v-for="provider in assistant.providers" :key="provider.provider" :value="provider.provider" :disabled="!provider.available">{{ provider.model || provider.provider }}</option></select><select v-model="assistant.answerStyle" aria-label="回答详细程度" :disabled="assistant.busy"><option value="concise">精简回答</option><option value="detailed">深入分析</option></select><button class="button primary" :disabled="!assistant.canAsk()" aria-label="发送助手问题"><Send :size="16" />{{ assistant.busy ? '生成中' : '发送' }}</button></div>
      </form>
    </div>
  </section>
</template>
<style scoped>
.ai-assistant-dock { position:fixed; right:22px; bottom:max(22px,env(safe-area-inset-bottom)); z-index:39; width:min(460px,calc(100vw - 32px)); height:min(760px,calc(100dvh - 100px)); display:flex; flex-direction:column; overflow:hidden; border:1px solid #cad8ee; border-radius:16px; background:var(--oa-bg-surface); box-shadow:0 16px 60px #24406a26; }
.ai-dock-header { display:flex; gap:10px; align-items:center; padding:14px 16px; flex:none; border-bottom:1px solid var(--oa-border-subtle); background:var(--oa-bg-subtle); }
.ai-dock-title { flex:1; min-width:0; }
.ai-dock-header h2 { margin:0; font-size:15px; font-weight:600; }
.ai-dock-header p { margin:3px 0 0; color:var(--oa-text-secondary); font-size:11px; }
.ai-dock-symbol { display:grid; place-items:center; height:34px; width:34px; flex:none; border-radius:10px; background:var(--oa-primary-soft); color:var(--oa-primary); }
.ai-dock-tools { display:flex; gap:2px; }
.ai-dock-tools .icon-button { width:28px; height:30px; min-height:30px; }
.ai-dock-history { display:flex; align-items:center; gap:10px; padding:10px 16px; flex:none; background:var(--oa-bg-subtle); border-bottom:1px solid var(--oa-border-subtle); }
.ai-dock-history label { white-space:nowrap; font-size:12px; color:var(--oa-text-secondary); }
.ai-dock-history select { min-width:0; height:32px; font-size:12px; }
.ai-dock-context { flex:none; border-bottom:1px solid var(--oa-border-subtle); }
.ai-dock-context summary { display:flex; align-items:center; gap:9px; padding:9px 16px; cursor:pointer; list-style:none; }
.ai-dock-context summary::-webkit-details-marker { display:none; }
.ai-dock-context summary > span { color:var(--oa-text-tertiary); font-size:11px; flex:none; }
.ai-dock-context summary strong { flex:1; min-width:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; font-size:12px; font-weight:400; }
.ai-dock-context summary svg { color:var(--oa-text-tertiary); }
.ai-dock-context[open] summary svg { transform:rotate(180deg); }
.ai-dock-context p { margin:0; padding:0 16px 10px; color:var(--oa-text-secondary); font-size:11px; line-height:1.7; }
.ai-dock-messages { flex:1; overflow:auto; overscroll-behavior:contain; scrollbar-gutter:stable; padding:16px; min-height:0; }
.ai-dock-turn { margin-bottom:22px; }
.ai-dock-question { margin:0 0 14px 28px; padding:10px 13px; border-radius:12px 12px 3px 12px; color:var(--oa-text-primary); background:var(--oa-primary-soft); font-size:13px; white-space:pre-wrap; overflow-wrap:anywhere; }
.ai-dock-answer { font-size:13px; line-height:1.7; overflow-wrap:anywhere; }
.ai-dock-answer-status { display:block; color:var(--oa-text-secondary); font-size:12px; font-weight:400; margin-bottom:8px; }
.ai-dock-model { display:block; color:var(--oa-text-tertiary); font-size:11px; margin-top:10px; }
.ai-dock-sources,.ai-dock-evidence { margin-top:10px; font-size:11px; color:var(--oa-text-secondary); }
.ai-dock-sources summary,.ai-dock-evidence summary { cursor:pointer; }
.ai-dock-evidence pre { max-height:180px; overflow:auto; white-space:pre-wrap; overflow-wrap:anywhere; padding:10px; font:11px/1.7 var(--oa-font-mono); background:var(--oa-bg-subtle); border-radius:8px; }
.ai-dock-retry { margin-top:8px; min-height:30px; font-size:12px; }
.ai-dock-welcome { display:grid; gap:10px; color:var(--oa-text-secondary); padding:22px 0; }
.ai-dock-welcome > svg { color:var(--oa-primary); }
.ai-dock-welcome h3 { margin:3px 0 0; font-size:17px; color:var(--oa-text-primary); font-weight:500; }
.ai-dock-welcome p { margin:0 0 5px; font-size:12px; line-height:1.7; }
.ai-dock-welcome button { border:1px solid var(--oa-border-subtle); border-radius:8px; padding:10px 12px; text-align:left; background:var(--oa-bg-surface); font:inherit; font-size:13px; color:var(--oa-text-secondary); cursor:pointer; }
.ai-dock-welcome button:hover { color:var(--oa-primary); border-color:var(--oa-primary); background:var(--oa-bg-subtle); }
.ai-dock-composer-shell { flex:none; padding:12px 16px 16px; border-top:1px solid var(--oa-border-subtle); background:var(--oa-bg-surface); }
.ai-dock-provider-error { font-size:11px; line-height:1.6; color:var(--oa-text-secondary); margin:0 0 8px; }
.ai-dock-composer { margin:0; padding:10px 12px; border:1px solid var(--oa-border-default); border-radius:11px; background:var(--oa-bg-surface); }
.ai-dock-composer:focus-within { border-color:var(--oa-primary); }
.ai-dock-composer textarea { display:block; width:100%; min-height:64px; max-height:140px; padding:0; margin:0; resize:none; font-size:13px; line-height:1.7; border:0; border-radius:0; background:transparent; }
.ai-dock-composer textarea:focus { box-shadow:none; }
.ai-dock-composer-bar { display:flex; justify-content:space-between; align-items:center; gap:6px; flex-wrap:wrap; margin-top:8px; }
.ai-dock-composer select { min-width:0; max-width:180px; width:auto; height:30px; min-height:30px; padding:0 4px; border:0; background:transparent; color:var(--oa-text-secondary); font-size:11px; }
.ai-dock-composer button { flex:none; min-height:32px; padding:0 10px; font-size:12px; }
@media(max-width:640px) { .ai-assistant-dock { right:10px; bottom:max(10px,env(safe-area-inset-bottom)); width:calc(100vw - 20px); height:calc(100dvh - 85px); }.ai-dock-header { gap:8px; }.ai-dock-symbol { display:none; }.ai-dock-composer select { max-width:180px; } }
</style>
