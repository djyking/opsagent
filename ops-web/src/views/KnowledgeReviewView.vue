<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from 'vue-router';
import { BookCheck, Download, Eye, RefreshCw, X } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import { knowledgeReviewApi, type ReviewPreview } from "@/api/knowledge-review";
import PageHeader from "@/components/PageHeader.vue";
import FilterBar from "@/components/FilterBar.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import ListSurface from "@/components/ListSurface.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import BaseModal from '@/components/BaseModal.vue';
import { request } from '@/api/http';
import { knowledgeSummary, knowledgeRetrievalLabel } from '@/utils/knowledge-review-summary';
import StatusBadge from "@/components/StatusBadge.vue";
import FormField from "@/components/FormField.vue";
import { formatDateTime, formatShortDateTime } from "@/utils/datetime";
import { usePageFeedback } from "@/composables/usePageFeedback";
import ActionButton from "@/components/feedback/ActionButton.vue";
import PaginationBar from "@/components/PaginationBar.vue";
import "@/styles/pages/knowledge-review.css";

const rows = ref<Record<string, unknown>[]>([]);
const route = useRoute();
const status = ref(route.query.documentId ? '' : 'IN_REVIEW');
const error = ref("");
const toast = usePageFeedback(error, load);
const busy = ref(0);
const loading = ref(false);
const selected = ref<Record<string, unknown>>();
const fullReadingOpen = ref(false);
const rejectOpen = ref(false);
const reviewHistory = ref<Record<string, unknown>[]>([]);
const historyError = ref('');
const historyLoading = ref(false);
const summary = computed(() => knowledgeSummary(parsedText.value || preview.value?.chunks.map(chunk => chunk.content).join('\n\n') || ''));
const retrievalLabel = computed(() => knowledgeRetrievalLabel(selected.value?.reviewStatus, selected.value?.indexStatus));
const rejectComment = ref("");
const preview = ref<ReviewPreview>();
const previewLoading = ref(false);
const previewError = ref("");
const contentTab = ref<"text" | "chunks">("text");
const parsedText = ref("");
const fullTextSeen = ref(false);
const textLoading = ref(false);
const textError = ref("");
const downloadLoading = ref(false);
const downloadError = ref("");
const sourceDownloaded = ref(false);
const acknowledged = ref(false);
const visitedPages = ref(new Set<number>());
let previewSequence = 0;
let selectionSequence = 0;
let listSequence = 0;
const contentReady = computed(() => Boolean(fullTextSeen.value && parsedText.value.trim()) || sourceDownloaded.value ||
  Boolean(preview.value?.total && visitedPages.value.size >= Math.ceil(preview.value.total / preview.value.pageSize)));
const canApprove = computed(() => selected.value?.reviewStatus === "IN_REVIEW" &&
  ["PARSED", "INDEXED"].includes(preview.value?.parseStatus || "") &&
  Boolean(preview.value?.total) && contentReady.value && acknowledged.value && !previewLoading.value && !previewError.value);

