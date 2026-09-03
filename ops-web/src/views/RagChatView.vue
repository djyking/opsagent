<script setup lang="ts">
import { ref } from "vue";
import { request } from "@/api/http";
const question = ref("");
const answer = ref("");
const references = ref<Record<string, unknown>[]>([]);
const model = ref("");
const latencyMs = ref(0);
const error = ref("");
const busy = ref(false);
async function ask() {
  busy.value = true;
  error.value = "";
  try {
    const r = await request<{
      answer: string;
      references: Record<string, unknown>[];
      provider: string;
      model: string;
      latencyMs: number;
    }>({
      method: "POST",
      url: "/api/rag/ask",
      data: { question: question.value, topK: 5 },
    });
    answer.value = r.answer;
    references.value = r.references;
    model.value = `${r.provider}/${r.model}`;
    latencyMs.value = r.latencyMs;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "问答请求失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section class="panel ai-panel">
    <header class="panel-header">
      <div>
        <span class="eyebrow">RAG CHAT</span>
        <h3>智能知识问答</h3>
      </div>
    </header>
    <form class="question-form" @submit.prevent="ask">
      <textarea
        v-model="question"
        required
        rows="4"
        maxlength="2000"
        placeholder="输入运维问题"
      ></textarea
      ><button class="button primary" :disabled="busy">
        {{ busy ? "检索中…" : "提问" }}
      </button>
    </form>
    <p v-if="error" class="error-text">{{ error }}</p>
    <article v-if="answer" class="qa-answer">
      <small>{{ model }} · {{ latencyMs }} ms</small>
      <p>{{ answer }}</p>
      <div class="reference-list">
        <span v-for="(item, index) in references" :key="index"
          >{{ item.documentName }} · Chunk {{ item.chunkIndex }}</span
        >
      </div>
    </article>
  </section>
</template>
