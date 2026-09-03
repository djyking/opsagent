<script setup lang="ts">
import { nextTick, ref } from "vue";
import { Bot, Clock3, Database, Send } from "@lucide/vue";
import { streamRagAnswer } from "@/api/rag-stream";
import type { AiReference } from "@/types/api";
import AnswerContent from "@/components/AnswerContent.vue";

const question = ref("");
const answer = ref("");
const references = ref<AiReference[]>([]);
const model = ref("");
const latencyMs = ref(0);
const error = ref("");
const progress = ref("");
const busy = ref(false);

async function ask() {
  const submitted = question.value.trim();
  if (!submitted || busy.value) return;
  busy.value = true;
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
</script>

<template>
  <section class="panel ai-panel rag-workspace">
    <header class="panel-header">
      <div>
        <span class="eyebrow">RAG CHAT</span>
        <h3>智能知识问答</h3>
      </div>
      <Bot :size="26" />
    </header>
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
    <p v-if="error" class="inline-error rag-error">{{ error }}</p>
    <div v-if="busy || answer" class="stream-answer">
      <div class="answer-status">
        <span :class="{ pulse: busy }"><Bot :size="18" /></span>
        <div>
          <strong>{{ busy ? progress : "回答完成" }}</strong>
          <small v-if="model"><Clock3 :size="13" />{{ model }} · {{ latencyMs }} ms</small>
        </div>
      </div>
      <AnswerContent v-if="answer" :content="answer" />
      <div v-else class="answer-skeleton"><i /><i /><i /></div>
      <div v-if="references.length" class="source-section">
        <strong><Database :size="16" />参考来源</strong>
        <div class="reference-list source-cards">
          <span v-for="item in references" :key="item.chunkId">
            {{ item.documentName || `文档 #${item.documentId}` }}
            <small v-if="item.pageNumber">第 {{ item.pageNumber }} 页</small>
            <small v-if="item.relevanceScore">相关度 {{ (item.relevanceScore * 100).toFixed(0) }}%</small>
          </span>
        </div>
      </div>
    </div>
    <div v-else class="empty-state rag-empty">
      <Bot :size="34" /><strong>输入问题开始检索</strong>
      <span>系统会先寻找相关知识，再逐段展示模型回答。</span>
    </div>
  </section>
</template>
