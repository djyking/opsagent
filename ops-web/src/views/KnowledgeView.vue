<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ArrowLeft, ArrowUpRight, BookCheck, BookOpen, Database, FileText, Layers3, Play, Plus, Trash2, Upload } from "@lucide/vue";
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
import { useAuthStore } from "@/stores/auth";
import KnowledgeDocumentRevision from '@/components/knowledge/KnowledgeDocumentRevision.vue';
import KnowledgeRetrievalCheck from '@/components/knowledge/KnowledgeRetrievalCheck.vue';
import KnowledgeDocumentContent from '@/components/knowledge/KnowledgeDocumentContent.vue';
import VisitorKnowledgeLibrary from '@/components/knowledge/VisitorKnowledgeLibrary.vue';
import { visitorKnowledgeApi } from '@/api/visitor-knowledge';
import { knowledgeStage } from '@/utils/knowledge-stage';
import '@/styles/pages/knowledge-workspace.css';

const auth = useAuthStore();
const knowledgeScope = ref<'public' | 'experience'>('public');
let actorEpoch = 0, documentRead = 0, chunkRead = 0;
let routeSelectionPending = true;
onBeforeUnmount(() => { actorEpoch++; documentRead++; chunkRead++; });

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
  version: number;
  parse_error?: string;
  review_comment?: string;
  ticket_id?: number;
  create_by?: number;
  create_time: string;
}

const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
const bases = ref<KnowledgeBase[]>([]);
const baseKeyword = ref('');
const visibleBases = computed(() => bases.value.filter(base => `${base.name} ${base.description || ''}`.toLowerCase().includes(baseKeyword.value.trim().toLowerCase())));
const mobileDocuments = ref(false);
const documents = ref<KnowledgeDocument[]>([]);
const selectedBaseId = ref<number>();
const selectedFile = ref<File>();
const uploadOpen = ref(false);
const detailDocument = ref<KnowledgeDocument>();
const revisionDocument = ref<KnowledgeDocument>();
function canEdit(document: KnowledgeDocument) { return !auth.isDemo && (auth.isAdmin || document.create_by === auth.user?.userId) && ['DRAFT', 'REJECTED'].includes(document.review_status); }
function reviewLink(document: KnowledgeDocument) { return { path: '/knowledge/review', query: { documentId: document.id } }; }
async function revisionSaved() { revisionDocument.value = undefined; await loadDocuments(); toast.show('已保存新版本，请重新解析后提交审核'); }
const keyword = ref('');
const documentStage = ref('');
const visibleDocuments = computed(() => documents.value.filter(document => document.original_name.toLowerCase().includes(keyword.value.trim().toLowerCase()) && (!documentStage.value || knowledgeStage(document).label === documentStage.value)));
const stageOptions = computed(() => [...new Set(documents.value.map(document => knowledgeStage(document).label))]);
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
function guideUpload() { if (!selectedBaseId.value) createOpen.value = true; else uploadOpen.value = true; }
async function focusUpload() { if (route.query.upload === '1') { await nextTick(); guideUpload(); } }
watch(() => route.query.upload, focusUpload);
const success = ref("");
const busy = ref("");
const createOpen = ref(false);
const selectedBase = computed(() => bases.value.find((item) => item.id === selectedBaseId.value));
const indexedCount = computed(() => documents.value.filter((item) => knowledgeStage(item).label === "可检索").length);

