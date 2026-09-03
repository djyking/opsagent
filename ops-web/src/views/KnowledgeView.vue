<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { BookOpen, FileText, Layers3, Play, Trash2, Upload } from "@lucide/vue";
import { request } from "@/api/http";
import BaseModal from "@/components/BaseModal.vue";
import StatusBadge from "@/components/StatusBadge.vue";

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
const selectedBase = computed(() => bases.value.find((item) => item.id === selectedBaseId.value));
const indexedCount = computed(() => documents.value.filter((item) => item.status === "INDEXED").length);

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
onMounted(load);
</script>

<template>
  <div class="knowledge-workspace">
    <aside class="panel knowledge-sidebar">
      <header class="panel-header"><div><span class="eyebrow">LIBRARIES</span><h3>知识库</h3></div><span class="panel-count">{{ bases.length }}</span></header>
      <button v-for="base in bases" :key="base.id" class="knowledge-base-item" :class="{ active: base.id === selectedBaseId }" @click="selectBase(base.id)">
        <BookOpen :size="18" /><span><strong>{{ base.name }}</strong><small>{{ base.description || "暂无用途说明" }}</small></span>
      </button>
      <form class="knowledge-create-card" @submit.prevent="create">
        <strong>创建新知识库</strong>
        <input v-model.trim="name" maxlength="128" placeholder="知识库名称" />
        <textarea v-model.trim="description" maxlength="500" rows="2" placeholder="说明业务域、维护人和用途" />
        <button class="button primary" :disabled="busy === 'create' || !name">{{ busy === "create" ? "创建中…" : "创建并进入" }}</button>
      </form>
    </aside>

    <main class="panel knowledge-main">
      <header class="panel-header">
        <div><span class="eyebrow">KNOWLEDGE OPERATIONS</span><h3>{{ selectedBase?.name || "请选择知识库" }}</h3><p>{{ selectedBase?.description || "创建知识库后，需要上传、解析并索引文档才可用于 RAG。" }}</p></div>
        <div class="knowledge-metrics"><span><strong>{{ documents.length }}</strong>文档</span><span><strong>{{ indexedCount }}</strong>已索引</span></div>
      </header>
      <p v-if="error" class="inline-error">{{ error }}</p><p v-if="success" class="inline-success">{{ success }}</p>
      <div v-if="selectedBase" class="upload-strip upload-dropzone" :class="{ 'drag-active': dragActive }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="chooseFile($event.dataTransfer?.files?.[0])">
        <label><Upload :size="19" /><span>{{ selectedFile?.name || "拖拽文件到这里，或点击选择文件" }}</span><input type="file" accept=".pdf,.docx,.txt,.md,.markdown" @change="chooseFile(($event.target as HTMLInputElement).files?.[0])" /><small>最大 10 MB；上传后仍需解析和建立向量索引</small></label>
        <button class="button primary small" :disabled="!selectedFile || busy === 'upload'" @click="upload">{{ busy === "upload" ? "上传中…" : "上传文档" }}</button>
      </div>
      <div v-if="selectedBase && !documents.length" class="empty-state"><FileText :size="32" /><strong>这个知识库还没有文档</strong><span>上传 SOP、故障复盘或运维手册后执行解析。</span></div>
      <div v-else class="document-list knowledge-document-list">
        <article v-for="document in documents" :key="document.id">
          <div class="file-icon">{{ document.file_type.toUpperCase() }}</div><div class="file-info"><strong>{{ document.original_name }}</strong><span>{{ (document.file_size / 1024).toFixed(1) }} KB · {{ new Date(document.create_time).toLocaleString("zh-CN") }}</span><p v-if="document.parse_error" class="file-error">{{ document.parse_error }}</p></div>
          <StatusBadge :value="document.status === 'INDEXED' ? 'SUCCESS' : document.status" />
          <div class="row-actions"><button class="icon-button" title="解析并建立向量索引" :disabled="busy === `parse-${document.id}`" @click="parse(document)"><Play :size="16" /></button><button class="icon-button" title="查看文本切片" :disabled="document.status !== 'INDEXED'" @click="showChunks(document)"><Layers3 :size="16" /></button><button class="icon-button danger" title="删除文档" @click="remove(document)"><Trash2 :size="16" /></button></div>
        </article>
      </div>
    </main>
    <BaseModal v-if="chunkDocument" :title="`${chunkDocument.original_name} · 文本切片`" wide @close="chunkDocument = undefined">
      <div class="chunk-list"><article v-for="chunk in chunks" :key="String(chunk.id)"><header><strong>Chunk {{ chunk.chunk_index }}</strong><span>{{ chunk.token_count || 0 }} tokens</span></header><p>{{ chunk.content }}</p></article></div>
    </BaseModal>
  </div>
</template>
