<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import { request } from '@/api/http';
import InlineError from '@/components/InlineError.vue';
const props = defineProps<{ documentId: number }>();
const query = ref(''), busy = ref(false), error = ref(''), searched = ref(false);
const rows = ref<{ chunkId: number; documentId: number; documentName: string; content: string; version?: number }[]>([]);
let active = true;
onBeforeUnmount(() => { active = false; });
async function search() {
  if (busy.value || !query.value.trim()) return;
  busy.value = true; error.value = ''; searched.value = false; rows.value = [];
  try { const result = await request<typeof rows.value>({ url: '/api/knowledge/search', params: { query: query.value.trim(), documentId: props.documentId, topK: 3 } }); if (active) { rows.value = result; searched.value = true; } }
  catch (cause) { if (active) error.value = cause instanceof Error ? cause.message : '检索失败'; }
  finally { if (active) busy.value = false; }
}
</script>
<template>
  <section class="knowledge-retrieval-check"><h3>检索与引用</h3><form @submit.prevent="search"><input v-model="query" maxlength="500" aria-label="在当前文档检索" placeholder="输入该文档中的问题或关键词" /><button class="button secondary" :disabled="busy || !query.trim()">{{ busy ? '检索中…' : '验证检索' }}</button></form><InlineError v-if="error" :message="error" /><p v-if="searched && !rows.length">当前查询没有命中内容，可调整关键词；不据此判断文档未发布。</p><article v-for="row in rows" :key="row.chunkId"><strong>文档 #{{ row.documentId }} · 切片 #{{ row.chunkId }} · v{{ row.version || '—' }}</strong><p>{{ row.content }}</p></article></section>
</template>
<style scoped>
.knowledge-retrieval-check { display: grid; gap: 12px; }.knowledge-retrieval-check h3,.knowledge-retrieval-check p { margin: 0; }.knowledge-retrieval-check form { display: flex; gap: 8px; }.knowledge-retrieval-check input { flex: 1; min-width: 0; }.knowledge-retrieval-check article { padding: 14px; background: var(--oa-bg-subtle); border-radius: 8px; font-size: 13px; line-height: 1.65; }.knowledge-retrieval-check article p { white-space: pre-wrap; margin-top: 8px; max-height: 240px; overflow: auto; }
</style>