async function load() {
  const epoch = actorEpoch;
  const followRoute = routeSelectionPending;
  const requestedBase = Number(route.query.baseId);
  try {
    const rows = await request<KnowledgeBase[]>({ url: "/api/knowledge/bases" });
    if (epoch !== actorEpoch) return; bases.value = rows;
    if (followRoute && requestedBase && bases.value.some(base => base.id === requestedBase)) selectedBaseId.value = requestedBase;
    if (!selectedBaseId.value && bases.value.length) selectedBaseId.value = bases.value[0].id;
    await loadDocuments();
    if (followRoute) { routeSelectionPending = false; await focusDocument(); }
  } catch (cause) {
    if (epoch === actorEpoch) error.value = cause instanceof Error ? cause.message : "加载失败";
  }
}
async function loadDocuments() {
  if (!selectedBaseId.value) {
    documents.value = [];
    return;
  }
  const baseId = selectedBaseId.value, epoch = actorEpoch, own = ++documentRead;
  loadingDocuments.value = true;
  try {
    const rows = await request<KnowledgeDocument[]>({ url: `/api/knowledge/bases/${baseId}/documents` });
    if (selectedBaseId.value === baseId && epoch === actorEpoch && own === documentRead) { documents.value = rows; if (detailDocument.value) detailDocument.value = rows.find(row => row.id === detailDocument.value?.id); }
  } finally { if (epoch === actorEpoch && own === documentRead) loadingDocuments.value = false; }
}
async function selectBase(id: number) {
  mobileDocuments.value = true;
  selectedBaseId.value = id; detailDocument.value = undefined; chunkDocument.value = undefined; chunks.value = []; chunkRead++;
  error.value = "";
  documents.value = [];
  try { await loadDocuments(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "文档加载失败"; }
}
async function focusDocument() {
  const id = Number(route.query.documentId);
  if (!id) return;
  const epoch = actorEpoch;
  const document = documents.value.find(row => row.id === id);
  if (document) { knowledgeScope.value = 'public'; detailDocument.value = document; mobileDocuments.value = true; error.value = ''; return; }
  if (auth.isDemo) {
    const experience = await visitorKnowledgeApi.get();
    if (epoch !== actorEpoch || Number(route.query.documentId) !== id) return;
    if (experience.documents.some(row => row.id === id)) { knowledgeScope.value = 'experience'; error.value = ''; return; }
  }
  // A citation contains a document ID, so locate it only within already-authorized libraries.
  for (const base of bases.value.filter(base => base.id !== selectedBaseId.value)) {
    const rows = await request<KnowledgeDocument[]>({ url: `/api/knowledge/bases/${base.id}/documents` });
    if (epoch !== actorEpoch || Number(route.query.documentId) !== id) return;
    const found = rows.find(row => row.id === id);
    if (found) { selectedBaseId.value = base.id; documents.value = rows; knowledgeScope.value = 'public'; detailDocument.value = found; mobileDocuments.value = true; error.value = ''; return; }
  }
  knowledgeScope.value = 'public'; error.value = '未找到指定文档，文档可能已过期、删除或当前身份无权访问';
}
watch(() => [route.query.baseId, route.query.documentId], () => { routeSelectionPending = true; void load(); });
async function create() {
  const epoch = actorEpoch;
  if (busy.value || auth.isDemo) return;
  if (!name.value.trim() || busy.value) return;
  busy.value = "create";
  error.value = "";
  try {
    const id = await request<number>({
      method: "POST",
      url: "/api/knowledge/bases",
      data: { name: name.value.trim(), description: description.value.trim() },
    });
    if (epoch !== actorEpoch) return;
    name.value = "";
    description.value = "";
    createOpen.value = false;
    success.value = "知识库已创建，请继续上传文档并执行解析入库";
    toast.show("知识库已创建，可以上传文档了");
    selectedBaseId.value = id;
    await load();
  } catch (cause) {
    if (epoch === actorEpoch) error.value = cause instanceof Error ? cause.message : "创建失败";
  } finally {
    if (epoch === actorEpoch) busy.value = "";
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
  const epoch = actorEpoch;
  if (busy.value || auth.isDemo) return;
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
    if (epoch !== actorEpoch) return;
    selectedFile.value = undefined;
    success.value = "上传成功，请解析正文并提交审核；审核发布与索引分别核验。";
    uploadOpen.value = false;
    toast.show("文档已上传，请继续解析");
    if (epoch !== actorEpoch) return;
    await loadDocuments();
    if (epoch !== actorEpoch) return;
  } catch (cause) {
    if (epoch === actorEpoch) error.value = cause instanceof Error ? cause.message : "上传失败";
  } finally {
    if (epoch === actorEpoch) busy.value = "";
  }
}
async function parse(document: KnowledgeDocument) {
  const epoch = actorEpoch;
  if (busy.value || auth.isDemo) return;
  busy.value = `parse-${document.id}`;
  try {
    const taskId = await request<number>({ method: "POST", url: `/api/knowledge/documents/${document.id}/parse` });
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const task = await request<Record<string, unknown>>({ url: `/api/knowledge/parse-tasks/${taskId}` });
      if (epoch !== actorEpoch) return;
      if (["SUCCESS", "FAILED"].includes(String(task.status))) break;
      await new Promise((resolve) => window.setTimeout(resolve, 1500));
    }
    if (epoch !== actorEpoch) return;
    await loadDocuments();
    if (epoch !== actorEpoch) return;
    const parsed = documents.value.find(row => row.id === document.id);
    if (parsed?.status === "FAILED") throw new Error(parsed.parse_error || "文档解析失败，请检查文件内容");
    toast.show(["PARSED", "INDEXED"].includes(parsed?.status || "") ? "文档解析完成，可检查切片并提交审核" : "解析仍在处理中，请稍后刷新查看", "info");
  } catch (cause) {
    if (epoch === actorEpoch) error.value = cause instanceof Error ? cause.message : "解析失败";
  } finally {
    if (epoch === actorEpoch) busy.value = "";
  }
}
async function showChunks(document: KnowledgeDocument) {
  const epoch = actorEpoch, own = ++chunkRead;
  try { const rows = await request<Record<string, unknown>[]>({ url: `/api/knowledge/documents/${document.id}/chunks` }); if (epoch !== actorEpoch || own !== chunkRead) return; chunks.value = rows; detailDocument.value = undefined; chunkDocument.value = document; }
  catch (cause) { if (epoch === actorEpoch && own === chunkRead) error.value = cause instanceof Error ? cause.message : '正文切片读取失败'; }
}
async function remove(document: KnowledgeDocument) {
  const epoch = actorEpoch;
  if (busy.value || auth.isDemo) return;
  if (!confirm(`确认删除文档“${document.original_name}”吗？删除后将同步补偿 Elasticsearch 索引。`)) return;
  busy.value = `delete-${document.id}`;
  try {
    await request({ method: "DELETE", url: `/api/knowledge/documents/${document.id}` });
    if (epoch !== actorEpoch) return;
    await loadDocuments();
    if (epoch !== actorEpoch) return;
  } catch (cause) {
    if (epoch === actorEpoch) error.value = cause instanceof Error ? cause.message : "删除失败";
  } finally {
    if (epoch === actorEpoch) busy.value = "";
  }
}
async function submitReview(document: KnowledgeDocument) {
  const epoch = actorEpoch;
  if (busy.value || auth.isDemo) return;
  busy.value = `review-${document.id}`;
  try {
    await request({
      method: "POST",
      url: `/api/knowledge/documents/${document.id}/submit-review`,
    });
    if (epoch !== actorEpoch) return;
    success.value = "已提交知识审核；发布前不会进入生产 RAG 检索范围";
    toast.show("知识文档已提交审核");
    if (epoch !== actorEpoch) return;
    await loadDocuments();
    if (epoch !== actorEpoch) return;
  } catch (cause) {
    if (epoch === actorEpoch) error.value = cause instanceof Error ? cause.message : "提交审核失败";
  } finally {
    if (epoch === actorEpoch) busy.value = "";
  }
}
watch(() => auth.user?.userId, () => { actorEpoch++; documentRead++; chunkRead++; bases.value = []; documents.value = []; selectedBaseId.value = undefined; detailDocument.value = undefined; revisionDocument.value = undefined; chunkDocument.value = undefined; chunks.value = []; uploadOpen.value = false; selectedFile.value = undefined; createOpen.value = false; busy.value = ''; error.value = ''; if (auth.user) void load(); });
onMounted(async () => { await load(); await focusUpload(); });
</script>

