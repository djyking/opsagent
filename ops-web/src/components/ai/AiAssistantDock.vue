<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { storeToRefs } from 'pinia';
import { Bot, ChevronDown, Maximize2, Plus, Send, X } from '@lucide/vue';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { ragAnswerLabel } from '@/api/rag-stream';
import { assistantQuestionBody, assistantQuestionEvidence } from '@/utils/ai-context';
import AnswerContent from '@/components/AnswerContent.vue';
import RagSources from '@/components/RagSources.vue';
import RagBudgetEvidence from '@/components/RagBudgetEvidence.vue';
const assistant = useAiAssistantStore();
const { chatScroll, questionInput } = storeToRefs(assistant);
const route = useRoute(), router = useRouter();
const dock = ref<HTMLElement>();
const visible = computed(() => assistant.open && route.path !== '/rag/chat');
const suggestions = computed(() => assistant.context.ticketId
  ? ['总结当前事件的现象与证据', '分析可能原因与待补充证据', '推荐相关 Runbook']
  : assistant.context.service ? ['分析当前服务健康状态', '解释 Sentinel 是否发生限流', '总结最近一次配置变更', '推荐相关 Runbook']
    : ['检查当前服务健康与内存趋势', '当前有哪些异常需要关注？', '查询服务目录与依赖关系']);
let previousFocus: HTMLElement | null = null;
watch(visible, async value => {
  if (value) { previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null; await nextTick(); questionInput.value?.focus(); }
  else if (previousFocus?.isConnected) { previousFocus.focus(); previousFocus = null; }
});
function expand() { assistant.hide(); void router.push({ path: '/rag/chat', query: assistant.sessionId ? { conversation: assistant.sessionId } : {} }); }
function close() { assistant.hide(); }
function prompt(value: string) { assistant.question = value; void nextTick(() => questionInput.value?.focus()); }
onBeforeUnmount(() => { if (previousFocus?.isConnected) previousFocus.focus(); });
</script>
<template>
  <section v-if="visible" ref="dock" class="ai-assistant-dock" role="dialog" aria-modal="false" aria-labelledby="ai-dock-title" @keydown.esc.stop="close">
    <header class="ai-dock-header"><span class="ai-dock-symbol"><Bot :size="22" /></span><div><h2 id="ai-dock-title">OpsAgent AI</h2><p>只读分析 · 与智能问答共享会话</p></div><button class="icon-button" aria-label="新建助手会话" :disabled="assistant.busy" @click="assistant.newSession"><Plus :size="17" /></button><button class="icon-button" aria-label="在完整页面继续当前会话" @click="expand"><Maximize2 :size="16" /></button><button class="icon-button" aria-label="收起 AI 助手" @click="close"><X :size="18" /></button></header>
    <div class="ai-dock-context"><span>当前上下文</span><strong>{{ assistant.contextSummary || '全局运维问答' }}</strong><small>上下文用于限定分析对象；结论以实际引用为准。</small></div>
    <label class="ai-dock-history"><span>我的会话</span><select :value="assistant.sessionId" :disabled="assistant.busy || assistant.loading" aria-label="切换助手会话" @change="($event.target as HTMLSelectElement).value ? assistant.selectSession(($event.target as HTMLSelectElement).value) : assistant.newSession()"><option value="">新会话</option><option v-for="session in assistant.sessions" :key="session.id" :value="session.id">{{ session.title }}</option></select></label>
    <div ref="chatScroll" class="ai-dock-messages" :aria-busy="assistant.busy || assistant.loading">
      <p v-if="assistant.loading" role="status">正在读取会话…</p>
      <button v-if="assistant.hasEarlier" class="button text" :disabled="assistant.busy || assistant.loading" @click="assistant.earlier">加载更早消息</button>
      <article v-for="turn in assistant.turns" :key="turn.id" class="ai-dock-turn"><p class="ai-dock-question">{{ assistantQuestionBody(turn.question) }}</p><details v-if="assistantQuestionEvidence(turn.question)" class="ai-dock-evidence"><summary>提问上下文与现场快照</summary><pre>{{ assistantQuestionEvidence(turn.question) }}</pre></details><div class="ai-dock-answer"><strong class="ai-dock-answer-status">{{ assistant.turnLabel(turn) }}</strong><AnswerContent v-if="turn.answer" :content="turn.answer" /><p v-else-if="turn.status === 'PROCESSING'" role="status">正在读取数据与生成分析…</p><small v-if="turn.result" class="ai-dock-model">{{ ragAnswerLabel(turn.result) }}</small><RagBudgetEvidence v-if="turn.result" :result="turn.result" /><p v-if="turn.errorMessage" class="inline-error">{{ turn.errorMessage }}</p><details v-if="turn.result?.references.length" class="ai-dock-sources"><summary><ChevronDown :size="13" />{{ turn.result.references.length }} 条引用依据</summary><RagSources :references="turn.result.references" /></details></div></article>
      <div v-if="!assistant.turns.length && !assistant.loading" class="ai-dock-welcome"><Bot :size="28" /><h3>从当前问题开始</h3><p>服务、事件和告警上下文会随页面更新，切换页面也可以继续本次会话。</p><button v-for="item in suggestions" :key="item" :disabled="assistant.busy" @click="prompt(item)">{{ item }}</button></div>
      <p v-if="assistant.historyError" class="inline-error">{{ assistant.historyError }} <button class="button text" @click="assistant.refreshHistory()">重试</button></p><p v-if="assistant.error" class="inline-error" role="alert">{{ assistant.error }}</p>
    </div>
    <form class="ai-dock-composer" @submit.prevent="assistant.ask()"><p v-if="assistant.providerError" class="inline-error">{{ assistant.providerError }} <button type="button" class="button text" :disabled="assistant.providersLoading" @click="assistant.loadProviders">重试</button></p><textarea ref="questionInput" v-model="assistant.question" rows="3" maxlength="2000" placeholder="询问当前服务或事件…" aria-label="AI 助手问题" @keydown.enter.exact.prevent="assistant.ask()" /><div><select v-model="assistant.selectedProvider" aria-label="助手回答模型" :disabled="assistant.busy || assistant.providersLoading || !assistant.providersReady"><option v-if="!assistant.selectedProvider" value="">{{ assistant.providersLoading ? '读取模型配置…' : '仅知识检索' }}</option><option v-for="provider in assistant.providers" :key="provider.provider" :value="provider.provider" :disabled="!provider.available">{{ provider.model || provider.provider }}</option></select><button class="button primary" :disabled="assistant.busy || assistant.loading || !assistant.question.trim() || !assistant.providersReady || assistant.providersLoading" aria-label="发送助手问题"><Send :size="16" />{{ assistant.busy ? '生成中' : '发送' }}</button></div></form>
  </section>
