<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { AlertTriangle, Check, DatabaseZap, FileText, RefreshCw, RotateCw, Wrench } from "@lucide/vue";
import { adminApi } from "@/api/modules";
import { knowledgeIndexApi, type KnowledgeIndexTask } from "@/api/knowledge-index";
import BaseModal from "@/components/BaseModal.vue";
import MetricStrip from "@/components/MetricStrip.vue";
import type { MetricStripItem } from "@/components/MetricStrip.vue";
import PageHeader from "@/components/PageHeader.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import FormField from "@/components/FormField.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { usePageFeedback } from "@/composables/usePageFeedback";
import ActionButton from "@/components/feedback/ActionButton.vue";
import GuidedEmptyState from "@/components/experience/GuidedEmptyState.vue";

const consistency = ref<Record<string, unknown>>({});
const failedTasks = ref<KnowledgeIndexTask[]>([]);
const reindexTask = ref<Record<string, unknown> | null>(null);
const repairDocumentId = ref<number>();
const modal = ref<"" | "maintenance" | "repair" | "retry" | "reindex">("");
const selectedTask = ref<KnowledgeIndexTask>();
const reindexAcknowledged = ref(false);
const busy = ref("");
const loading = ref(false);
const error = ref("");
const success = ref("");
const loaded = ref(false);
const tasksLoaded = ref(false);
const historyOpen = ref(false);
const historyPage = ref(1);
const actionablePage = ref(1);
const pageSize = 8;
const toast = usePageFeedback(error, load);
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;

const actionableTasks = computed(() => failedTasks.value.filter((task) => task.repairable));
const historicalTasks = computed(() => failedTasks.value.filter((task) => !task.repairable));
const visibleActionable = computed(() => actionableTasks.value.slice((actionablePage.value - 1) * pageSize, actionablePage.value * pageSize));
const visibleHistory = computed(() => historicalTasks.value.slice((historyPage.value - 1) * pageSize, historyPage.value * pageSize));
const rebuilding = computed(() => ["PENDING", "RUNNING"].includes(String(reindexTask.value?.status || "")));
const mutationDisabled = computed(() => Boolean(busy.value) || rebuilding.value);
const healthMetrics = computed<MetricStripItem[]>(() => [
  { key: "published", label: "已发布文档", value: Number(consistency.value.publishedDocumentCount || 0), meta: "MySQL 当前发布记录", icon: FileText },
  { key: "indexed", label: "已发布检索文档", value: Number(consistency.value.publishedIndexedDocumentCount || 0), meta: `ES 全部文档 ${consistency.value.totalIndexedDocumentCount || 0} 篇`, icon: DatabaseZap },
  { key: "vectors", label: "已发布向量", value: Number(consistency.value.publishedVectorPointCount || 0), meta: `源切片 ${consistency.value.publishedChunkCount || 0} / 全部向量 ${consistency.value.vectorPointCount || 0}`, icon: DatabaseZap },
  { key: "pending", label: "待处理文档", value: Number(consistency.value.pendingDocumentCount || 0), meta: "等待索引管线处理", tone: Number(consistency.value.pendingDocumentCount || 0) ? "warning" : "default", icon: RefreshCw },
  { key: "failed", label: "失败文档", value: Number(consistency.value.failedDocumentCount || 0), meta: "仅统计仍在发布的文档", tone: Number(consistency.value.failedDocumentCount || 0) ? "danger" : "default", icon: AlertTriangle },
]);
const checks = computed(() => [
  { label: "ES 缺失文档", count: Number(consistency.value.missingEsDocumentCount || 0), unit: "篇" },
  { label: "ES 孤儿文档", count: Number(consistency.value.orphanEsDocumentCount || 0), unit: "篇" },
  { label: "Qdrant 缺失向量", count: Number(consistency.value.missingQdrantPointCount || 0), unit: "个" },
  { label: "Qdrant 孤儿向量", count: Number(consistency.value.orphanQdrantPointCount || 0), unit: "个" },
]);
const hasIssues = computed(() => checks.value.some((item) => item.count > 0) || Number(consistency.value.failedDocumentCount || 0) > 0);
const reindexProgress = computed(() => {
  const total = Number(reindexTask.value?.document_total || 0);
  return total ? Math.min(100, Math.round((Number(reindexTask.value?.document_success || 0) + Number(reindexTask.value?.document_failure || 0)) / total * 100)) : 0;
});

