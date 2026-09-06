<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
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
import StatusBadge from "@/components/StatusBadge.vue";
import FormField from "@/components/FormField.vue";
import { formatDateTime, formatShortDateTime } from "@/utils/datetime";
import { usePageFeedback } from "@/composables/usePageFeedback";
import ActionButton from "@/components/feedback/ActionButton.vue";
import PaginationBar from "@/components/PaginationBar.vue";
import "@/styles/pages/knowledge-review.css";

const rows = ref<Record<string, unknown>[]>([]);
const status = ref("IN_REVIEW");
const error = ref("");
const toast = usePageFeedback(error, load);
const busy = ref(0);
const loading = ref(false);
const selected = ref<Record<string, unknown>>();
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
const contentReady = computed(() => Boolean(fullTextSeen.value && parsedText.value.trim()) || sourceDownloaded.value ||
  Boolean(preview.value?.total && visitedPages.value.size >= Math.ceil(preview.value.total / preview.value.pageSize)));
const canApprove = computed(() => selected.value?.reviewStatus === "IN_REVIEW" &&
  ["PARSED", "INDEXED"].includes(preview.value?.parseStatus || "") &&
  Boolean(preview.value?.total) && contentReady.value && acknowledged.value && !previewLoading.value && !previewError.value);

async function load() {
  loading.value = true;
  error.value = "";
  try { rows.value = await itsmApi.reviewDocuments(status.value); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审核列表加载失败"; }
  finally { loading.value = false; }
}
function close() { previewSequence++; selectionSequence++; selected.value = undefined; }
function open(row: Record<string, unknown>) {
  selectionSequence++;
  selected.value = row;
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
    selected.value = { ...selected.value, parseStatus: result.parseStatus, reviewStatus: result.reviewStatus };
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
    fullTextSeen.value = contentTab.value === "text";
  } catch (cause) {
    if (sequence === previewSequence) textError.value = cause instanceof Error ? cause.message : "解析全文加载失败";
  } finally { if (sequence === previewSequence) textLoading.value = false; }
}
function selectContentTab(tab: "text" | "chunks") {
  contentTab.value = tab;
  if (tab === "text") {
    if (parsedText.value) fullTextSeen.value = true;
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
  try { await itsmApi.approveDocument(id, "已核对文档内容与解析结果，审核通过并发布"); toast.show("知识审核已通过并发布"); close(); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审核失败"; }
  finally { busy.value = 0; }
}
async function reject(id: number) {
  const comment = rejectComment.value.trim();
  if (!comment) { error.value = "请输入驳回意见"; return; }
  busy.value = id;
  try { await itsmApi.rejectDocument(id, comment); toast.show("知识已驳回，审核意见已保存"); close(); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "驳回失败"; }
  finally { busy.value = 0; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page review-page">
    <PageHeader title="知识审核" description="审核解析完成的知识文档并决定是否发布">
      <template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />{{ loading ? "刷新中…" : "刷新" }}</button></template>
    </PageHeader>
    <section class="knowledge-review-intro" aria-label="知识发布流程">
      <span class="knowledge-review-symbol"><BookCheck :size="26" /></span>
      <div><h2>让值得信赖的知识，进入团队的答案。</h2><p>核对文档内容与解析状态，留下明确的审核意见。</p></div>
      <ol><li><span>1</span>核对内容</li><li><span>2</span>记录意见</li><li><span>3</span>审核发布</li></ol>
    </section>
    <ListSurface>
      <template #toolbar><FilterBar>
      <div class="segmented-control">
        <button :class="{ active: status === 'IN_REVIEW' }" @click="status = 'IN_REVIEW'; load()">待审核</button>
        <button :class="{ active: status === 'PUBLISHED' }" @click="status = 'PUBLISHED'; load()">已通过</button>
        <button :class="{ active: status === 'REJECTED' }" @click="status = 'REJECTED'; load()">已驳回</button>
        <button :class="{ active: !status }" @click="status = ''; load()">全部</button>
      </div>
      <span class="filter-result">{{ rows.length }} 篇文档</span>
      </FilterBar></template>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />

      <LoadingState v-if="loading && !rows.length" text="正在读取审核队列…" />
      <EmptyState v-else-if="!rows.length" title="当前筛选下没有知识文档" description="新的待审核文档会显示在这里" :icon="BookCheck" />
      <table v-else>
        <thead><tr><th>文档</th><th>知识库</th><th>解析状态</th><th>审核状态</th><th>提交时间</th><th>意见</th><th></th></tr></thead>
        <tbody><tr v-for="row in rows" :key="String(row.id)" tabindex="0" @click="open(row)" @keydown.enter="open(row)">
          <td><button class="table-title table-title-button" @click.stop="open(row)"><strong>{{ row.originalName }}</strong><span>文档 #{{ row.id }}</span></button></td>
          <td>{{ row.knowledgeBaseName }}</td><td><StatusBadge :value="String(row.parseStatus)" /></td><td><StatusBadge :value="String(row.reviewStatus)" /></td>
          <td><time :title="row.submittedTime ? formatDateTime(String(row.submittedTime)) : '未提交'">{{ row.submittedTime ? formatShortDateTime(String(row.submittedTime)) : "未提交" }}</time></td>
          <td>{{ row.reviewComment || "—" }}</td><td><div class="row-actions reveal-on-row"><button class="icon-button" title="查看审核详情" @click.stop="open(row)"><Eye :size="16" /></button></div></td>
        </tr></tbody>
      </table>
    </ListSurface>
    <DetailPanel v-if="selected" title="审核文档" :subtitle="String(selected.originalName)" width="wide" @close="close">
      <div class="knowledge-review-content">
      <dl class="oa-definition-list review-document-meta">
        <div><dt>知识库</dt><dd>{{ selected.knowledgeBaseName }}</dd></div><div><dt>解析状态</dt><dd><StatusBadge :value="String(selected.parseStatus)" /></dd></div>
        <div><dt>审核状态</dt><dd><StatusBadge :value="String(selected.reviewStatus)" /></dd></div><div><dt>提交时间</dt><dd>{{ selected.submittedTime ? formatDateTime(String(selected.submittedTime)) : "未提交" }}</dd></div>
        <div><dt>当前意见</dt><dd>{{ selected.reviewComment || "无" }}</dd></div>
      </dl>
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
      <div v-if="selected.reviewStatus === 'IN_REVIEW'" class="review-decision">
        <label class="review-content-ack"><input v-model="acknowledged" type="checkbox" :disabled="!contentReady || !preview?.total" /><span>我已阅读文档内容，并核对解析结果可以用于知识问答</span></label>
        <p v-if="!contentReady" class="review-reading-note">请先加载全文、阅读全部切片页，或下载并核对原文件，再确认审核。</p>
        <FormField label="驳回意见" help="驳回时必须填写原因"><textarea v-model.trim="rejectComment" rows="3" maxlength="500" placeholder="说明需要修改的内容，例如正文缺失、解析错误或知识不准确" /></FormField>
      </div>
      <InlineError v-if="error" :message="error" />
      </div>
      <template v-if="selected.reviewStatus === 'IN_REVIEW'" #footer>
        <button class="button secondary danger-text" :disabled="busy === Number(selected.id)" @click="reject(Number(selected.id))"><X :size="15" />驳回</button>
        <ActionButton class="primary" :loading="busy === Number(selected.id)" :disabled="!canApprove" loading-text="正在提交审核…" @click="approve(Number(selected.id))">通过并发布</ActionButton>
      </template>
    </DetailPanel>
  </div>
</template>
