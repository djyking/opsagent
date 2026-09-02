<script setup lang="ts">
import { ref } from "vue";
import { request } from "@/api/http";
const question = ref("");
const answer = ref("");
const references = ref<Record<string, unknown>[]>([]);
const busy = ref(false);
async function ask() {
  busy.value = true;
  try {
    const r = await request<{
      answer: string;
      references: Record<string, unknown>[];
    }>({
      method: "POST",
      url: "/api/rag/chat",
      data: { question: question.value, topK: 5 },
    });
    answer.value = r.answer;
    references.value = r.references;
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
    <article v-if="answer" class="qa-answer">
      <p>{{ answer }}</p>
      <div class="reference-list">
        <span v-for="(item, index) in references" :key="index"
          >{{ item.documentName }} · Chunk {{ item.chunkIndex }}</span
        >
      </div>
    </article>
  </section>
</template>
