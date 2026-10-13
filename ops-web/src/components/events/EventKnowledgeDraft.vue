<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { request, ApiError } from '@/api/http';
import { visitorKnowledgeApi } from '@/api/visitor-knowledge';
import { useAuthStore } from '@/stores/auth';
import BaseModal from '@/components/BaseModal.vue';
import FormField from '@/components/FormField.vue';
import InlineError from '@/components/InlineError.vue';

const props = defineProps<{ open: boolean; ticketId: number; ticketTitle: string; initialContent: string; canSave: boolean }>();
const emit = defineEmits<{ close: []; saved: [documentId: number] }>();
const auth = useAuthStore();
const isVisitor = computed(() => auth.isDemo);
const bases = ref<{ id: number; name: string }[]>([]);
const baseId = ref<number>();
const content = ref(props.initialContent);
const busy = ref(false), loading = ref(true), error = ref(''), uncertain = ref(false);
const saved = ref<{ id: number; baseId: number }>();
const documentLink = computed(() => ({ path: '/knowledge', query: { ...(isVisitor.value ? {} : { baseId: saved.value?.baseId }), documentId: saved.value?.id } }));
let active = true;
let contextVersion = 0, loadVersion = 0;
const valid = (version: number) => active && version === contextVersion;
onBeforeUnmount(() => { active = false; contextVersion++; });
async function loadBases() {
  const version = contextVersion, invocation = ++loadVersion;
  loading.value = true; error.value = '';
  try {
    if (isVisitor.value) {
      const library = await visitorKnowledgeApi.get();
      if (!valid(version) || invocation !== loadVersion) return;
      bases.value = [{ id: library.baseId, name: library.name }]; baseId.value = library.baseId;
    } else {
      const result = await request<{ id: number; name: string }[]>({ url: '/api/knowledge/bases' });
      if (valid(version) && invocation === loadVersion) bases.value = result;
    }
  }
  catch (cause) { if (valid(version) && invocation === loadVersion) error.value = cause instanceof Error ? cause.message : '知识库读取失败'; }
  finally { if (valid(version) && invocation === loadVersion) loading.value = false; }
}
async function save() {
  if (!props.open || !props.canSave || loading.value || !baseId.value || !bases.value.some(base => base.id === baseId.value)
    || !content.value.trim() || busy.value || saved.value || uncertain.value && !isVisitor.value) return;
  const target = baseId.value, version = contextVersion, visitor = isVisitor.value;
  busy.value = true; error.value = '';
  try {
    const file = new File([content.value], `event-${props.ticketId}-retrospective.md`, { type: 'text/markdown' });
    let id: number;
    if (visitor) id = await visitorKnowledgeApi.upload(file, props.ticketId);
    else {
      const data = new FormData(); data.append('file', file);
      data.append('ticketId', String(props.ticketId)); data.append('visibility', 'PRIVATE');
      id = await request<number>({ method: 'POST', url: `/api/knowledge/bases/${target}/documents`, data });
    }
    if (!valid(version)) return;
    uncertain.value = false; saved.value = { id, baseId: target }; emit('saved', id);
  } catch (cause) {
    if (!valid(version)) return;
    uncertain.value = !(cause instanceof ApiError && (((cause.status || 0) >= 400 && (cause.status || 0) < 500) || [40000, 40300, 40400, 40900].includes(cause.code || 0)));
    error.value = cause instanceof Error ? cause.message : '保存失败';
  } finally { if (valid(version)) busy.value = false; }
}
watch(() => [auth.identity, auth.user?.userId, auth.isDemo, props.ticketId], () => {
  contextVersion++; loadVersion++; bases.value = []; baseId.value = undefined;
  saved.value = undefined; uncertain.value = false; busy.value = false; error.value = ''; content.value = props.initialContent;
  if (props.open) void loadBases();
}, { flush: 'sync' });
watch(() => props.open, (open) => {
  if (!open) return;
  if (!saved.value && !uncertain.value) content.value = props.initialContent;
  if (!bases.value.length) void loadBases();
}, { immediate: true });
</script>

<template>
  <BaseModal v-if="open" title="事件知识草稿" :description="ticketTitle" wide @close="!busy && emit('close')">
    <div class="event-knowledge-draft">
      <p v-if="isVisitor">核对复盘内容后保存到我的体验库，继续体验解析、切片和知识问答。</p>
      <p v-else>先选择保存位置并核对内容，之后在文档页继续解析和审核。</p>
      <InlineError v-if="error" :message="error" />
      <FormField v-if="isVisitor" label="保存位置"><p>{{ loading ? '正在读取我的体验库…' : bases[0]?.name || '体验库尚未加载' }}</p></FormField>
      <FormField v-else label="目标知识库"><select v-model="baseId" :disabled="loading || busy || !!saved || uncertain" aria-label="事件草稿目标知识库"><option :value="undefined" disabled>{{ loading ? '正在读取知识库…' : '请选择知识库' }}</option><option v-for="base in bases" :key="base.id" :value="base.id">{{ base.name }}</option></select></FormField>
      <p v-if="isVisitor">仅本人可见，未发布到公共知识库。体验到期或主动结束后不可访问，并会自动清理。</p>
      <p v-else-if="!loading && !bases.length">尚无可选知识库。<RouterLink to="/knowledge">前往创建知识库</RouterLink></p>
      <button v-if="error && !bases.length" class="button secondary" :disabled="loading" @click="loadBases">重新读取</button>
      <FormField label="复盘内容"><textarea v-model="content" rows="13" :disabled="busy || !!saved || uncertain" /></FormField>
      <p v-if="saved && isVisitor" class="inline-success" role="status">已关联体验文档 #{{ saved.id }}。同一事件重复保存会返回已有文档，不会覆盖原正文。</p>
      <p v-else-if="saved" class="inline-success" role="status">已保存文档 #{{ saved.id }} · {{ bases.find(base => base.id === saved?.baseId)?.name }}。当前为私有草稿，尚未发布。</p>
      <p v-else-if="uncertain && isVisitor" role="status">保存结果尚未确认，可以重试确认。同一事件若已保存，会返回原文档，不会重复创建或覆盖正文。</p>
      <p v-else-if="uncertain" role="status">保存结果尚未确认，请先在事件关联文档中核对，避免重复创建。</p>
      <p v-else-if="!canSave">{{ isVisitor ? '仅可保存本人已解决的隔离演练复盘。' : '保存需要事件创建人、处理人或管理员权限。' }}</p>
      <div class="form-actions"><button class="button secondary" :disabled="busy" @click="emit('close')">关闭</button><RouterLink v-if="saved" class="button primary" :to="documentLink">打开{{ isVisitor ? '体验文档' : '草稿' }}，继续解析 →</RouterLink><button v-else-if="canSave && (!uncertain || isVisitor)" class="button primary" :disabled="loading || busy || !baseId || !content.trim()" @click="save">{{ busy ? '保存中…' : uncertain ? '重试确认保存' : isVisitor ? '保存到我的体验库' : '保存私有草稿' }}</button></div>
    </div>
  </BaseModal>
</template>

<style scoped>
.event-knowledge-draft { display: grid; gap: 16px; }.event-knowledge-draft p { margin: 0; color: var(--oa-text-secondary); font-size: 13px; line-height: 1.6; }.event-knowledge-draft textarea { resize: vertical; min-height: 220px; }
</style>
