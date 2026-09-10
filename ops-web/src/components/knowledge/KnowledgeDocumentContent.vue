<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { request } from '@/api/http';
import { useAuthStore } from '@/stores/auth';
import { renderAnswer } from '@/utils/answer-markdown';

const props = defineProps<{ documentId: number; version: number }>();
interface ContentPage {
  documentId: number; version: number; status: string; source: string; format: string;
  text: string; pageNum: number; totalPages: number; totalCharacters: number; notice: string;
}
const auth = useAuthStore();
const page = ref<ContentPage>(), loading = ref(false), error = ref(''), mode = ref<'preview' | 'text'>('preview');
const markdown = computed(() => page.value?.format === 'MARKDOWN' && page.value.totalPages === 1);
const RenderedBody = () => renderAnswer(page.value?.text || '', { stripChunkMarkers: false });
let generation = 0, alive = true, controller: AbortController | undefined;
let actor = auth.identity ?? auth.user?.userId;
function clear() { generation++; controller?.abort(); page.value = undefined; error.value = ''; loading.value = false; }
async function load(pageNum = 1) {
  const currentActor = auth.identity ?? auth.user?.userId;
  if (!alive || currentActor == null || currentActor !== actor) return;
  const current = ++generation;
  controller?.abort(); controller = new AbortController();
  const id = props.documentId, version = props.version;
  loading.value = true; error.value = ''; page.value = undefined;
  try {
    const result = await request<ContentPage>({ url: `/api/knowledge/documents/${id}/content`, params: { pageNum, version }, signal: controller.signal, expectedIdentity: auth.identity ?? undefined });
    if (!alive || current !== generation || (auth.identity ?? auth.user?.userId) !== currentActor || props.documentId !== id || props.version !== version) return;
    if (result.documentId !== id || result.version !== version) throw new Error('文档版本已变化，请关闭详情并刷新后重试');
    page.value = result;
  } catch (cause) {
    if (alive && current === generation) error.value = cause instanceof Error ? cause.message : '正文加载失败，请重试';
  } finally { if (alive && current === generation) loading.value = false; }
}
watch(() => [props.documentId, props.version], () => { clear(); mode.value = 'preview'; void load(); }, { immediate: true });
watch(() => [auth.identity, auth.user?.userId], () => {
  const current = auth.identity ?? auth.user?.userId;
  if (current !== actor) { clear(); error.value = '登录身份已变化，请重新打开文档'; }
});
onBeforeUnmount(() => { alive = false; clear(); });
</script>

<template>
  <section class="document-content" aria-label="文档正文">
    <header class="document-content-header"><h3>文档正文</h3><div v-if="page?.status === 'AVAILABLE' && markdown" class="document-content-modes" aria-label="正文显示方式"><button class="text-button" :aria-pressed="mode === 'preview'" @click="mode = 'preview'">排版预览</button><button class="text-button" :aria-pressed="mode === 'text'" @click="mode = 'text'">原始文本</button></div></header>
    <p v-if="loading" class="document-content-state" role="status">正在读取正文…</p>
    <div v-else-if="error" class="document-content-error" role="alert"><p>{{ error }}</p><button class="button secondary small" @click="load()">重新加载</button></div>
    <template v-else-if="page">
      <template v-if="page.status === 'AVAILABLE'">
        <p class="document-content-notice">{{ page.source === 'ORIGINAL_FILE_TEXT' ? '上传原文件正文' : '原文件提取正文' }} · v{{ page.version }} · {{ page.totalCharacters.toLocaleString() }} 字符<template v-if="page.totalPages > 1"> · 长文按原始文本分页显示</template></p>
        <div class="document-content-reader" tabindex="0" role="region" aria-label="正文内容"><RenderedBody v-if="markdown && mode === 'preview'" /><pre v-else>{{ page.text }}</pre></div>
        <footer v-if="page.totalPages > 1" class="document-content-pages"><button class="button secondary small" :disabled="page.pageNum <= 1" @click="load(page.pageNum - 1)">上一页</button><span>第 {{ page.pageNum }} / {{ page.totalPages }} 页</span><button class="button secondary small" :disabled="page.pageNum >= page.totalPages" @click="load(page.pageNum + 1)">下一页</button></footer>
        <p v-if="page.source === 'EXTRACTED_FILE_TEXT'" class="document-content-notice">{{ page.notice }}</p>
      </template>
      <div v-else class="document-content-state"><p>{{ page.notice }}</p><button class="button secondary small" @click="load()">重新读取正文</button></div>
    </template>
  </section>
</template>

<style scoped>
.document-content{min-width:0;border-top:1px solid var(--oa-border);padding-top:18px}.document-content-header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:10px}.document-content-header h3{margin:0;font-size:15px}.document-content-modes{display:flex;gap:14px}.document-content-modes button{font-size:12px;color:var(--oa-text-muted)}.document-content-modes button[aria-pressed=true]{color:#2563eb;font-weight:600}.document-content-notice{font-size:12px;color:var(--oa-text-muted);line-height:1.7;margin:0 0 12px}.document-content-reader{max-height:52vh;min-height:100px;overflow:auto;border:1px solid var(--oa-border);border-radius:8px;padding:18px;background:var(--oa-bg-subtle);overflow-wrap:anywhere}.document-content-reader pre{font:13px/1.9 var(--oa-font-body,system-ui);white-space:pre-wrap;margin:0}.document-content-reader :deep(.answer-content){font-size:14px;line-height:1.8;min-width:0}.document-content-reader :deep(pre){white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;background:#eef2f7;border-radius:6px}.document-content-reader :deep(h4){font-size:15px;margin:18px 0 10px}.document-content-reader :deep(h4:first-child){margin-top:0}.document-content-reader :deep(p){white-space:pre-wrap}.document-content-reader :deep(a){color:#2563eb}.document-content-reader :deep(.answer-table-scroll){overflow:auto}.document-content-reader :deep(table){border-collapse:collapse;min-width:100%}.document-content-reader :deep(td),.document-content-reader :deep(th){border:1px solid var(--oa-border);padding:6px 10px;text-align:left}.document-content-state{padding:20px 0;font-size:13px;color:var(--oa-text-muted);line-height:1.8}.document-content-error{color:#b91c1c;font-size:13px}.document-content-pages{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:12px;font-size:12px;color:var(--oa-text-muted)}
@media(max-width:600px){.document-content-reader{padding:12px;max-height:55vh}.document-content-pages{gap:6px}}
</style>
