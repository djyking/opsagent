<script setup lang="ts">
import { nextTick, ref, watch } from "vue";
import { BookOpen, Bot, Clock3, Copy, PanelRightClose, PanelRightOpen, Plus, RotateCw, Send, Sparkles } from "@lucide/vue";
import { useRoute } from "vue-router";
import { streamRagAnswer } from "@/api/rag-stream";
import type { AiReference } from "@/types/api";
import AnswerContent from "@/components/AnswerContent.vue";
import RagSources from "@/components/RagSources.vue";

const question = ref("");
const answer = ref("");
const references = ref<AiReference[]>([]);
const model = ref("");
const latencyMs = ref(0);
const error = ref("");
const progress = ref("");
const busy = ref(false);
const submittedQuestion = ref("");
const contextOpen = ref(true);
const route = useRoute();
const suggestions = ["排查 Redis 连接超时", "分析 RabbitMQ 消息堆积", "查询 SLA 超时处理规范"];

function newSession() {
  question.value = "";
  answer.value = "";
  references.value = [];
  model.value = "";
  latencyMs.value = 0;
  error.value = "";
  progress.value = "";
  submittedQuestion.value = "";
}

function askSuggestion(value: string) {
  question.value = value;
  ask();
}

async function ask() {
  const submitted = question.value.trim();
  if (!submitted || busy.value) return;
  busy.value = true;
  submittedQuestion.value = submitted;
  error.value = "";
  answer.value = "";
  references.value = [];
  model.value = "";
  latencyMs.value = 0;
  progress.value = "正在检索知识库";
  try {
    const result = await streamRagAnswer(
      { question: submitted, topK: 5 },
      {
        onStatus: (message) => (progress.value = message),
        onSources: (rows) => (references.value = rows),
        onToken: async (delta) => {
          answer.value += delta;
          await nextTick();
          await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
        },
      },
    );
    answer.value = result.answer || answer.value;
    references.value = result.references;
    model.value = `${result.provider}/${result.model}`;
    latencyMs.value = result.latencyMs;
    progress.value = "回答生成完成";
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "问答请求失败";
    progress.value = "";
  } finally {
    busy.value = false;
  }
}

function retry() {
  if (!submittedQuestion.value || busy.value) return;
  question.value = submittedQuestion.value;
  ask();
}

async function copyAnswer() {
  if (answer.value) await navigator.clipboard.writeText(answer.value);
}

watch(() => route.query.new, (value) => {
  if (value === "1" && !busy.value) newSession();
}, { immediate: true });
</script>

<template>
  <section class="rag-three-pane" :class="{ 'context-closed': !contextOpen }">
    <aside class="rag-conversations">
      <header class="panel-header"><div><h3>会话</h3><span class="panel-count">当前浏览器</span></div><button class="icon-button" title="新会话" @click="newSession"><Plus :size="15" /></button></header>
      <button class="rag-session active"><Bot :size="16" /><span><strong>{{ submittedQuestion || "新会话" }}</strong><small>{{ answer ? "回答完成" : busy ? progress : "等待提问" }}</small></span></button>
      <p class="rag-history-notice">历史记录暂未启用</p>
    </aside>

    <main class="ai-panel rag-workspace">
      <header class="panel-header">
        <div><Sparkles :size="15" /><h3>智能知识问答</h3></div>
        <div class="rag-header-actions"><button class="button primary" @click="newSession"><Plus :size="15" />新会话</button><button class="icon-button rag-context-toggle" :title="contextOpen ? '收起检索上下文' : '展开检索上下文'" @click="contextOpen = !contextOpen"><PanelRightClose v-if="contextOpen" :size="17" /><PanelRightOpen v-else :size="17" /></button></div>
      </header>
      <div class="rag-chat-scroll">
        <div v-if="submittedQuestion" class="rag-user-message"><strong>你</strong><p>{{ submittedQuestion }}</p></div>
        <div v-if="busy || answer" class="stream-answer">
          <div class="answer-status">
            <span :class="{ pulse: busy }"><Bot :size="18" /></span>
            <div><strong>{{ busy ? progress : "回答完成" }}</strong><small v-if="model"><Clock3 :size="13" />{{ model }} · {{ latencyMs }} ms</small></div>
            <div v-if="answer && !busy" class="rag-answer-actions"><button class="icon-button" title="复制回答" @click="copyAnswer"><Copy :size="15" /></button><button class="icon-button" title="重新生成" @click="retry"><RotateCw :size="15" /></button></div>
          </div>
          <AnswerContent v-if="answer" :content="answer" />
          <div v-else class="answer-skeleton"><i /><i /><i /></div>
        </div>
        <div v-else class="rag-empty"><span class="rag-empty-icon"><Sparkles :size="22" /></span><strong>有什么运维问题？</strong><p>我会先检索知识库，再基于真实来源生成回答。</p><div class="suggested-prompts"><button v-for="item in suggestions" :key="item" @click="askSuggestion(item)">{{ item }}</button></div></div>
        <p v-if="error" class="inline-error rag-error">{{ error }}</p>
      </div>
      <form class="question-form rag-question-form chat-composer" @submit.prevent="ask">
      <textarea
        v-model.trim="question"
        required
        rows="3"
        maxlength="2000"
        placeholder="输入运维问题…"
        @keydown.enter.exact.prevent="ask"
      />
      <div class="question-submit-row">
        <span class="knowledge-scope"><BookOpen :size="14" />知识库</span>
        <span class="composer-hint">Enter 发送 · Shift + Enter 换行</span>
        <button class="composer-send" :disabled="busy || !question.trim()" :title="busy ? '生成中' : '发送问题'" aria-label="发送问题"><Send :size="16" /></button>
      </div>
      </form>
    </main>

    <aside v-if="contextOpen" class="rag-context-panel">
      <header class="panel-header"><div><h3>检索上下文</h3><span class="panel-count">{{ references.length }} 条来源</span></div></header>
      <p class="rag-context-note">来源、Chunk 与相关度均由检索接口返回。</p>
      <RagSources :references="references" />
      <p v-if="!references.length" class="rag-context-empty">完成提问后，这里显示命中的知识片段。</p>
    </aside>
  </section>
</template>
