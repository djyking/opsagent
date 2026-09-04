<script setup lang="ts">
import { nextTick, ref } from "vue";
import AnswerContent from "@/components/AnswerContent.vue";
import RagSources from "@/components/RagSources.vue";
import { streamRagAnswer } from "@/api/rag-stream";
import type { AiReference } from "@/types/api";
const question = ref("");
const answer = ref("");
const references = ref<AiReference[]>([]);
const model = ref("");
const latencyMs = ref(0);
const error = ref("");
const busy = ref(false);

async function ask() {
  busy.value = true;
  error.value = "";
  answer.value = "";
  references.value = [];
  model.value = "正在生成";
  latencyMs.value = 0;
  try {
    const result = await streamRagAnswer(
      { question: question.value, topK: 5 },
      {
        onToken: async (delta) => {
          answer.value += delta;
          await nextTick();
        },
        onSources: (rows) => (references.value = rows),
      },
    );
    answer.value = result.answer || answer.value;
    references.value = result.references;
    model.value = `${result.provider}/${result.model}`;
    latencyMs.value = result.latencyMs;
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
        {{ busy ? "生成中…" : "提问" }}
      </button>
    </form>
    <p v-if="error" class="error-text">{{ error }}</p>
    <article v-if="answer" class="qa-answer">
      <small>{{ model }} · {{ latencyMs }} ms</small>
      <AnswerContent :content="answer" />
      <RagSources :references="references" compact />
    </article>
  </section>
</template>