async function load() {
  const sequence = ++listSequence;
  loading.value = true;
  error.value = "";
  try { const result = await itsmApi.reviewDocuments(status.value); if (sequence === listSequence) rows.value = result; }
  catch (cause) { if (sequence === listSequence) error.value = cause instanceof Error ? cause.message : "审核列表加载失败"; }
  finally { if (sequence === listSequence) loading.value = false; }
}
function close() { if (busy.value) return; previewSequence++; selectionSequence++; selected.value = undefined; fullReadingOpen.value = rejectOpen.value = false; }
function open(row: Record<string, unknown>) {
  selectionSequence++;
  selected.value = row;
  fullReadingOpen.value = rejectOpen.value = false; reviewHistory.value = []; historyError.value = '';
  rejectComment.value = "";
  preview.value = undefined;
  previewError.value = textError.value = downloadError.value = parsedText.value = "";
  textLoading.value = downloadLoading.value = false;
  sourceDownloaded.value = acknowledged.value = false;
  fullTextSeen.value = false;
  visitedPages.value = new Set();
  contentTab.value = "text";
  void loadPreview(1, true);
}
async function loadPreview(page = 1, withText = false) {
  if (!selected.value) return;
  const id = Number(selected.value.id);
  const sequence = ++previewSequence;
  textLoading.value = false;
  previewLoading.value = true;
  previewError.value = "";
  try {
    const result = await knowledgeReviewApi.preview(id, page);
    if (sequence !== previewSequence) return;
    if (preview.value && preview.value.version !== result.version) {
      parsedText.value = "";
      fullTextSeen.value = false;
      visitedPages.value = new Set();
      acknowledged.value = sourceDownloaded.value = false;
      withText = true;
    }
    preview.value = result;
    selected.value = { ...selected.value, parseStatus: result.parseStatus, reviewStatus: result.reviewStatus, indexStatus: result.indexStatus ?? selected.value?.indexStatus };
    if (contentTab.value === "chunks" && result.chunks.length) visitedPages.value = new Set([...visitedPages.value, result.pageNum]);
    if (withText && result.total) void loadText(id, sequence);
  } catch (cause) {
    if (sequence === previewSequence) previewError.value = cause instanceof Error ? cause.message : "文档内容加载失败";
  } finally { if (sequence === previewSequence) previewLoading.value = false; }
}
async function loadText(id = Number(selected.value?.id), sequence = previewSequence) {
  textLoading.value = true;
  textError.value = "";
  try {
    const result = await knowledgeReviewApi.text(id);
    if (sequence !== previewSequence) return;
    if (result.version !== preview.value?.version || result.chunkCount !== preview.value?.total) {
      textError.value = "文档解析内容已更新，请刷新预览后重新核对";
      acknowledged.value = false;
      return;
    }
    parsedText.value = result.text;
    fullTextSeen.value = fullReadingOpen.value && contentTab.value === "text";
  } catch (cause) {
    if (sequence === previewSequence) textError.value = cause instanceof Error ? cause.message : "解析全文加载失败";
  } finally { if (sequence === previewSequence) textLoading.value = false; }
}
function selectContentTab(tab: "text" | "chunks") {
  contentTab.value = tab;
  if (tab === "text") {
    if (parsedText.value) fullTextSeen.value = fullReadingOpen.value;
    else if (!textLoading.value && preview.value?.total) void loadText();
  } else if (!previewLoading.value && preview.value?.chunks.length) {
    visitedPages.value = new Set([...visitedPages.value, preview.value.pageNum]);
  }
}
async function downloadSource() {
  if (!selected.value || downloadLoading.value) return;
  const id = Number(selected.value.id);
  const selection = selectionSequence;
  const name = preview.value?.originalName || String(selected.value.originalName);
  downloadLoading.value = true;
  downloadError.value = "";
  try {
    const blob = await knowledgeReviewApi.source(id);
    if (selection !== selectionSequence) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url; link.download = name; link.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    sourceDownloaded.value = true;
  } catch (cause) {
    if (selection === selectionSequence) downloadError.value = cause instanceof Error ? cause.message : "原文件下载失败";
  } finally { if (selection === selectionSequence) downloadLoading.value = false; }
}
async function approve(id: number) {
  if (!canApprove.value || busy.value) return;
  busy.value = id;
  try { const result = await itsmApi.approveDocument(id, summary.value.rootPending ? '已核对原文；根因待复核，按适用限制发布案例，转为 Runbook 需另行审核' : '已核对文档内容与解析结果，审核通过并发布', preview.value!.version); selected.value = { ...selected.value, reviewStatus: 'PUBLISHED', indexStatus: result.index_status || 'PENDING' }; toast.show('知识已发布，索引状态需独立核对'); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审核失败"; }
  finally { busy.value = 0; }
}
async function reject(id: number) {
  const comment = rejectComment.value.trim();
  if (!comment) { error.value = "请输入驳回意见"; return; }
  busy.value = id;
  try { await itsmApi.rejectDocument(id, comment); toast.show("知识已驳回，审核意见已保存"); rejectOpen.value = false; selected.value = { ...selected.value, reviewStatus: 'REJECTED', reviewComment: comment }; await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "驳回失败"; }
  finally { busy.value = 0; }
}
async function followDocument() {
  if (route.query.documentId) status.value = '';
  await load();
  if (!route.query.documentId) return;
  const row = rows.value.find(item => Number(item.id) === Number(route.query.documentId));
  if (row) open(row); else error.value = '未找到指定审核文档，请核对访问权限或刷新列表';
}
watch(() => route.query.documentId, followDocument);
onMounted(followDocument);
onBeforeUnmount(() => { previewSequence++; selectionSequence++; listSequence++; });
function openFullText() { fullReadingOpen.value = true; selectContentTab('text'); }
async function loadHistory(event: Event) {
  if (!(event.target as HTMLDetailsElement).open || !selected.value) return;
  const selection = selectionSequence;
  historyLoading.value = true; historyError.value = '';
  try { const result = await request<Record<string, unknown>[]>({ url: `/api/knowledge/documents/${selected.value.id}/review-history` }); if (selection === selectionSequence) reviewHistory.value = result; }
  catch (cause) { if (selection === selectionSequence) historyError.value = cause instanceof Error ? cause.message : '审核记录读取失败'; }
  finally { if (selection === selectionSequence) historyLoading.value = false; }
}
</script>

<template>
  <div class="stack-page review-page">
    <template v-if="!selected">
      <PageHeader title="知识审核" description="核对知识内容与适用范围，再决定发布"><template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />刷新</button></template></PageHeader>
      <ListSurface><template #toolbar><FilterBar><div class="segmented-control"><button :class="{ active: status === 'IN_REVIEW' }" @click="status = 'IN_REVIEW'; load()">待审核</button><button :class="{ active: status === 'PUBLISHED' }" @click="status = 'PUBLISHED'; load()">已发布</button><button :class="{ active: status === 'REJECTED' }" @click="status = 'REJECTED'; load()">已驳回</button><button :class="{ active: !status }" @click="status = ''; load()">全部</button></div><span class="filter-result">{{ rows.length }} 篇文档</span></FilterBar></template>
      <InlineError v-if="error" :message="error" /><LoadingState v-if="loading && !rows.length" text="正在读取审核队列…" /><EmptyState v-else-if="!rows.length" title="当前没有匹配文档" description="新的待审核文档会显示在这里" :icon="BookCheck" />
      <table v-else><thead><tr><th>文档</th><th>审核状态</th><th>检索状态</th><th>提交时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="String(row.id)" tabindex="0" @click="open(row)" @keydown.enter.self="open(row)"><td><button class="table-title table-title-button" @click.stop="open(row)"><strong>{{ row.originalName }}</strong><span>文档 #{{ row.id }} · {{ row.knowledgeBaseName }}</span></button></td><td><StatusBadge :value="String(row.reviewStatus)" /></td><td>{{ knowledgeRetrievalLabel(row.reviewStatus, row.indexStatus) }}</td><td>{{ row.submittedTime ? formatShortDateTime(String(row.submittedTime)) : '未提交' }}</td><td><button class="button text" @click.stop="open(row)">查看详情</button></td></tr></tbody></table></ListSurface>
    </template>
    <template v-else>
      <button class="text-button review-back" :disabled="!!busy" @click="close">← 返回审核队列</button>
      <section class="review-prototype-header"><div><h1>{{ selected.originalName }}</h1><StatusBadge :value="String(selected.reviewStatus)" /></div><p>文档 #{{ selected.id }} · {{ selected.knowledgeBaseName }} <span>更新于 {{ selected.updateTime ? formatDateTime(String(selected.updateTime)) : '时间未提供' }}</span></p><footer><span>来源事件 <RouterLink v-if="selected.ticketId" :to="`/tickets/${selected.ticketId}`">EVT-{{ selected.ticketId }} ↗</RouterLink><span v-else>未关联事件</span></span><span>{{ retrievalLabel }}</span></footer></section>
      <section class="review-summary-card"><header><h2>知识草稿摘要</h2><span v-if="summary.rootPending" class="review-root-pending">根因待复核</span><button class="button text" @click="openFullText">查看全文 →</button></header><LoadingState v-if="previewLoading && !preview" text="正在读取文档摘要…" /><InlineError v-if="previewError" :message="previewError" /><button v-if="previewError" class="button secondary" @click="loadPreview(1, true)">重试摘要</button><template v-if="preview && !summary.structured"><article><span>文</span><div><h3>原文摘录</h3><p>{{ summary.excerpt }}</p><small class="review-reading-note">摘录自已保存正文，完整内容请查看全文。</small></div></article></template><template v-else-if="preview"><article><span>01</span><div><h3>事故概况</h3><p>{{ summary.incident }}</p></div></article><article><span>02</span><div><h3>有效处置</h3><p>{{ summary.actions }}</p></div></article><article><span>03</span><div><h3>验证结果</h3><p class="review-verification-strip">{{ summary.verification }}</p></div></article></template></section>
      <details class="review-fold"><summary><strong>来源证据</strong><span>原文、解析与关联事件</span></summary><div><dl class="oa-definition-list"><div><dt>原文件</dt><dd>{{ selected.originalName }}</dd></div><div><dt>解析状态</dt><dd><StatusBadge :value="String(preview?.parseStatus || selected.parseStatus)" /></dd></div><div><dt>内容版本</dt><dd>{{ preview?.version ?? '未读取' }}</dd></div><div><dt>关联工单</dt><dd><RouterLink v-if="selected.ticketId" :to="`/tickets/${selected.ticketId}`">工单 #{{ selected.ticketId }}</RouterLink><span v-else>未关联</span></dd></div></dl><button class="button secondary" :disabled="!preview?.sourceAvailable || downloadLoading" @click="downloadSource"><Download :size="15" />下载原文件</button><InlineError v-if="downloadError" :message="downloadError" /></div></details>
      <details class="review-fold"><summary><strong>适用范围与限制</strong><span>{{ summary.rootPending ? '根因仍需复核' : '按原文范围使用' }}</span></summary><div><p>{{ summary.limits }}</p><p>经验案例与可执行 Runbook 分别审核；文档发布不会自动获得生产执行权限。</p></div></details>
      <details class="review-fold" @toggle="loadHistory"><summary><strong>审核记录</strong><span>{{ selected.reviewComment || '按需查看' }}</span></summary><div><LoadingState v-if="historyLoading" text="正在读取审核记录…" /><InlineError v-if="historyError" :message="historyError" /><article v-for="(item, index) in reviewHistory" :key="index"><p>{{ item.comment || item.reviewComment || '未填写意见' }}</p><small>用户 #{{ item.operator_id || item.operatorId || '未提供' }} · {{ item.create_time || item.createTime || '' }} · {{ item.to_status || item.toStatus }}</small></article><p v-if="!historyLoading && !reviewHistory.length && !historyError">尚无审核历史。</p></div></details>
      <section class="review-action-bar"><div><p>{{ retrievalLabel }}</p><label v-if="selected.reviewStatus === 'IN_REVIEW'" class="review-content-ack"><input v-model="acknowledged" type="checkbox" :disabled="!contentReady || !preview?.total" /><span>已阅读全文并核对适用限制</span></label></div><div v-if="selected.reviewStatus === 'IN_REVIEW'" class="review-action-buttons"><button class="button secondary" :disabled="!!busy" @click="rejectOpen = true">驳回</button><ActionButton class="primary" :loading="busy === Number(selected.id)" :disabled="!canApprove" loading-text="正在提交…" @click="approve(Number(selected.id))">通过并发布</ActionButton></div><RouterLink v-if="selected.reviewStatus !== 'IN_REVIEW'" class="button primary" :to="{ path: '/knowledge', query: { baseId: Number(selected.knowledgeBaseId), documentId: Number(selected.id) } }">{{ selected.reviewStatus === 'REJECTED' ? '修改文档后重新提交 →' : '查看文档、索引与引用 →' }}</RouterLink><button v-if="selected.reviewStatus !== 'IN_REVIEW'" class="button secondary" :disabled="previewLoading" @click="open({ ...rows.find(row => row.id === selected?.id) || selected })">刷新状态</button></section>
      <p v-if="selected.reviewStatus === 'IN_REVIEW' && !contentReady" class="review-reading-note">摘要仅用于浏览；审核前请查看全文、全部切片页，或下载并核对原文件。</p><InlineError v-if="error" :message="error" />
      <DetailPanel v-if="fullReadingOpen" title="知识全文" :subtitle="String(selected.originalName)" width="wide" @close="fullReadingOpen = false">
      <section class="review-reading" aria-label="文档内容预览">
        <header class="review-reading-header">
          <div><h3>文档内容</h3><p v-if="preview">版本 {{ preview.version }} · {{ preview.fileType.toUpperCase() || '文档' }} · {{ preview.total }} 个解析切片</p><p v-else>核对原文与解析结果，再决定是否发布</p></div>
          <ActionButton class="secondary" :loading="downloadLoading" :disabled="!preview?.sourceAvailable" loading-text="下载中…" @click="downloadSource"><Download :size="15" />下载原文件</ActionButton>
        </header>
        <p v-if="preview && !preview.sourceAvailable" class="review-reading-notice">原文件暂不可用，可核对下方已保存的解析内容。</p>
        <InlineError v-if="downloadError" :message="downloadError" />
        <InlineError v-if="previewError" :message="previewError" /><button v-if="previewError" class="button secondary" @click="loadPreview(1, true)">重新加载预览</button>
        <LoadingState v-if="previewLoading && !preview" text="正在读取文档内容…" />
        <template v-if="preview">
          <p v-if="preview.parseError" class="review-reading-notice review-reading-notice--error">解析异常：{{ preview.parseError }}</p>
          <div class="segmented-control review-content-tabs" role="tablist" aria-label="正文阅读方式">
            <button role="tab" :aria-selected="contentTab === 'text'" :class="{ active: contentTab === 'text' }" @click="selectContentTab('text')">解析全文</button>
            <button role="tab" :aria-selected="contentTab === 'chunks'" :class="{ active: contentTab === 'chunks' }" @click="selectContentTab('chunks')">分页切片 · {{ preview.total }}</button>
          </div>
          <EmptyState v-if="!preview.total" title="尚无可读解析内容" description="文档需要完成解析后才能核对正文并发布；也可以填写意见驳回。" />
          <div v-else-if="contentTab === 'text'" class="review-text-tab" role="tabpanel">
            <p class="review-reading-note">以下为全部解析切片按顺序拼接的正文，可能包含切片重叠内容；原始排版请下载原文件查看。</p>
            <LoadingState v-if="textLoading" text="正在加载解析全文…" />
            <template v-else-if="textError"><InlineError :message="textError" /><div class="review-content-retry"><button class="button secondary" @click="loadText()">重试全文</button><button class="button secondary" @click="selectContentTab('chunks')">改为分页阅读</button></div></template>
            <pre v-else class="review-parsed-text">{{ parsedText }}</pre>
          </div>
          <div v-else class="review-chunks-tab" role="tabpanel" :aria-busy="previewLoading">
            <LoadingState v-if="previewLoading" text="正在加载切片…" />
            <div v-else class="review-chunk-list"><article v-for="chunk in preview.chunks" :key="chunk.id"><header><strong>切片 {{ chunk.chunkIndex + 1 }}</strong><span v-if="chunk.pageNumber">原文第 {{ chunk.pageNumber }} 页</span><span v-if="chunk.tokenCount != null">{{ chunk.tokenCount }} tokens</span></header><pre>{{ chunk.content }}</pre></article></div>
            <PaginationBar :page="preview.pageNum" :page-size="preview.pageSize" :total="preview.total" @change="loadPreview($event)" />
          </div>
        </template>
      </section>
      </DetailPanel>
      <BaseModal v-if="rejectOpen" title="驳回知识草稿" @close="rejectOpen = false"><FormField label="驳回意见 *"><textarea v-model.trim="rejectComment" required maxlength="500" rows="5" placeholder="说明需补充的证据、适用限制或正文修订" /></FormField><InlineError v-if="error" :message="error" /><template #footer><button class="button secondary" @click="rejectOpen = false">取消</button><button class="button primary" :disabled="!!busy || !rejectComment" @click="reject(Number(selected.id))">确认驳回</button></template></BaseModal>
    </template>
  </div>
</template>
