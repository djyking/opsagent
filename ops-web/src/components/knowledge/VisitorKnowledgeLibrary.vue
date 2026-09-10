<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { FileText, Upload, MessageCircle, Layers3, RefreshCw } from '@lucide/vue';
import { visitorKnowledgeApi as api, type ExperienceDocument, type ExperienceLibrary } from '@/api/visitor-knowledge';
import { useAuthStore } from '@/stores/auth';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { formatDateTime } from '@/utils/datetime';
import BaseModal from '@/components/BaseModal.vue';
import InlineError from '@/components/InlineError.vue';
import KnowledgeRetrievalCheck from './KnowledgeRetrievalCheck.vue';
import KnowledgeDocumentContent from './KnowledgeDocumentContent.vue';

const props = defineProps<{ initialDocumentId?: number }>();
const auth = useAuthStore(), ai = useAiAssistantStore();
const library = ref<ExperienceLibrary>(), error = ref(''), busy = ref(''), loading = ref(false);
const fileInput = ref<HTMLInputElement>(), detailId = ref<number>(), deleteId = ref<number>();
const chunks = ref<Awaited<ReturnType<typeof api.chunks>>>([]), chunksOpen = ref(false);
const detail = computed(() => library.value?.documents.find(document => document.id === detailId.value));
const processing = computed(() => library.value?.documents.some(document => ['QUEUED', 'PARSING', 'INDEXING'].includes(document.stage)));
const uploadDisabled = computed(() => !!busy.value || !library.value || library.value.documents.length >= library.value.limits.files);
let epoch = 0, alive = true, read = 0, timer: number | undefined, followedInitial = false;
const message = (cause: unknown) => cause instanceof Error ? cause.message : '请求失败，请重试';
const size = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`;
const stage = (document: ExperienceDocument) => ({ UPLOADED: '待解析', QUEUED: '等待处理', PARSING: '正在解析切片', INDEXING: '正在向量化', INDEXED: '可用于本人问答', FAILED: document.chunk_count ? '向量化失败' : '解析失败' }[document.stage] || document.stage);
const action = (document: ExperienceDocument) => document.stage === 'FAILED' ? (document.chunk_count ? '重试向量化' : '重试解析') : '解析并加入问答';
function valid(current: number) { return alive && current === epoch; }
function schedule() { window.clearTimeout(timer); if (alive) timer = window.setTimeout(() => void load(true), processing.value ? 2500 : 30000); }
async function load(quiet = false) {
  const current = epoch, own = ++read;
  if (!quiet) loading.value = true;
  try { const result = await api.get(); if (valid(current) && own === read) { library.value = result; if (!followedInitial && props.initialDocumentId) { followedInitial = true; if (result.documents.some(document => document.id === props.initialDocumentId)) detailId.value = props.initialDocumentId; else error.value = '引用文档已过期、删除或当前体验无权访问'; } if (detailId.value && !detail.value) { detailId.value = undefined; chunks.value = []; } } }
  catch (cause) { if (valid(current) && own === read) { error.value = message(cause); if (/体验已结束|过期|到期|无权|会话|VISITOR_(?:REVOKED|EXPIRED)/.test(error.value)) { library.value = undefined; detailId.value = undefined; chunks.value = []; } } }
  finally { if (valid(current) && own === read) { loading.value = false; schedule(); } }
}
async function upload(file?: File) {
  if (!file || busy.value || !library.value) return;
  if (file.size > library.value.limits.fileBytes) { error.value = '单份文件不能超过 5 MB'; return; }
  if (!/\.(pdf|docx|txt|md|markdown)$/i.test(file.name)) { error.value = '仅支持 PDF、DOCX、TXT 和 Markdown'; return; }
  const current = epoch; busy.value = 'upload'; error.value = '';
  try { const id = await api.upload(file); if (!valid(current)) return; await load(); if (valid(current)) detailId.value = id; }
  catch (cause) { if (valid(current)) error.value = message(cause); }
  finally { if (valid(current)) { busy.value = ''; if (fileInput.value) fileInput.value.value = ''; } }
}
async function process(document: ExperienceDocument) {
  if (busy.value || processing.value) return;
  const current = epoch; busy.value = `process-${document.id}`; error.value = '';
  try { await api.process(document.id, document.stage === 'FAILED' && document.chunk_count > 0); if (valid(current)) await load(); }
  catch (cause) { if (valid(current)) error.value = message(cause); }
  finally { if (valid(current)) busy.value = ''; }
}
async function showChunks(document: ExperienceDocument) {
  const current = epoch; busy.value = 'chunks'; error.value = '';
  try { const result = await api.chunks(document.id); if (valid(current) && detailId.value === document.id) { chunks.value = result; chunksOpen.value = true; } }
  catch (cause) { if (valid(current)) error.value = message(cause); }
  finally { if (valid(current)) busy.value = ''; }
}
async function remove() {
  if (!deleteId.value || busy.value) return;
  const current = epoch; busy.value = 'delete'; error.value = '';
  try { await api.remove(deleteId.value); if (!valid(current)) return; deleteId.value = undefined; detailId.value = undefined; chunks.value = []; await load(); }
  catch (cause) { if (valid(current)) error.value = message(cause); }
  finally { if (valid(current)) busy.value = ''; }
}
function ask(document: ExperienceDocument) { ai.setContext({ documentId: document.id }); ai.show(); detailId.value = undefined; }
watch(() => auth.user?.userId, () => { epoch++; read++; library.value = undefined; detailId.value = undefined; chunks.value = []; deleteId.value = undefined; error.value = ''; busy.value = ''; if (auth.isDemo) void load(); });
watch(detailId, () => { chunksOpen.value = false; chunks.value = []; });
watch(() => props.initialDocumentId, () => { followedInitial = false; void load(); });
onMounted(() => void load());
onBeforeUnmount(() => { alive = false; epoch++; window.clearTimeout(timer); });
</script>

<template>
  <section class="visitor-knowledge panel" aria-label="我的体验库">
    <header class="visitor-knowledge-heading"><div><h2>我的体验库</h2><p>上传自己的资料，完成解析、切片和向量化后，向 AI 提问验证。</p></div><div class="visitor-knowledge-actions"><button class="button secondary" :disabled="loading || !!busy" @click="load()"><RefreshCw :size="15" />刷新</button><button class="button primary" :disabled="uploadDisabled" @click="fileInput?.click()"><Upload :size="16" />{{ busy === 'upload' ? '上传中…' : '上传文档' }}</button><input ref="fileInput" class="visitor-knowledge-file" type="file" accept=".pdf,.docx,.txt,.md,.markdown" aria-label="选择体验文档" @change="upload(($event.target as HTMLInputElement).files?.[0])" /></div></header>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <template v-if="library">
      <div class="visitor-knowledge-meta"><span>仅本人可见</span><span>{{ library.documents.length }} / {{ library.limits.files }} 份 · {{ size(library.usedBytes) }} / {{ size(library.limits.totalBytes) }}</span><span>{{ formatDateTime(library.expiresAt) }} 到期</span></div>
      <div v-if="!library.documents.length" class="visitor-knowledge-empty"><Layers3 :size="32" /><h3>从一份资料开始</h3><p>PDF、DOCX、TXT 或 Markdown，单份最大 5 MB。</p><button class="button primary" :disabled="uploadDisabled" @click="fileInput?.click()">上传第一份文档</button></div>
      <div v-else class="visitor-knowledge-documents"><article v-for="document in library.documents" :key="document.id"><FileText :size="24" class="visitor-knowledge-icon" /><div class="visitor-knowledge-document-main"><button class="visitor-knowledge-name" @click="detailId = document.id">{{ document.original_name }}</button><p>{{ document.file_type.toUpperCase() }} · {{ size(document.file_size) }}<template v-if="document.chunk_count"> · {{ document.chunk_count }} 个切片</template></p><span class="visitor-knowledge-stage" :data-state="document.stage">{{ stage(document) }}</span><p v-if="document.parse_error" class="visitor-knowledge-error">{{ document.parse_error }}</p></div><div class="visitor-knowledge-row-actions"><button v-if="['UPLOADED', 'FAILED'].includes(document.stage)" class="button primary small" :disabled="!!busy || processing" @click="process(document)">{{ action(document) }}</button><button v-if="document.stage === 'INDEXED'" class="button primary small" @click="ask(document)"><MessageCircle :size="15" />提问验证</button><button class="text-button" @click="detailId = document.id">查看详情</button></div></article></div>
      <details class="visitor-knowledge-rules"><summary>体验规则与处理额度</summary><p>每人同时处理 1 份文件。每份最多提取 10 万字符、生成 200 个切片；超限会明确失败。扫描 PDF 暂不支持 OCR。</p><p>资料不会公开，也不需要审核。普通退出后可在同一体验身份中继续；24 小时绝对到期或主动结束体验后立即不可访问，文件、切片和索引将在随后 1 小时内清理。</p><p>解析切片不调用 AI；向量化的 embedding 用量与 AI 问答用量分别记录，未返回的用量标记为未知。</p></details>
    </template>
    <p v-else-if="loading" class="visitor-knowledge-loading">正在读取体验库…</p>
  </section>
  <BaseModal v-if="detail" :title="detail.original_name" wide @close="detailId = undefined">
    <section class="visitor-knowledge-detail"><p class="visitor-knowledge-stage" :data-state="detail.stage">{{ stage(detail) }}</p><ol class="visitor-knowledge-steps" aria-label="文档处理步骤"><li class="done">上传</li><li :class="{ done: detail.chunk_count > 0, current: detail.stage === 'PARSING' }">解析与切片</li><li :class="{ done: detail.stage === 'INDEXED', current: detail.stage === 'INDEXING' }">私有向量化</li><li :class="{ current: detail.stage === 'INDEXED' }">本人问答</li></ol><p v-if="detail.parse_error" class="visitor-knowledge-error">{{ detail.parse_error }}</p><div class="visitor-knowledge-actions"><button v-if="['UPLOADED', 'FAILED'].includes(detail.stage)" class="button primary" :disabled="!!busy || processing" @click="process(detail)">{{ action(detail) }}</button><button v-if="detail.chunk_count" class="button secondary" :disabled="!!busy" @click="showChunks(detail)">查看切片（{{ detail.chunk_count }}）</button><button v-if="detail.stage === 'INDEXED'" class="button primary" @click="ask(detail)">针对此文档提问</button></div>
      <div v-if="chunksOpen" class="visitor-knowledge-chunks"><article v-for="chunk in chunks" :key="chunk.id"><strong>切片 {{ chunk.chunk_index + 1 }} · {{ chunk.token_count }} tokens</strong><p>{{ chunk.content }}</p></article></div>
      <KnowledgeDocumentContent :key="`body-${detail.id}`" :document-id="detail.id" :version="detail.version" />
      <KnowledgeRetrievalCheck v-if="detail.stage === 'INDEXED'" :document-id="detail.id" :key="detail.id" />
      <details class="visitor-knowledge-rules"><summary>处理用量与文档管理</summary><p>Embedding 模型：{{ detail.embedding_model || '尚未记录' }}</p><p>已记录 {{ detail.embedding_calls }} 次 embedding 调用，已知用量 {{ detail.embedding_tokens }} tokens<template v-if="detail.embedding_unknown_calls">；另有 {{ detail.embedding_unknown_calls }} 次用量未知</template>。包括该文档的向量化和检索调用。</p><p>AI 回答用量在助手对应回答中查看。</p><button class="button secondary" :disabled="!!busy" @click="deleteId = detail.id">删除此文档</button></details>
    </section>
  </BaseModal>
  <BaseModal v-if="deleteId" title="删除体验文档" @close="deleteId = undefined"><p>删除后立即停止访问，并清理这份文档的文件、切片与私有索引。</p><div class="visitor-knowledge-actions"><button class="button secondary" @click="deleteId = undefined">取消</button><button class="button primary" :disabled="!!busy" @click="remove">{{ busy === 'delete' ? '删除中…' : '删除文档' }}</button></div></BaseModal>
</template>

<style scoped>
.visitor-knowledge{padding:24px;min-width:0}.visitor-knowledge-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:24px}.visitor-knowledge-heading h2{font-size:20px;margin:0 0 8px}.visitor-knowledge-heading p,.visitor-knowledge-document-main p{color:var(--oa-text-muted);font-size:13px;margin:0;line-height:1.7}.visitor-knowledge-actions{display:flex;align-items:center;flex-wrap:wrap;gap:10px}.visitor-knowledge-file{display:none}.visitor-knowledge-meta{display:flex;flex-wrap:wrap;gap:8px 20px;color:var(--oa-text-muted);font-size:12px;padding:18px 0;border-bottom:1px solid var(--oa-border)}.visitor-knowledge-meta span:first-child{color:var(--oa-primary,#2563eb)}.visitor-knowledge-documents article{display:flex;align-items:flex-start;gap:14px;padding:22px 0;border-bottom:1px solid var(--oa-border)}.visitor-knowledge-icon{color:var(--oa-primary,#2563eb);margin-top:3px;flex-shrink:0}.visitor-knowledge-document-main{min-width:0;flex:1}.visitor-knowledge-name{border:0;padding:0;background:transparent;font-weight:600;font-size:15px;text-align:left;overflow-wrap:anywhere;color:var(--oa-text)}.visitor-knowledge-row-actions{display:flex;align-items:center;gap:16px;flex-shrink:0}.visitor-knowledge-stage{display:inline-flex;color:#475569;background:#f1f5f9;padding:4px 9px;border-radius:6px;font-size:12px;margin:9px 0 0}.visitor-knowledge-stage[data-state='INDEXED'],.visitor-knowledge-stage[data-state='INDEXING'],.visitor-knowledge-stage[data-state='PARSING']{color:#1d4ed8;background:#eff6ff}.visitor-knowledge-stage[data-state='FAILED']{color:#b45309;background:#fff7ed}.visitor-knowledge-error{color:#b45309!important;overflow-wrap:anywhere;font-size:13px;line-height:1.6;margin-top:8px!important}.visitor-knowledge-rules{margin-top:20px;color:var(--oa-text-muted);font-size:13px;line-height:1.8}.visitor-knowledge-rules summary{cursor:pointer;color:var(--oa-text);font-weight:500}.visitor-knowledge-rules p{margin:12px 0}.visitor-knowledge-empty{padding:52px 16px;text-align:center;color:var(--oa-text-muted)}.visitor-knowledge-empty h3{color:var(--oa-text);margin:12px 0 6px}.visitor-knowledge-empty p{margin:0 0 20px;font-size:13px}.visitor-knowledge-detail{display:grid;gap:18px}.visitor-knowledge-detail>.visitor-knowledge-stage{justify-self:start;margin:0}.visitor-knowledge-steps{list-style:none;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:0;padding:0}.visitor-knowledge-steps li{padding:12px 8px;text-align:center;border-radius:8px;background:#f8fafc;color:#94a3b8;font-size:13px}.visitor-knowledge-steps li.done{background:#eff6ff;color:#1d4ed8}.visitor-knowledge-steps li.current{outline:1px solid #93c5fd;color:#1d4ed8;background:#eff6ff}.visitor-knowledge-chunks{display:grid;gap:12px;max-height:360px;overflow:auto}.visitor-knowledge-chunks article{padding:14px;background:var(--oa-bg-subtle);border-radius:8px;font-size:13px}.visitor-knowledge-chunks p{white-space:pre-wrap;line-height:1.7;overflow-wrap:anywhere}.visitor-knowledge-loading{padding:20px 0;color:var(--oa-text-muted)}
@media(max-width:760px){.visitor-knowledge{padding:18px}.visitor-knowledge-heading{flex-direction:column;gap:14px}.visitor-knowledge-documents article{flex-wrap:wrap}.visitor-knowledge-row-actions{width:100%;padding-left:38px;flex-wrap:wrap}.visitor-knowledge-steps{grid-template-columns:repeat(2,minmax(0,1fr))}}
</style>
