<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { BookCheck, BookOpen, FileText, Layers3, Play, Plus, Trash2, Upload } from "@lucide/vue";
import { request } from "@/api/http";
import BaseModal from "@/components/BaseModal.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PageHeader from "@/components/PageHeader.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import TableSurface from "@/components/TableSurface.vue";
import { formatDateTime, formatShortDateTime } from "@/utils/datetime";
import GuidedEmptyState from "@/components/experience/GuidedEmptyState.vue";
import ActionButton from "@/components/feedback/ActionButton.vue";
import { usePageFeedback } from "@/composables/usePageFeedback";

interface KnowledgeBase {
  id: number;
  name: string;
  description?: string;
}
interface KnowledgeDocument {
  id: number;
  original_name: string;
  file_type: string;
  file_size: number;
  status: string;
  review_status: string;
  index_status?: string;
  chunk_count?: number;
  embedding_model?: string;
  version?: number;
  parse_error?: string;
  create_time: string;
}

const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
const bases = ref<KnowledgeBase[]>([]);
const documents = ref<KnowledgeDocument[]>([]);
const selectedBaseId = ref<number>();
const selectedFile = ref<File>();
const dragActive = ref(false);
const chunks = ref<Record<string, unknown>[]>([]);
const chunkDocument = ref<KnowledgeDocument>();
const name = ref("");
const description = ref("");
const error = ref("");
const toast = usePageFeedback(error, load);
const route = useRoute();
const fileInput = ref<HTMLInputElement>();
const loadingDocuments = ref(false);
function guideUpload() { if (!selectedBaseId.value) createOpen.value = true; else fileInput.value?.click(); }
async function focusUpload() { if (route.query.upload === '1') { await nextTick(); if (selectedBaseId.value) { fileInput.value?.closest('.knowledge-upload')?.scrollIntoView({ block: 'center' }); fileInput.value?.focus(); } else createOpen.value = true; } }
watch(() => route.query.upload, focusUpload);
const success = ref("");
const busy = ref("");
const createOpen = ref(false);
const selectedBase = computed(() => bases.value.find((item) => item.id === selectedBaseId.value));
const indexedCount = computed(() => documents.value.filter((item) => item.index_status === "SUCCESS" || item.status === "INDEXED").length);

