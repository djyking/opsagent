<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { request } from '@/api/http';
import BaseModal from '@/components/BaseModal.vue';
import InlineError from '@/components/InlineError.vue';
const props = defineProps<{ document: { id: number; original_name: string; file_type: string; version?: number; review_comment?: string } }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const content = ref(''), file = ref<File>(), version = ref(props.document.version), error = ref('');
const loading = ref(false), busy = ref(false), textLoaded = ref(false);
const editable = ['md', 'markdown', 'txt'].includes(props.document.file_type.toLowerCase());
let active = true;
onBeforeUnmount(() => { active = false; });
onMounted(async () => {
  if (!editable) return;
  loading.value = true;
  try {
    const result = await request<{ version: number; text: string }>({ url: `/api/knowledge/documents/${props.document.id}/draft-text` });
    if (active) { version.value = result.version; content.value = result.text; textLoaded.value = true; }
  } catch (cause) { if (active) error.value = cause instanceof Error ? cause.message : '原文件读取失败，可上传真实修订文件'; }
  finally { if (active) loading.value = false; }
});
async function save() {
  if (busy.value || !version.value || (!file.value && (!textLoaded.value || !content.value.trim()))) return;
  busy.value = true; error.value = '';
  try {
    const data = new FormData(); data.append('version', String(version.value));
    data.append('file', file.value || new File([content.value], props.document.original_name, { type: 'text/plain' }));
    await request({ method: 'PUT', url: `/api/knowledge/documents/${props.document.id}/draft-file`, data });
    if (active) emit('saved');
  } catch (cause) { if (active) error.value = cause instanceof Error ? cause.message : '修订保存失败'; }
  finally { if (active) busy.value = false; }
}
</script>

<template>
  <BaseModal title="修改知识草稿" :description="`${document.original_name} · 版本 ${version || '未读取'}`" wide @close="!busy && emit('close')">
    <div class="knowledge-revision">
      <p v-if="document.review_comment" class="knowledge-review-comment">退回意见：{{ document.review_comment }}</p>
      <InlineError v-if="error" :message="error" />
      <p v-if="loading">正在读取原文…</p><label v-else-if="textLoaded">修改原文<textarea v-model="content" rows="15" :disabled="busy || !!file" /></label>
      <label>{{ editable ? '或上传修订文件' : '上传修订文件' }}<input type="file" accept=".pdf,.docx,.txt,.md,.markdown" :disabled="busy" @change="file = ($event.target as HTMLInputElement).files?.[0]" /></label>
      <p>保存后保留文档编号和来源事件，生成新版本；请重新解析并提交审核。</p>
      <div class="form-actions"><button class="button secondary" :disabled="busy" @click="emit('close')">取消</button><button class="button primary" :disabled="busy || loading || !version || (!file && (!textLoaded || !content.trim()))" @click="save">{{ busy ? '保存中…' : '保存修订' }}</button></div>
    </div>
  </BaseModal>
</template>

<style scoped>
.knowledge-revision { display: grid; gap: 16px; }.knowledge-revision label { display: grid; gap: 8px; }.knowledge-revision textarea { resize: vertical; }.knowledge-revision p { margin: 0; font-size: 13px; line-height: 1.6; color: var(--oa-text-secondary); }.knowledge-review-comment { padding: 12px; background: var(--oa-bg-subtle); border-radius: 8px; }
</style>