function message(cause: unknown, fallback: string) {
  return cause instanceof Error ? cause.message : fallback;
}
function stopPolling() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = undefined;
}
function schedulePoll(taskId: number) {
  stopPolling();
  if (disposed) return;
  pollTimer = setTimeout(() => void pollReindex(taskId), 4000);
}
async function pollReindex(taskId: number) {
  try {
    const task = await adminApi.knowledgeReindexTask(taskId);
    if (disposed) return;
    reindexTask.value = task;
    if (rebuilding.value) schedulePoll(taskId);
    else {
      if (task.status === "SUCCESS") {
        success.value = `全量重建 #${taskId} 已完成，检索索引已切换。`;
        toast.show(success.value);
      }
      await load();
    }
  } catch (cause) {
    if (!disposed) error.value = message(cause, "任务进度暂时无法读取，请刷新继续跟进；后台任务不会因此取消。");
  }
}
async function load() {
  if (loading.value || disposed) return;
  loading.value = true;
  error.value = "";
  stopPolling();
  const results = await Promise.allSettled([
    adminApi.knowledgeIndexConsistency(), knowledgeIndexApi.failedTasks(), knowledgeIndexApi.latestReindexTask(),
  ]);
  if (disposed) return;
  const [health, tasks, latest] = results;
  const failures: string[] = [];
  loaded.value = health.status === "fulfilled";
  if (health.status === "fulfilled") consistency.value = health.value;
  else failures.push(message(health.reason, "一致性状态读取失败"));
  tasksLoaded.value = tasks.status === "fulfilled";
  if (tasks.status === "fulfilled") {
    failedTasks.value = tasks.value;
    actionablePage.value = Math.min(actionablePage.value, Math.max(1, Math.ceil(actionableTasks.value.length / pageSize)));
    historyPage.value = Math.min(historyPage.value, Math.max(1, Math.ceil(historicalTasks.value.length / pageSize)));
  } else failures.push(message(tasks.reason, "失败任务读取失败"));
  if (latest.status === "fulfilled") {
    reindexTask.value = latest.value;
    if (rebuilding.value) schedulePoll(Number(latest.value?.id));
  } else failures.push(message(latest.reason, "重建任务读取失败"));
  error.value = failures.join("；");
  loading.value = false;
}
function openModal(next: typeof modal.value) {
  if (mutationDisabled.value) return;
  error.value = "";
  reindexAcknowledged.value = false;
  modal.value = next;
}
function closeModal() {
  if (!busy.value) modal.value = "";
}
function openRetry(task: KnowledgeIndexTask) {
  if (!task.repairable || mutationDisabled.value) return;
  selectedTask.value = task;
  openModal("retry");
}
async function repair() {
  if (mutationDisabled.value) return;
  const task = modal.value === "retry" ? selectedTask.value : undefined;
  const id = task?.documentId || Number(repairDocumentId.value);
  if (!Number.isSafeInteger(id) || id <= 0) {
    error.value = "请输入有效的文档编号";
    return;
  }
  busy.value = "repair";
  error.value = "";
  try {
    const taskId = task ? await knowledgeIndexApi.retryTask(task) : await adminApi.repairKnowledgeIndex(id);
    success.value = `文档 #${id} 的${task?.operation === "DELETE" ? "索引清理" : "索引修复"}任务 #${taskId} 已提交。处理中任务会复用；提交成功不代表索引已完成，请稍后刷新检查。`;
    toast.show("任务已提交，后台正在处理");
    repairDocumentId.value = undefined;
    modal.value = "";
    await load();
  } catch (cause) {
    error.value = message(cause, "任务提交失败");
  } finally {
    busy.value = "";
  }
}
async function startReindex() {
  if (mutationDisabled.value || !reindexAcknowledged.value) return;
  busy.value = "reindex";
  error.value = "";
  try {
    const taskId = await adminApi.requestKnowledgeReindex();
    reindexTask.value = { id: taskId, status: "PENDING" };
    success.value = `全量重建 #${taskId} 已提交。可离开本页，返回后会继续显示后台进度。`;
    toast.show("重建任务已提交");
    modal.value = "";
    schedulePoll(taskId);
  } catch (cause) {
    error.value = message(cause, "重建任务提交失败");
  } finally {
    busy.value = "";
  }
}
onMounted(load);
onBeforeUnmount(() => { disposed = true; stopPolling(); });
</script>