async function load() {
  try {
    bases.value = await request<KnowledgeBase[]>({ url: "/api/knowledge/bases" });
    if (!selectedBaseId.value && bases.value.length) selectedBaseId.value = bases.value[0].id;
    await loadDocuments();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  }
}
async function loadDocuments() {
  if (!selectedBaseId.value) {
    documents.value = [];
    return;
  }
  const baseId = selectedBaseId.value;
  loadingDocuments.value = true;
  try {
    const rows = await request<KnowledgeDocument[]>({ url: `/api/knowledge/bases/${baseId}/documents` });
    if (selectedBaseId.value === baseId) documents.value = rows;
  } finally { loadingDocuments.value = false; }
}
async function selectBase(id: number) {
  selectedBaseId.value = id;
  error.value = "";
  documents.value = [];
  try { await loadDocuments(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "文档加载失败"; }
}
async function create() {
  if (!name.value.trim() || busy.value) return;
  busy.value = "create";
  error.value = "";
  try {
    const id = await request<number>({
      method: "POST",
      url: "/api/knowledge/bases",
      data: { name: name.value.trim(), description: description.value.trim() },
    });
    name.value = "";
    description.value = "";
    createOpen.value = false;
    success.value = "知识库已创建，请继续上传文档并执行解析入库";
    toast.show("知识库已创建，可以上传文档了");
    selectedBaseId.value = id;
    await load();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "创建失败";
  } finally {
    busy.value = "";
  }
}
function chooseFile(file?: File) {
  dragActive.value = false;
  if (!file) return;
  if (file.size > MAX_UPLOAD_BYTES) {
    error.value = "文件不能超过 10 MB";
    return;
  }
  selectedFile.value = file;
  error.value = "";
}
async function upload() {
  if (!selectedFile.value || !selectedBaseId.value || busy.value) return;
  busy.value = "upload";
  const form = new FormData();
  form.append("file", selectedFile.value);
  try {
    await request<number>({
      method: "POST",
      url: `/api/knowledge/bases/${selectedBaseId.value}/documents`,
      data: form,
    });
    selectedFile.value = undefined;
    success.value = "上传成功，请点击解析按钮完成切片和向量索引";
    toast.show("文档已上传，请继续解析");
    await loadDocuments();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "上传失败";
  } finally {
    busy.value = "";
  }
}
async function parse(document: KnowledgeDocument) {
  busy.value = `parse-${document.id}`;
  try {
    const taskId = await request<number>({ method: "POST", url: `/api/knowledge/documents/${document.id}/parse` });
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const task = await request<Record<string, unknown>>({ url: `/api/knowledge/parse-tasks/${taskId}` });
      if (["SUCCESS", "FAILED"].includes(String(task.status))) break;
      await new Promise((resolve) => window.setTimeout(resolve, 1500));
    }
    await loadDocuments();
    const parsed = documents.value.find(row => row.id === document.id);
    if (parsed?.status === "FAILED") throw new Error(parsed.parse_error || "文档解析失败，请检查文件内容");
    toast.show(["PARSED", "INDEXED"].includes(parsed?.status || "") ? "文档解析完成，可检查切片并提交审核" : "解析仍在处理中，请稍后刷新查看", "info");
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "解析失败";
  } finally {
    busy.value = "";
  }
}
async function showChunks(document: KnowledgeDocument) {
  chunks.value = await request<Record<string, unknown>[]>({ url: `/api/knowledge/documents/${document.id}/chunks` });
  chunkDocument.value = document;
}
async function remove(document: KnowledgeDocument) {
  if (!confirm(`确认删除文档“${document.original_name}”吗？删除后将同步补偿 Elasticsearch 索引。`)) return;
  busy.value = `delete-${document.id}`;
  try {
    await request({ method: "DELETE", url: `/api/knowledge/documents/${document.id}` });
    await loadDocuments();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "删除失败";
  } finally {
    busy.value = "";
  }
}
async function submitReview(document: KnowledgeDocument) {
  busy.value = `review-${document.id}`;
  try {
    await request({
      method: "POST",
      url: `/api/knowledge/documents/${document.id}/submit-review`,
    });
    success.value = "已提交知识审核；发布前不会进入生产 RAG 检索范围";
    toast.show("知识文档已提交审核");
    await loadDocuments();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "提交审核失败";
  } finally {
    busy.value = "";
  }
}
onMounted(async () => { await load(); await focusUpload(); });
</script>

