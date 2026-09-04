<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { BookCheck, BookOpen, FileText, Layers3, Play, Plus, Trash2, Upload } from "@lucide/vue";
import { request } from "@/api/http";
import BaseModal from "@/components/BaseModal.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PageHeader from "@/components/PageHeader.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import TableSurface from "@/components/TableSurface.vue";
import { formatDateTime, formatShortDateTime } from "@/utils/datetime";

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
  documents.value = await request<KnowledgeDocument[]>({
    url: `/api/knowledge/bases/${selectedBaseId.value}/documents`,
  });
}
async function selectBase(id: number) {
  selectedBaseId.value = id;
  error.value = "";
  await loadDocuments();
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
  if (!selectedFile.value || !selectedBaseId.value) return;
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
    await loadDocuments();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "提交审核失败";
  } finally {
    busy.value = "";
  }
}
onMounted(load);
</script>

<template>
  <div class="stack-page knowledge-page">
<PageHeader title="知识库" description="管理用于运维检索与智能问答的可信知识" />
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" /><p v-if="success" class="inline-success">{{ success }}</p>
    <section class="knowledge-workspace">
    <aside class="panel knowledge-sidebar">
      <header class="panel-header"><div><h3>知识库</h3><p>{{ bases.length }} 个</p></div><button class="icon-button" title="新建知识库" @click="createOpen = true"><Plus :size="16" /></button></header>
      <button v-for="base in bases" :key="base.id" class="knowledge-base-item" :class="{ active: base.id === selectedBaseId }" @click="selectBase(base.id)">
        <BookOpen :size="18" /><span><strong>{{ base.name }}</strong><small>{{ base.description || "暂无用途说明" }}</small></span>
      </button>
    </aside>

    <main class="panel knowledge-main">
      <header class="panel-header">
        <div><h3>{{ selectedBase?.name || "请选择知识库" }}</h3><p>{{ selectedBase?.description || "创建知识库后，需要上传、解析并索引文档才可用于 RAG。" }}</p><small v-if="selectedBase">{{ documents.length }} 个文档 · {{ indexedCount }} 已索引</small></div>
      </header>
      <div v-if="selectedBase" class="upload-strip upload-dropzone compact-upload" :class="{ 'drag-active': dragActive }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="chooseFile($event.dataTransfer?.files?.[0])">
        <label><Upload :size="19" /><span>{{ selectedFile?.name || "拖拽文件到这里，或点击选择文件" }}</span><input type="file" accept=".pdf,.docx,.txt,.md,.markdown" @change="chooseFile(($event.target as HTMLInputElement).files?.[0])" /><small>最大 10 MB；上传后仍需解析和建立向量索引</small></label>
        <button class="button primary small" :disabled="!selectedFile || busy === 'upload'" @click="upload">{{ busy === "upload" ? "上传中…" : "上传文档" }}</button>
      </div>
      <TableSurface v-else-if="documents.length" class="knowledge-document-table"><table><thead><tr><th>文档</th><th>解析</th><th>审核</th><th>索引</th><th>更新时间</th><th></th></tr></thead><tbody><tr v-for="document in documents" :key="document.id"><td><span class="table-title"><strong>{{ document.original_name }}</strong><span>{{ (document.file_size / 1024).toFixed(1) }} KB · {{ document.chunk_count || 0 }} Chunks</span></span></td><td><StatusBadge :value="document.status === 'INDEXED' ? 'PARSED' : document.status" /></td><td><StatusBadge :value="document.review_status || 'DRAFT'" /></td><td><StatusBadge :value="document.index_status || (document.status === 'INDEXED' ? 'SUCCESS' : 'PENDING')" /></td><td><time :title="formatDateTime(document.create_time)">{{ formatShortDateTime(document.create_time) }}</time></td><td><div class="row-actions reveal-on-row"><button class="icon-button" title="解析并建立向量索引" :disabled="busy === `parse-${document.id}`" @click="parse(document)"><Play :size="16" /></button><button v-if="['PARSED','INDEXED'].includes(document.status) && ['DRAFT','REJECTED'].includes(document.review_status || 'DRAFT')" class="icon-button" title="提交知识审核" :disabled="busy === `review-${document.id}`" @click="submitReview(document)"><BookCheck :size="16" /></button><button class="icon-button" title="查看文本切片" :disabled="!['PARSED','INDEXED'].includes(document.status)" @click="showChunks(document)"><Layers3 :size="16" /></button><button class="icon-button" title="删除文档" @click="remove(document)"><Trash2 :size="16" /></button></div></td></tr></tbody></table></TableSurface>
    </main>
    </section>
    <BaseModal v-if="createOpen" title="新建知识库" description="说明知识库的业务域、维护人和用途" @close="createOpen = false"><form class="form-grid" @submit.prevent="create"><label>知识库名称<input v-model.trim="name" required maxlength="128" placeholder="例如：生产故障处理手册" /></label><label class="full">用途说明<textarea v-model.trim="description" maxlength="500" rows="4" placeholder="说明业务域、维护人和使用范围" /></label><div class="form-actions full"><button type="button" class="button secondary" @click="createOpen = false">取消</button><button class="button primary" :disabled="busy === 'create' || !name">{{ busy === "create" ? "创建中…" : "创建并进入" }}</button></div></form></BaseModal>
    <BaseModal v-if="chunkDocument" :title="`${chunkDocument.original_name} · 文本切片`" wide @close="chunkDocument = undefined">
      <div class="chunk-list"><article v-for="chunk in chunks" :key="String(chunk.id)"><header><strong>Chunk {{ chunk.chunk_index }}</strong><span>{{ chunk.token_count || 0 }} tokens</span></header><p>{{ chunk.content }}</p></article></div>
    </BaseModal>
  </div>
</template>
