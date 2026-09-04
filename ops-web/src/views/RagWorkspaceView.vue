<script setup lang="ts">
import { nextTick, ref } from "vue";
import { Bot, Clock3, Copy, PanelRightClose, PanelRightOpen, RotateCw, Send } from "@lucide/vue";
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
</script>

<template>
  <section class="rag-three-pane" :class="{ 'context-closed': !contextOpen }">
    <aside class="panel rag-conversations">
      <header class="panel-header"><div><h3>会话</h3><span class="panel-count">当前浏览器</span></div></header>
      <button class="rag-session active"><Bot :size="16" /><span><strong>{{ submittedQuestion || "新会话" }}</strong><small>{{ answer ? "回答完成" : busy ? progress : "等待提问" }}</small></span></button>
      <div class="empty-state small-empty"><span>历史会话接口尚未启用，本页不会伪造记录。</span></div>
    </aside>

    <main class="panel ai-panel rag-workspace">
      <header class="panel-header">
        <div><span class="eyebrow">RAG CHAT</span><h3>智能知识问答</h3></div>
        <button class="icon-button rag-context-toggle" :title="contextOpen ? '收起检索上下文' : '展开检索上下文'" @click="contextOpen = !contextOpen">
          <PanelRightClose v-if="contextOpen" :size="17" /><PanelRightOpen v-else :size="17" />
        </button>
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
        <div v-else class="empty-state rag-empty"><Bot :size="34" /><strong>输入问题开始检索</strong><span>系统会先寻找相关知识，再逐段展示模型回答。</span></div>
        <p v-if="error" class="inline-error rag-error">{{ error }}</p>
      </div>
      <form class="question-form rag-question-form" @submit.prevent="ask">
      <textarea
        v-model.trim="question"
        required
        rows="4"
        maxlength="2000"
        placeholder="输入运维问题，例如：生产服务器磁盘使用率超过 90% 应该如何处理？"
      />
      <div class="question-submit-row">
        <span>回答会引用内部知识库；30 秒内无首段响应将自动结束并提示。</span>
        <button class="button primary" :disabled="busy || !question.trim()">
          <Send :size="17" />{{ busy ? "生成中…" : "提问" }}
        </button>
      </div>
      </form>
    </main>

    <aside v-if="contextOpen" class="panel rag-context-panel">
      <header class="panel-header"><div><h3>检索上下文</h3><span class="panel-count">{{ references.length }} 条来源</span></div></header>
      <p class="rag-context-note">仅展示后端实际返回的引用、相关度与检索通道。</p>
      <RagSources :references="references" />
      <div v-if="!references.length" class="empty-state small-empty"><span>提问后在这里查看真实检索结果。</span></div>
    </aside>
  </section>
</template>
