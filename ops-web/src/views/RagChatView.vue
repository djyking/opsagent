<script setup lang="ts">
import { ref } from "vue";
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
  answer.value = "";
  references.value = [];
  model.value = "正在生成";
  latencyMs.value = 0;
  let completed = false;
  try {
    const baseUrl = String(import.meta.env.VITE_API_BASE_URL || "").replace(
      /\/$/,
      "",
    );
    const token = localStorage.getItem("opsagent_token");
    const response = await fetch(`${baseUrl}/api/rag/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ question: question.value, topK: 5 }),
    });
    if (!response.ok || !response.body) {
      const body = (await response.json().catch(() => null)) as {
        message?: string;
      } | null;
      throw new Error(body?.message || `问答请求失败（HTTP ${response.status}）`);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        completed = handleEvent(buffer.slice(0, boundary)) || completed;
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
      }
      if (done) break;
    }
    if (buffer.trim()) completed = handleEvent(buffer) || completed;
    if (!completed) throw new Error("流式问答连接提前结束");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "问答请求失败";
  } finally {
    busy.value = false;
  }
}

function handleEvent(block: string): boolean {
  let event = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) event = line.slice(6).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (!data.length) return false;
  const payload = JSON.parse(data.join("\n")) as Record<string, unknown>;
  if (event === "token") {
    answer.value += String(payload.delta || "");
  } else if (event === "sources") {
    references.value = (payload.references || []) as Record<string, unknown>[];
  } else if (event === "done") {
    answer.value = String(payload.answer || answer.value);
    references.value = (payload.references || references.value) as Record<
      string,
      unknown
    >[];
    model.value = `${String(payload.provider)}/${String(payload.model)}`;
    latencyMs.value = Number(payload.latencyMs || 0);
    return true;
  } else if (event === "error") {
    throw new Error(String(payload.message || "流式问答失败"));
  }
  return false;
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
      <p>{{ answer }}</p>
      <div class="reference-list">
        <span v-for="(item, index) in references" :key="index"
          >{{ item.documentName }} · Chunk {{ item.chunkIndex }}</span
        >
      </div>
    </article>
  </section>
</template>