<template>
  <div class="stack-page knowledge-page">
    <PageHeader :icon="BookOpen" title="知识与经验">
      <template #actions><button v-if="!auth.isDemo" class="button secondary" @click="createOpen = true"><Plus :size="16" />新建知识库</button><button v-if="!auth.isDemo" class="button primary" @click="guideUpload"><Upload :size="16" />上传文档</button></template>
    </PageHeader>
    <nav class="knowledge-section-nav" aria-label="知识与经验视图">
      <template v-if="auth.isDemo"><button type="button" :class="{ active: knowledgeScope === 'public' }" :aria-pressed="knowledgeScope === 'public'" @click="knowledgeScope = 'public'"><BookOpen :size="17" />公共知识</button><button type="button" :class="{ active: knowledgeScope === 'experience' }" :aria-pressed="knowledgeScope === 'experience'" @click="knowledgeScope = 'experience'"><Layers3 :size="17" />我的体验库</button></template>
      <RouterLink v-else to="/knowledge" class="active" aria-current="page"><BookOpen :size="17" />知识文档</RouterLink>
      <template v-if="auth.isAdmin && !auth.isDemo">
        <RouterLink to="/knowledge/review"><BookCheck :size="17" />知识审核<ArrowUpRight :size="13" /></RouterLink>
        <RouterLink to="/knowledge/index-admin"><Database :size="17" />索引管理<ArrowUpRight :size="13" /></RouterLink>
      </template>
    </nav>
    <VisitorKnowledgeLibrary v-if="auth.isDemo && knowledgeScope === 'experience'" :key="auth.user?.userId" :initial-document-id="Number(route.query.documentId) || undefined" />
    <template v-if="!auth.isDemo || knowledgeScope === 'public'">
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" /><p v-if="success" class="inline-success">{{ success }}</p>
    <div class="knowledge-split" :class="{ 'mobile-documents': mobileDocuments }">
    <aside class="panel knowledge-base-directory" aria-label="知识库列表"><header><strong>知识库</strong><span>{{ bases.length }}</span></header><input v-model="baseKeyword" placeholder="搜索知识库" aria-label="搜索知识库" /><nav><button v-for="base in visibleBases" :key="base.id" type="button" :class="{ active: selectedBaseId === base.id }" :aria-pressed="selectedBaseId === base.id" @click="selectBase(base.id)"><BookOpen :size="18" /><span><strong>{{ base.name }}</strong><small>{{ base.description || '查看知识库文档' }}</small></span></button></nav><p v-if="!visibleBases.length">{{ bases.length ? '没有匹配的知识库' : auth.isDemo ? '暂时没有可公开浏览的知识库' : '尚未创建知识库' }}</p></aside>
    <main class="knowledge-library-main">
    <section class="panel knowledge-documents-surface" aria-label="当前知识库文档">
      <header class="knowledge-library-heading"><button type="button" class="text-button knowledge-library-back" @click="mobileDocuments = false"><ArrowLeft :size="16" />知识库列表</button><h2>{{ selectedBase?.name || '选择知识库' }}</h2><small>{{ loadingDocuments ? '读取中…' : `${documents.length} 份文档` }}</small></header>
      <div class="knowledge-library-toolbar"><input v-model="keyword" placeholder="搜索文档名称" aria-label="搜索知识文档" /><select v-model="documentStage" aria-label="文档当前阶段"><option value="">全部阶段</option><option v-for="stage in stageOptions" :key="stage">{{ stage }}</option></select><button class="button secondary" :disabled="loadingDocuments" @click="load">刷新</button></div>
      <TableSurface v-if="selectedBase && visibleDocuments.length" class="knowledge-document-table"><table><thead><tr><th>文档 / 来源</th><th>当前阶段</th><th>创建时间</th><th>下一步</th></tr></thead><tbody><tr v-for="document in visibleDocuments" :key="document.id"><td><button class="knowledge-document-link" @click="detailDocument = document"><FileText :size="20" /><span><strong>{{ document.original_name }}</strong><small>{{ document.file_type || '文档' }} · {{ (document.file_size / 1024).toFixed(1) }} KB</small></span></button></td><td><span class="knowledge-stage" :data-tone="knowledgeStage(document).tone">{{ knowledgeStage(document).label }}</span></td><td><time :title="formatDateTime(document.create_time)">{{ formatShortDateTime(document.create_time) }}</time></td><td><div class="row-actions"><button v-if="!auth.isDemo && ['待解析','解析失败'].includes(knowledgeStage(document).label)" class="button secondary small" :disabled="!!busy" @click="parse(document)">解析文档</button><button v-else-if="!auth.isDemo && knowledgeStage(document).label === '待提交审核'" class="button secondary small" :disabled="!!busy" @click="submitReview(document)">提交审核</button><button v-else-if="canEdit(document) && knowledgeStage(document).label === '审核退回'" class="button secondary small" @click="revisionDocument = document">修改后重提</button><RouterLink v-else-if="auth.isAdmin && knowledgeStage(document).label === '待审核'" class="button secondary small" :to="reviewLink(document)">进入审核</RouterLink><button class="text-button" @click="detailDocument = document">查看详情 →</button></div></td></tr></tbody></table></TableSurface>
      <div v-else-if="loadingDocuments" class="loading-state">正在加载文档…</div><EmptyState v-else-if="documents.length" title="没有符合筛选的文档" description="调整名称或阶段筛选后查看。" />
      <EmptyState v-else-if="auth.isDemo" title="暂时没有可公开浏览的文档" description="可以选择其他知识库，或前往 AI 自动化查看公共案例。" />
      <GuidedEmptyState v-else kind="knowledge" :title="selectedBase ? '这个知识库还没有文档' : '尚未创建知识库'" :description="selectedBase ? '上传一份真实文档，解析并审核后发布。' : '先创建知识库，再上传用于检索的文档。'" :steps="['上传', '解析', '审核发布', '检索引用']" :action="selectedBase ? '上传第一个文档' : '创建知识库'" @action="guideUpload" />
    </section>
    <details v-if="selectedBase?.description" class="panel knowledge-library-description"><summary>知识库用途与说明</summary><p>{{ selectedBase.description }}</p></details>
    </main></div>
    <BaseModal v-if="uploadOpen && selectedBase && !auth.isDemo" title="上传知识文档" :description="`上传到 ${selectedBase.name}`" @close="uploadOpen = false"><div class="knowledge-upload" :class="{ 'drag-active': dragActive }" @dragenter.prevent="dragActive = true" @dragover.prevent="dragActive = true" @dragleave.prevent="dragActive = false" @drop.prevent="chooseFile($event.dataTransfer?.files?.[0])"><label><Upload :size="25" /><span>{{ selectedFile?.name || '选择或拖入文件' }}<small>PDF / DOCX / TXT / Markdown · 最大 10 MB</small></span><input ref="fileInput" type="file" aria-label="选择知识文档" accept=".pdf,.docx,.txt,.md,.markdown" @change="chooseFile(($event.target as HTMLInputElement).files?.[0])" /></label></div><p class="knowledge-modal-note">上传后解析正文，再审核发布；发布与索引状态分别核对。</p><InlineError v-if="error" :message="error" /><div class="form-actions"><button class="button secondary" @click="uploadOpen = false">取消</button><ActionButton class="primary" :disabled="!selectedFile || !!busy" :loading="busy === 'upload'" @click="upload">上传文档</ActionButton></div></BaseModal>
    <BaseModal v-if="detailDocument && !revisionDocument" :title="detailDocument.original_name" wide @close="detailDocument = undefined">
      <section class="knowledge-document-detail">
        <span class="knowledge-stage" :data-tone="knowledgeStage(detailDocument).tone">{{ knowledgeStage(detailDocument).label }}</span>
        <p>文档 #{{ detailDocument.id }} · {{ selectedBase?.name }} · v{{ detailDocument.version }}<template v-if="detailDocument.ticket_id"> · 来源事件 <RouterLink :to="`/tickets/${detailDocument.ticket_id}`">#{{ detailDocument.ticket_id }} ↗</RouterLink></template></p>
        <p v-if="detailDocument.review_comment">审核意见：{{ detailDocument.review_comment }}</p>
        <p v-if="detailDocument.parse_error" class="inline-error">{{ detailDocument.parse_error }}</p>
        <p v-if="knowledgeStage(detailDocument).label === '内容待补齐'">没有取得文本切片，请核对原文件并重新解析。</p>
        <p v-if="knowledgeStage(detailDocument).label === '已发布待索引'">审核已通过，正在等待索引结果；刷新后核对是否可检索。</p>
        <div class="row-actions">
          <button v-if="canEdit(detailDocument)" class="button secondary" :disabled="!!busy" @click="revisionDocument = detailDocument">{{ detailDocument.review_status === 'REJECTED' ? '按意见修改' : '编辑草稿' }}</button>
          <button v-if="!auth.isDemo && !['IN_REVIEW','PUBLISHED','ARCHIVED'].includes(detailDocument.review_status)" class="button primary" :disabled="!!busy" @click="parse(detailDocument)">{{ ['UPLOADED','FAILED'].includes(detailDocument.status) ? '解析文档' : '重新解析' }}</button>
          <button v-if="!auth.isDemo && ['待提交审核','审核退回'].includes(knowledgeStage(detailDocument).label)" class="button primary" :disabled="!!busy" @click="submitReview(detailDocument)">提交审核</button>
          <RouterLink v-if="auth.isAdmin && ['IN_REVIEW','PUBLISHED','REJECTED'].includes(detailDocument.review_status)" class="button primary" :to="reviewLink(detailDocument)">{{ detailDocument.review_status === 'IN_REVIEW' ? '审核此文档 →' : '查看审核记录' }}</RouterLink>
          <button class="button secondary" :disabled="loadingDocuments || !!busy" @click="loadDocuments">刷新状态</button>
        </div>
        <KnowledgeDocumentContent :key="`body-${detailDocument.id}`" :document-id="detailDocument.id" :version="detailDocument.version" />
        <KnowledgeRetrievalCheck v-if="knowledgeStage(detailDocument).label === '可检索'" :key="detailDocument.id" :document-id="detailDocument.id" />
        <details><summary>切片与处理记录</summary><button class="button secondary" :disabled="!['PARSED','INDEXED'].includes(detailDocument.status) || !detailDocument.chunk_count" @click="showChunks(detailDocument)">查看检索切片</button><dl><div><dt>解析</dt><dd><StatusBadge :value="detailDocument.status" /></dd></div><div><dt>审核</dt><dd><StatusBadge :value="detailDocument.review_status || 'DRAFT'" /></dd></div><div><dt>索引</dt><dd><StatusBadge :value="detailDocument.index_status || 'PENDING'" /></dd></div><div><dt>切片</dt><dd>{{ detailDocument.chunk_count ?? '未取得' }}</dd></div><div><dt>嵌入模型</dt><dd>{{ detailDocument.embedding_model || '未记录' }}</dd></div></dl><RouterLink v-if="auth.isAdmin && detailDocument.index_status === 'FAILED'" to="/knowledge/index-admin">查看索引失败原因与重试</RouterLink></details>
        <details v-if="!auth.isDemo"><summary>文档管理</summary><button class="button secondary" :disabled="!!busy" @click="remove(detailDocument)">删除此文档</button></details>
      </section>
    </BaseModal>
    <KnowledgeDocumentRevision v-if="revisionDocument" :document="revisionDocument" @close="revisionDocument = undefined" @saved="revisionSaved" />
    <BaseModal v-if="createOpen && !auth.isDemo" title="新建知识库" description="说明知识库的业务域、维护人和用途" @close="createOpen = false"><form class="form-grid" @submit.prevent="create"><label>知识库名称<input v-model.trim="name" required maxlength="128" placeholder="例如：生产故障处理手册" /></label><label class="full">用途说明<textarea v-model.trim="description" maxlength="500" rows="4" placeholder="说明业务域、维护人和使用范围" /></label><div class="form-actions full"><button type="button" class="button secondary" @click="createOpen = false">取消</button><ActionButton class="primary" :disabled="!name" :loading="busy === 'create'" loading-text="创建中…">创建并进入</ActionButton></div></form></BaseModal>
    <BaseModal v-if="chunkDocument" :title="`${chunkDocument.original_name} · 文本切片`" wide @close="chunkDocument = undefined">
      <div class="chunk-list"><article v-for="chunk in chunks" :key="String(chunk.id)"><header><strong>Chunk {{ chunk.chunk_index }}</strong><span>{{ chunk.token_count || 0 }} tokens</span></header><p>{{ chunk.content }}</p></article></div>
    </BaseModal>
    </template>
  </div>
</template>