<template>
  <div class="stack-page knowledge-page">
    <PageHeader title="知识库" description="把团队经验，整理成可以查找与复用的知识">
      <template #actions><button class="button secondary" @click="createOpen = true"><Plus :size="16" />新建知识库</button></template>
    </PageHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" /><p v-if="success" class="inline-success">{{ success }}</p>
    <section class="knowledge-workspace">
    <aside class="panel knowledge-sidebar">
      <header class="panel-header"><div><h3>知识目录</h3><p>{{ bases.length }} 个知识库</p></div><BookOpen :size="19" /></header>
      <button v-for="base in bases" :key="base.id" class="knowledge-base-item" :class="{ active: base.id === selectedBaseId }" :aria-pressed="base.id === selectedBaseId" @click="selectBase(base.id)">
        <BookOpen :size="18" /><span><strong>{{ base.name }}</strong><small>{{ base.description || "暂无用途说明" }}</small></span>
      </button>
      <p class="knowledge-sidebar-note">按业务域整理文档，让每次查找更有方向。</p>
    </aside>

    <main class="panel knowledge-main">
      <header class="panel-header">
        <div><h3>{{ selectedBase?.name || "请选择知识库" }}</h3><p>{{ selectedBase?.description || "创建知识库后，上传文档并完成解析、审核与索引。" }}</p></div>
        <div v-if="selectedBase" class="knowledge-metrics"><span><FileText :size="15" /><strong>{{ loadingDocuments ? '—' : documents.length }}</strong>文档</span><span><Layers3 :size="15" /><strong>{{ loadingDocuments ? '—' : indexedCount }}</strong>已索引</span></div>
      </header>
      <div v-if="selectedBase" class="knowledge-upload" :class="{ 'drag-active': dragActive }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="chooseFile($event.dataTransfer?.files?.[0])">
        <label><Upload :size="21" /><span>{{ selectedFile?.name || "添加一份知识，让经验被留下" }}<small>{{ selectedFile ? '已选择文件，点击上传按钮继续' : '拖拽或点击选择 · PDF / DOCX / TXT / Markdown · 最大 10 MB' }}</small></span><input ref="fileInput" type="file" aria-label="选择知识文档" accept=".pdf,.docx,.txt,.md,.markdown" @change="chooseFile(($event.target as HTMLInputElement).files?.[0])" /></label>
        <ActionButton class="primary small" :disabled="!selectedFile || busy === 'upload'" :loading="busy === 'upload'" loading-text="上传中…" @click="upload">上传文档</ActionButton>
      </div>
      <p v-if="selectedBase" class="knowledge-publish-note"><BookCheck :size="14" />上传后请完成解析与审核，发布并建立索引后可用于知识检索。</p>
      <TableSurface v-if="selectedBase && documents.length" class="knowledge-document-table"><table><thead><tr><th>文档</th><th>解析</th><th>审核</th><th>索引</th><th>更新时间</th><th></th></tr></thead><tbody><tr v-for="document in documents" :key="document.id"><td><span class="table-title"><strong>{{ document.original_name }}</strong><span>{{ (document.file_size / 1024).toFixed(1) }} KB · {{ document.chunk_count || 0 }} Chunks</span></span></td><td><StatusBadge :value="document.status === 'INDEXED' ? 'PARSED' : document.status" /></td><td><StatusBadge :value="document.review_status || 'DRAFT'" /></td><td><StatusBadge :value="document.index_status || (document.status === 'INDEXED' ? 'SUCCESS' : 'PENDING')" /></td><td><time :title="formatDateTime(document.create_time)">{{ formatShortDateTime(document.create_time) }}</time></td><td><div class="row-actions reveal-on-row"><button class="icon-button" title="解析并建立向量索引" :disabled="busy === `parse-${document.id}`" @click="parse(document)"><Play :size="16" /></button><button v-if="['PARSED','INDEXED'].includes(document.status) && ['DRAFT','REJECTED'].includes(document.review_status || 'DRAFT')" class="icon-button" title="提交知识审核" :disabled="busy === `review-${document.id}`" @click="submitReview(document)"><BookCheck :size="16" /></button><button class="icon-button" title="查看文本切片" :disabled="!['PARSED','INDEXED'].includes(document.status)" @click="showChunks(document)"><Layers3 :size="16" /></button><button class="icon-button" title="删除文档" @click="remove(document)"><Trash2 :size="16" /></button></div></td></tr></tbody></table></TableSurface>
      <div v-else-if="loadingDocuments" class="loading-state">正在加载文档…</div>
      <GuidedEmptyState v-else kind="knowledge" :title="selectedBase ? '这个知识库还没有文档' : '尚未创建知识库'" :description="selectedBase ? '上传故障手册或操作规范，逐步整理成可复用的知识。' : '先按业务域创建知识库，再上传用于检索的文档。'" :steps="['上传', '解析切片', '审核发布', '检索引用']" :action="selectedBase ? '上传第一个文档' : '创建知识库'" @action="guideUpload" />
    </main>
    </section>
    <BaseModal v-if="createOpen" title="新建知识库" description="说明知识库的业务域、维护人和用途" @close="createOpen = false"><form class="form-grid" @submit.prevent="create"><label>知识库名称<input v-model.trim="name" required maxlength="128" placeholder="例如：生产故障处理手册" /></label><label class="full">用途说明<textarea v-model.trim="description" maxlength="500" rows="4" placeholder="说明业务域、维护人和使用范围" /></label><div class="form-actions full"><button type="button" class="button secondary" @click="createOpen = false">取消</button><ActionButton class="primary" :disabled="!name" :loading="busy === 'create'" loading-text="创建中…">创建并进入</ActionButton></div></form></BaseModal>
    <BaseModal v-if="chunkDocument" :title="`${chunkDocument.original_name} · 文本切片`" wide @close="chunkDocument = undefined">
      <div class="chunk-list"><article v-for="chunk in chunks" :key="String(chunk.id)"><header><strong>Chunk {{ chunk.chunk_index }}</strong><span>{{ chunk.token_count || 0 }} tokens</span></header><p>{{ chunk.content }}</p></article></div>
    </BaseModal>
  </div>
</template>
