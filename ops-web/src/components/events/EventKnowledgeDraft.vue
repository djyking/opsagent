<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { request, ApiError } from '@/api/http';
import BaseModal from '@/components/BaseModal.vue';
import FormField from '@/components/FormField.vue';
import InlineError from '@/components/InlineError.vue';

const props = defineProps<{ open: boolean; ticketId: number; ticketTitle: string; initialContent: string; canSave: boolean }>();
const emit = defineEmits<{ close: []; saved: [documentId: number] }>();
const bases = ref<{ id: number; name: string }[]>([]);
const baseId = ref<number>();
const content = ref(props.initialContent);
const busy = ref(false), loading = ref(true), error = ref(''), uncertain = ref(false);
const saved = ref<{ id: number; baseId: number }>();
const documentLink = computed(() => ({ path: '/knowledge', query: { baseId: saved.value?.baseId, documentId: saved.value?.id } }));
let active = true;
onBeforeUnmount(() => { active = false; });
async function loadBases() {
  loading.value = true; error.value = '';
  try { const result = await request<{ id: number; name: string }[]>({ url: '/api/knowledge/bases' }); if (active) bases.value = result; }
  catch (cause) { if (active) error.value = cause instanceof Error ? cause.message : '知识库读取失败'; }
  finally { if (active) loading.value = false; }
}
async function save() {
  if (!props.canSave || !baseId.value || !content.value.trim() || busy.value || saved.value || uncertain.value) return;
  const target = baseId.value;
  busy.value = true; error.value = '';
  try {
    const data = new FormData();
    data.append('file', new File([content.value], `event-${props.ticketId}-retrospective.md`, { type: 'text/markdown' }));
    data.append('ticketId', String(props.ticketId)); data.append('visibility', 'PRIVATE');
    const id = await request<number>({ method: 'POST', url: `/api/knowledge/bases/${target}/documents`, data });
    if (!active) return;
    saved.value = { id, baseId: target }; emit('saved', id);
  } catch (cause) {
    if (!active) return;
    uncertain.value = !(cause instanceof ApiError && (((cause.status || 0) >= 400 && (cause.status || 0) < 500) || [40000, 40300, 40400, 40900].includes(cause.code || 0)));
    error.value = cause instanceof Error ? cause.message : '保存失败';
  } finally { if (active) busy.value = false; }
}
watch(() => props.open, (open) => {
  if (!open) return;
  if (!saved.value && !uncertain.value) content.value = props.initialContent;
  if (!bases.value.length) void loadBases();
}, { immediate: true });
</script>

<template>
  <BaseModal v-if="open" title="事件知识草稿" :description="ticketTitle" wide @close="!busy && emit('close')">
    <div class="event-knowledge-draft">
      <p>先选择保存位置并核对内容，之后在文档页继续解析和审核。</p>
      <InlineError v-if="error" :message="error" />
      <FormField label="目标知识库"><select v-model="baseId" :disabled="loading || busy || !!saved || uncertain" aria-label="事件草稿目标知识库"><option :value="undefined" disabled>{{ loading ? '正在读取知识库…' : '请选择知识库' }}</option><option v-for="base in bases" :key="base.id" :value="base.id">{{ base.name }}</option></select></FormField>
      <p v-if="!loading && !bases.length">尚无可选知识库。<RouterLink to="/knowledge">前往创建知识库</RouterLink></p>
      <button v-if="error && !bases.length" class="button secondary" :disabled="loading" @click="loadBases">重新读取</button>
      <FormField label="复盘内容"><textarea v-model="content" rows="13" :disabled="busy || !!saved || uncertain" /></FormField>
      <p v-if="saved" class="inline-success" role="status">已保存文档 #{{ saved.id }} · {{ bases.find(base => base.id === saved?.baseId)?.name }}。当前为私有草稿，尚未发布。</p>
      <p v-else-if="uncertain" role="status">保存结果尚未确认，请先在事件关联文档中核对，避免重复创建。</p>
      <p v-else-if="!canSave">保存需要事件创建人、处理人或管理员权限。</p>
      <div class="form-actions"><button class="button secondary" :disabled="busy" @click="emit('close')">关闭</button><RouterLink v-if="saved" class="button primary" :to="documentLink">打开草稿，继续解析 →</RouterLink><button v-else-if="canSave && !uncertain" class="button primary" :disabled="busy || !baseId || !content.trim()" @click="save">{{ busy ? '保存中…' : '保存私有草稿' }}</button></div>
    </div>
  </BaseModal>
</template>

<style scoped>
.event-knowledge-draft { display: grid; gap: 16px; }.event-knowledge-draft p { margin: 0; color: var(--oa-text-secondary); font-size: 13px; line-height: 1.6; }.event-knowledge-draft textarea { resize: vertical; min-height: 220px; }
</style>