</template>
<style scoped>
.ai-dock-evidence { margin: 0 0 12px; font-size: 10px; color: var(--oa-text-muted); }.ai-dock-evidence summary { cursor: pointer; }.ai-dock-evidence pre { white-space: pre-wrap; overflow-wrap: anywhere; padding: 8px; font: inherit; line-height: 1.6; background: var(--oa-bg-subtle); border-radius: 8px; }
.ai-assistant-dock { position: fixed; right: 22px; bottom: max(22px, env(safe-area-inset-bottom)); z-index: 39; width: min(460px, calc(100vw - 32px)); height: min(760px, calc(100dvh - 100px)); display: flex; flex-direction: column; overflow: hidden; border: 1px solid #cad8ee; border-radius: 18px; background: var(--oa-bg-surface); box-shadow: 0 16px 60px #24406a26; }
.ai-dock-header { display: flex; gap: 9px; align-items: center; padding: 15px 16px; border-bottom: 1px solid var(--oa-border-subtle); background: linear-gradient(110deg, #eff6ff, #f7f4ff); }
.ai-dock-header > div { flex: 1; min-width: 0; }.ai-dock-header h2 { margin: 0; font-size: 15px; font-weight: 600; }.ai-dock-header p { margin: 3px 0 0; color: var(--oa-text-muted); font-size: 11px; }
.ai-dock-symbol { display: grid; place-items: center; height: 36px; width: 36px; border-radius: 12px; background: #e3eaff; color: #6077bd; }
.ai-dock-context { display: grid; gap: 4px; padding: 12px 16px; border-bottom: 1px solid var(--oa-border-subtle); background: var(--oa-bg-subtle); }.ai-dock-context span { color: var(--oa-text-muted); font-size: 11px; }.ai-dock-context strong { overflow-wrap: anywhere; font-size: 12px; font-weight: 500; }.ai-dock-context small { color: var(--oa-text-muted); font-size: 11px; }
.ai-dock-history { display: flex; align-items: center; gap: 10px; padding: 8px 16px; font-size: 12px; }.ai-dock-history span { white-space: nowrap; color: var(--oa-text-secondary); }.ai-dock-history select { min-width: 0; width: 100%; min-height: 32px; font-size: 12px; }
.ai-dock-messages { flex: 1; overflow: auto; overscroll-behavior: contain; padding: 12px 16px; min-height: 80px; }.ai-dock-turn { margin-bottom: 20px; }.ai-dock-question { margin: 0 0 12px 30px; padding: 11px 13px; border-radius: 12px 12px 3px 12px; color: var(--oa-text-primary); background: var(--oa-primary-soft); font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; }
.ai-dock-answer { font-size: 13px; }.ai-dock-answer-status { display: block; color: var(--oa-text-secondary); font-size: 11px; font-weight: 500; margin-bottom: 10px; }.ai-dock-model { display: block; color: var(--oa-text-muted); font-size: 10px; margin-top: 10px; }.ai-dock-sources { margin-top: 10px; }.ai-dock-sources summary { display: flex; align-items: center; gap: 5px; color: var(--oa-primary); font-size: 12px; cursor: pointer; }
.ai-dock-welcome { display: grid; gap: 9px; color: var(--oa-text-secondary); padding-block: 12px; }.ai-dock-welcome > svg { color: var(--oa-primary); }.ai-dock-welcome h3 { margin: 4px 0 0; font-size: 16px; font-weight: 500; }.ai-dock-welcome p { margin: 0 0 6px; font-size: 12px; line-height: 1.7; }.ai-dock-welcome button { border: 1px solid var(--oa-border-subtle); border-radius: 9px; padding: 10px; text-align: left; background: var(--oa-bg-subtle); font: inherit; font-size: 12px; color: var(--oa-text-secondary); cursor: pointer; }.ai-dock-welcome button:hover { color: var(--oa-primary); border-color: var(--oa-primary); }
.ai-dock-composer { border-top: 1px solid var(--oa-border-subtle); padding: 12px 16px; background: var(--oa-bg-surface); }.ai-dock-composer textarea { width: 100%; min-height: 64px; max-height: 180px; resize: vertical; font-size: 13px; }.ai-dock-composer > div { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-top: 8px; }.ai-dock-composer select { min-width: 0; max-width: 260px; min-height: 34px; font-size: 12px; }.ai-dock-composer button { flex: none; }
@media (max-width: 640px) { .ai-assistant-dock { right: 10px; bottom: max(10px, env(safe-area-inset-bottom)); width: calc(100vw - 20px); height: calc(100dvh - 85px); } }
</style>