<template>
  <div class="stack-page index-admin-page">
    <PageHeader title="知识索引" description="让已发布知识可被检索，区分当前故障与已失效的历史任务">
      <template #actions>
        <button class="button secondary" :disabled="loading || Boolean(busy)" @click="load"><RefreshCw :size="15" />{{ loading ? "检查中…" : "刷新检查" }}</button>
        <button class="button secondary" :disabled="mutationDisabled" @click="openModal('maintenance')"><Wrench :size="15" />维护操作</button>
      </template>
    </PageHeader>
    <p v-if="error && !modal" class="inline-error" role="alert">{{ error }}</p>
    <p v-if="success" class="inline-success" role="status">{{ success }}</p>
    <section v-if="reindexTask" class="panel compact-surface index-rebuild-status">
      <header class="section-header">
        <div><h3>最近全量重建 #{{ reindexTask.id }}</h3><p>成功 {{ reindexTask.document_success || 0 }} / {{ reindexTask.document_total || 0 }} 篇 · 失败 {{ reindexTask.document_failure || 0 }} 篇 · 切片 {{ reindexTask.chunk_total || 0 }} 个</p></div>
        <StatusBadge :value="String(reindexTask.status)" />
      </header>
      <progress v-if="rebuilding" :value="reindexProgress" max="100" aria-label="索引重建进度" />
      <p v-if="rebuilding" class="index-status-note">后台重建中，每 4 秒更新进度。校验完成前继续使用当前索引，维护提交暂时禁用。</p>
      <p v-if="reindexTask.error_message" class="inline-error">{{ reindexTask.error_message }}</p>
    </section>
    <section class="panel index-health-surface">
      <header class="section-header">
        <div><h3>检索健康</h3><p>按真实文档与向量 ID 核对，避免数量相等掩盖缺失数据</p></div>
        <span v-if="loaded" class="health-indicator" :class="{ issue: hasIssues }"><i />{{ hasIssues ? "需要处理" : "ID 核对通过" }}</span>
        <span v-else class="panel-count">{{ loading ? "正在检查" : "状态未获取" }}</span>
      </header>
      <MetricStrip v-if="loaded" :items="healthMetrics" label="索引健康指标" />
      <p v-else class="index-status-note">{{ loading ? "正在读取源文档与检索索引…" : "一致性结果暂不可用。失败任务仍可独立查看，不会将未知状态显示为正常。" }}</p>
      <div v-if="loaded" class="index-details-grid">
        <section>
          <header class="section-header compact"><div><h3>当前索引</h3><p>读取别名与物理资源</p></div></header>
          <dl class="index-definition-list">
            <div><dt>读取 Alias</dt><dd>{{ consistency.indexAlias || "—" }}</dd></div>
            <div><dt>写入 Alias</dt><dd>{{ consistency.writeAlias || "—" }}</dd></div>
            <div><dt>ES Index</dt><dd>{{ consistency.physicalIndex || "—" }}</dd></div>
            <div><dt>Qdrant Alias</dt><dd>{{ consistency.vectorAlias || "—" }}</dd></div>
            <div><dt>Collection</dt><dd>{{ consistency.physicalCollection || "—" }}</dd></div>
            <div><dt>Embedding</dt><dd>{{ consistency.embeddingModel || "—" }}</dd></div>
          </dl>
        </section>
        <section>
          <header class="section-header compact"><div><h3>一致性核对</h3><p>分别计算缺失与孤儿，不相互抵消</p></div></header>
          <div class="consistency-checklist">
            <div v-for="item in checks" :key="item.label" :class="{ issue: item.count > 0 }">
              <span><Check v-if="!item.count" :size="15" /><AlertTriangle v-else :size="15" /></span>
              <strong>{{ item.label }}：{{ item.count }} {{ item.unit }}</strong>
            </div>
          </div>
          <p class="index-check-note">{{ consistency.checkNote }}</p>
          <button v-if="hasIssues" class="text-button" :disabled="mutationDisabled" @click="openModal('maintenance')">查看修复方式</button>
        </section>
      </div>
    </section>

    <section class="panel failed-task-surface">
      <header class="section-header">
        <div><h3>待处理任务</h3><p>仅展示当前仍可重试的索引或清理故障</p></div><span class="panel-count">{{ actionableTasks.length }}</span>
      </header>
      <GuidedEmptyState v-if="tasksLoaded && !actionableTasks.length" kind="index" title="当前没有需要重试的失败任务" :description="historicalTasks.length ? `另有 ${historicalTasks.length} 条已失效历史记录，不影响当前知识检索，可在下方展开查看。` : '已发布文档的索引失败会显示在这里。你也可以通过一致性检查发现缺失的数据。'" action="重新检查" @action="load" />
      <p v-else-if="!tasksLoaded" class="index-status-note">{{ loading ? "正在读取任务…" : "任务读取失败，请刷新重试。" }}</p>
      <div v-else class="index-task-table">
        <article v-for="task in visibleActionable" :key="task.id">
          <code>#{{ task.id }}</code>
          <div><strong>{{ task.documentName || `文档 #${task.documentId}` }}</strong><span>文档 #{{ task.documentId }} · v{{ task.documentVersion }} · {{ task.operation === "DELETE" ? "清理索引" : "建立索引" }} · 已重试 {{ task.retryCount }} 次</span><p>{{ task.lastError || "未记录错误详情" }}</p><small class="index-task-guidance">{{ task.repairReason }}</small></div>
          <button class="button secondary small" :disabled="mutationDisabled" @click="openRetry(task)">{{ task.operation === "DELETE" ? "重试清理" : "重试索引" }}</button>
        </article>
      </div>
      <div v-if="actionableTasks.length > pageSize" class="index-pagination"><span>{{ actionableTasks.length }} 条 · 第 {{ actionablePage }} / {{ Math.ceil(actionableTasks.length / pageSize) }} 页</span><button class="button secondary small" :disabled="actionablePage === 1" @click="actionablePage--">上一页</button><button class="button secondary small" :disabled="actionablePage * pageSize >= actionableTasks.length" @click="actionablePage++">下一页</button></div>
    </section>

    <section v-if="tasksLoaded && historicalTasks.length" class="panel index-history-surface">
      <button class="index-history-toggle" :aria-expanded="historyOpen" @click="historyOpen = !historyOpen"><span><strong>历史任务 · {{ historicalTasks.length }} 条</strong><small>源文档已删除、未发布、版本已变化或当前索引已恢复；保留记录，不提供无效重试。</small></span><span>{{ historyOpen ? "收起" : "展开" }}</span></button>
      <div v-if="historyOpen" class="index-history-list">
        <article v-for="task in visibleHistory" :key="task.id">
          <div><strong>{{ task.documentName || `文档 #${task.documentId}` }}</strong><small>任务 #{{ task.id }} · 文档 #{{ task.documentId }} · 任务 v{{ task.documentVersion }} / 当前 {{ task.currentDocumentVersion == null ? "不存在" : `v${task.currentDocumentVersion}` }}</small></div>
          <span class="index-history-reason">{{ task.repairReason }}</span>
          <details v-if="task.lastError"><summary>原始错误详情</summary><p>{{ task.lastError }}</p></details>
        </article>
        <div v-if="historicalTasks.length > pageSize" class="index-pagination"><span>第 {{ historyPage }} / {{ Math.ceil(historicalTasks.length / pageSize) }} 页</span><button class="button secondary small" :disabled="historyPage === 1" @click="historyPage--">上一页</button><button class="button secondary small" :disabled="historyPage * pageSize >= historicalTasks.length" @click="historyPage++">下一页</button></div>
      </div>
    </section>

    <BaseModal v-if="modal === 'maintenance'" title="索引维护" description="先确定影响范围，再选择合适的修复方式" @close="closeModal">
      <div class="index-maintenance-options">
        <button @click="openModal('repair')"><Wrench :size="23" /><span><strong>修复单篇文档</strong><small>适用于已发布、已解析文档的索引缺失或失败。后台处理，已有任务会复用。</small></span></button>
        <button @click="openModal('reindex')"><RotateCw :size="23" /><span><strong>全量重建检索索引</strong><small>重新生成全部已发布知识的文本索引与向量，校验通过后切换。适用于全局不一致。</small></span></button>
      </div>
      <InlineNotice title="从任务列表开始">如果下方有可重试的失败任务，优先使用该任务的重试按钮。已删除文档的旧索引失败无需重新投递。</InlineNotice>
    </BaseModal>

    <BaseModal v-if="modal === 'repair' || modal === 'retry'" :title="modal === 'retry' ? '确认重试任务' : '修复单篇文档'" description="校验源文档、当前版本与任务状态后，再加入后台队列" @close="closeModal">
      <form id="index-repair" class="index-maintenance-form" @submit.prevent="repair">
        <InlineNotice v-if="selectedTask && modal === 'retry'" :title="selectedTask.documentName || `文档 #${selectedTask.documentId}`">任务 #{{ selectedTask.id }} · v{{ selectedTask.documentVersion }}。{{ selectedTask.repairReason }}。此操作只更新检索索引，不修改源文档。</InlineNotice>
        <template v-else>
          <InlineNotice title="可修复范围">文档需要已发布、已完成解析并具有有效切片。已删除或旧版本任务不会被重新投递。</InlineNotice>
          <FormField label="文档编号" help="可在知识库文档详情中查看编号"><input v-model.number="repairDocumentId" min="1" step="1" required type="number" placeholder="输入文档编号，例如 1001" /></FormField>
        </template>
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      </form>
      <template #footer><button class="button secondary" :disabled="Boolean(busy)" @click="closeModal">取消</button><ActionButton form="index-repair" type="submit" class="primary" :loading="busy === 'repair'" :disabled="modal === 'repair' && !repairDocumentId" loading-text="正在提交…">提交后台任务</ActionButton></template>
    </BaseModal>

    <BaseModal v-if="modal === 'reindex'" title="确认全量重建" description="这是后台维护任务，请确认影响范围" @close="closeModal">
      <div class="index-maintenance-form">
        <InlineNotice title="重建全部已发布知识">当前 {{ consistency.publishedDocumentCount ?? "—" }} 篇已发布文档、{{ consistency.publishedChunkCount ?? "—" }} 个切片。将建立新的 Elasticsearch 索引和 Qdrant Collection，通过校验后协调切换。</InlineNotice>
        <ul class="index-impact-list"><li>重建期间仍使用当前索引提供检索；存在失败时不切换。</li><li>会使用额外 CPU、内存和磁盘，并调用已配置的 Embedding API，可能产生调用费用。</li><li>源文档与既有索引保留。任务开始后可离开页面，但关闭弹窗或页面不会取消后台任务。</li></ul>
        <label class="index-maintenance-ack"><input v-model="reindexAcknowledged" type="checkbox" />我已了解影响，开始全量重建</label>
        <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
      </div>
      <template #footer><button class="button secondary" :disabled="Boolean(busy)" @click="closeModal">取消</button><ActionButton class="primary" :disabled="!reindexAcknowledged" :loading="busy === 'reindex'" loading-text="正在提交…" @click="startReindex">开始重建</ActionButton></template>
    </BaseModal>
  </div>
