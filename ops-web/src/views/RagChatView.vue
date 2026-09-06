<script setup lang="ts">
import { nextTick, onMounted, onBeforeUnmount, ref } from "vue";
import AnswerContent from "@/components/AnswerContent.vue";
import RagSources from "@/components/RagSources.vue";
import { ragAnswerLabel, streamRagAnswer } from "@/api/rag-stream";
import type { AiReference } from "@/types/api";
import { ragProviderApi, type ProviderOption, type AiProvider } from "@/api/conversations";
const question = ref("");
const answer = ref("");
const references = ref<AiReference[]>([]);
const model = ref("");
const latencyMs = ref(0);
const error = ref("");
const busy = ref(false);
const providers = ref<ProviderOption[]>([]);
const selectedProvider = ref<AiProvider | ''>('');
const ready = ref(false);
let controller: AbortController | undefined;
async function loadProviders() {
  error.value = '';
  try {
    const catalog = await ragProviderApi.list();
    providers.value = catalog.providers;
    selectedProvider.value = catalog.defaultProvider || catalog.providers.find(item => item.available)?.provider || '';
    ready.value = true;
  } catch { error.value = '模型列表读取失败，请重试。'; }
}
onMounted(loadProviders);
onBeforeUnmount(() => controller?.abort());

async function ask() {
  if (busy.value || !ready.value || !question.value.trim()) return;
  controller = new AbortController();
  busy.value = true;
  error.value = "";
  answer.value = "";
  references.value = [];
  model.value = "正在生成";
  latencyMs.value = 0;
  try {
    const result = await streamRagAnswer(
      { question: question.value, topK: 5, provider: selectedProvider.value || undefined },
      {
        onToken: async (delta) => {
          answer.value += delta;
          await nextTick();
        },
        onSources: (rows) => (references.value = rows),
      },
      controller.signal,
    );
    answer.value = result.answer || answer.value;
    references.value = result.references;
    model.value = ragAnswerLabel(result);
    latencyMs.value = result.latencyMs;
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "问答请求失败";
  } finally {
    busy.value = false;
    controller = undefined;
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
      <label>回答模型<select v-model="selectedProvider" :disabled="busy || !ready"><option v-if="!selectedProvider" value="">仅数据与知识检索</option><option v-for="item in providers" :key="item.provider" :value="item.provider" :disabled="!item.available">{{ item.model || item.provider }}{{ item.available ? '' : ' · ' + item.status }}</option></select></label>
      <textarea
        v-model="question"
        required
        rows="4"
        maxlength="2000"
        placeholder="输入运维问题"
      ></textarea
      ><button class="button primary" :disabled="busy || !ready">
        {{ busy ? "生成中…" : "提问" }}
      </button>
    </form>
    <p v-if="error" class="error-text">{{ error }} <button v-if="!ready" class="button secondary" @click="loadProviders">重试</button></p>
    <article v-if="answer" class="qa-answer">
      <small>{{ model }} · {{ latencyMs }} ms</small>
      <AnswerContent :content="answer" />
      <RagSources :references="references" compact />
    </article>
  </section>
</template>