</template>

<style scoped>
.index-rebuild-status progress { display: block; width: 100%; height: 8px; accent-color: var(--oa-primary); }
.index-check-note, .index-task-guidance { color: var(--oa-text-secondary); font-size: var(--oa-font-size-xs); line-height: 1.7; }
.index-check-note { margin: 14px 0 8px; }
.index-definition-list dd { overflow-wrap: anywhere; min-width: 0; }
.index-task-guidance { display: block; margin-top: 8px; }
.index-history-toggle { display: flex; align-items: center; justify-content: space-between; gap: 16px; width: 100%; padding: 20px 24px; text-align: left; color: var(--oa-text-primary); }
.index-history-toggle strong, .index-history-toggle small { display: block; }
.index-history-toggle small { color: var(--oa-text-secondary); margin-top: 5px; line-height: 1.7; }
.index-history-toggle > span:last-child { flex: 0 0 auto; color: var(--oa-primary); }
.index-history-list article { display: grid; gap: 10px; padding: 18px 24px; border-top: 1px solid var(--oa-border-subtle); }
.index-history-list article small { display: block; color: var(--oa-text-secondary); margin-top: 5px; }
.index-history-reason { color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.index-history-list details { font-size: var(--oa-font-size-xs); color: var(--oa-text-secondary); }
.index-history-list details summary { cursor: pointer; }
.index-history-list details p { white-space: pre-wrap; overflow-wrap: anywhere; }
.index-pagination { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding: 16px 24px; color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); flex-wrap: wrap; }
.index-pagination > span { margin-right: auto; }
.index-maintenance-options { display: grid; gap: 12px; margin-bottom: 20px; }
.index-maintenance-options > button { display: flex; align-items: flex-start; gap: 14px; padding: 18px; border: 1px solid var(--oa-border-default); border-radius: var(--oa-radius-panel); background: var(--oa-bg-subtle); color: var(--oa-primary); text-align: left; }
.index-maintenance-options > button:hover { border-color: var(--oa-primary); background: var(--oa-primary-soft); }
.index-maintenance-options svg { flex: 0 0 auto; }
.index-maintenance-options strong { display: block; color: var(--oa-text-primary); }
.index-maintenance-options small { display: block; margin-top: 6px; color: var(--oa-text-secondary); line-height: 1.8; }
.index-maintenance-form { display: grid; gap: 18px; }
.index-maintenance-form input[type="number"] { width: 100%; }
.index-impact-list { margin: 0; padding-left: 20px; color: var(--oa-text-secondary); line-height: 1.8; }
.index-impact-list li + li { margin-top: 8px; }
.index-maintenance-ack { display: flex; align-items: center; gap: 10px; font-size: var(--oa-font-size-sm); }
.index-maintenance-ack input { flex: 0 0 auto; }
@media (max-width: 720px) {
  .index-history-toggle, .index-history-list article { padding: 16px; }
  .index-pagination { padding: 16px; }
  .index-history-toggle { align-items: flex-start; }
}
</style>
