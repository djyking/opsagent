<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Check,
  FileText,
  Upload,
  Play,
  Trash2,
  Layers3,
  Send,
  Bot,
  Clock3,
  MessageSquareText,
  Database,
  Wrench,
} from "@lucide/vue";
import { aiApi, documentApi, itsmApi, ticketApi } from "@/api/modules";
import { ragAnswerLabel, ragCompletionLabel, ragIncompleteMessage, streamRagAnswer } from "@/api/rag-stream";
import type {
  AiQuestion,
  DocumentChunk,
  DocumentRecord,
  Ticket,
  TicketComment,
  TicketLog,
  TicketTrace,
  TicketWorkRecord,
  WorkRecordType,
} from "@/types/api";
import { useAuthStore } from "@/stores/auth";
import BaseModal from "@/components/BaseModal.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import AnswerContent from "@/components/AnswerContent.vue";
import RagSources from "@/components/RagSources.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import LoadingState from "@/components/LoadingState.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import DetailHeader from "@/components/DetailHeader.vue";
import DescriptionList from "@/components/DescriptionList.vue";
import FormField from "@/components/FormField.vue";
import TechnicalMetadata from "@/components/TechnicalMetadata.vue";
import { formatDateTime } from "@/utils/datetime";
import { operationLabel, statusLabel } from "@/ui/status-map";
import { parseTicketDescription } from "@/utils/ticket-description";
import { usePageFeedback } from "@/composables/usePageFeedback";
import ActionButton from "@/components/feedback/ActionButton.vue";

type TicketAction =
  | "accept"
  | "start"
  | "suspend"
  | "resume"
  | "waitConfirm"
  | "resolve"
  | "reopen"
  | "close";

const MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
const ACCEPTED_EXTENSIONS = ["pdf", "docx", "txt", "md", "markdown"];
const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const id = Number(route.params.id);
const ticket = ref<Ticket>();
const logs = ref<TicketLog[]>([]);
const comments = ref<TicketComment[]>([]);
const workRecords = ref<TicketWorkRecord[]>([]);
const trace = ref<TicketTrace>();
const sla = ref<Record<string, unknown>>();
const traceOpen = ref(false);
const activeTab = ref<"overview" | "documents" | "records" | "activity">("overview");
const documents = ref<DocumentRecord[]>([]);
const questions = ref<AiQuestion[]>([]);
const loading = ref(true);
const error = ref("");
const toast = usePageFeedback(error, load);
const recordSaved = ref(false);
let recordTimer: ReturnType<typeof setTimeout>;
onBeforeUnmount(() => clearTimeout(recordTimer));
const busy = ref("");
const action = ref<TicketAction | "">("");
const remark = ref("");
const commentText = ref("");
const workRecordType = ref<WorkRecordType>("DIAGNOSIS");
const workRecordContent = ref("");
const workRecordEvidence = ref("");
const selectedFile = ref<File>();
const dragActive = ref(false);
const question = ref("");
const selectedDocument = ref<number>();
const chunks = ref<DocumentChunk[]>([]);
const chunkDocument = ref<DocumentRecord>();
const isOwner = computed(() => ticket.value?.creatorId === auth.user?.userId);
const isAssignee = computed(
  () => ticket.value?.assigneeId === auth.user?.userId,
);
const canUpload = computed(
  () => auth.isAdmin || isOwner.value || isAssignee.value,
);
const descriptionParts = computed(() => parseTicketDescription(ticket.value?.description));
const technicalMetadata = computed<Record<string, string | number | boolean>>(() => descriptionParts.value.metadata);
const alertName = computed(() => descriptionParts.value.alertName || "未提供");
const affectedService = computed(() => descriptionParts.value.affectedService || ticket.value?.affectedCiCode || "未关联");
const availableActions = computed<TicketAction[]>(() => {
  if (!ticket.value) return [];
  const operator = isAssignee.value || auth.isAdmin;
  if (ticket.value.status === "CREATED" && (auth.isOps || auth.isAdmin))
    return ["accept"];
  if (ticket.value.status === "ASSIGNED" && operator) return ["start"];
  if (ticket.value.status === "PROCESSING" && operator)
    return ["waitConfirm", "resolve", "suspend"];
  if (ticket.value.status === "SUSPENDED" && operator) return ["resume"];
  if (ticket.value.status === "WAITING_CONFIRM" && operator)
    return ["resolve", "resume"];
  if (ticket.value.status === "RESOLVED") {
    const actions: TicketAction[] = [];
    if (isOwner.value || auth.isAdmin) actions.push("close");
    if (operator) actions.push("reopen");
    return actions;
  }
  return [];
});
const actionLabels = {
  accept: "接收工单",
  start: "开始处理",
  suspend: "挂起处理",
  resume: "恢复处理",
  waitConfirm: "提交业务确认",
  resolve: "标记已解决",
  reopen: "重新处理",
  close: "确认关闭",
};
async function load() {
  loading.value = true;
  error.value = "";
  try {
    [
      ticket.value,
      logs.value,
      documents.value,
      comments.value,
      workRecords.value,
      trace.value,
      sla.value,
    ] = await Promise.all([
      ticketApi.detail(id),
      ticketApi.logs(id),
      documentApi.list(id),
      ticketApi.comments(id),
      ticketApi.workRecords(id),
      ticketApi.trace(id),
      itsmApi.ticketSla(id),
    ]);
    questions.value = (await aiApi.page(id)).records;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  } finally {
    loading.value = false;
  }
}
async function doAction() {
  if (!action.value) return;
  busy.value = "action";
  try {
    ticket.value = await ticketApi.action(id, action.value, remark.value);
    toast.show(action.value === "accept" ? "工单已接取" : "工单状态已更新");
    action.value = "";
    remark.value = "";
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作失败";
  } finally {
    busy.value = "";
  }
}
async function upload() {
  if (!selectedFile.value) return;
  busy.value = "upload";
  try {
    await documentApi.upload(id, selectedFile.value);
    toast.show("文档已上传，请继续解析");
    selectedFile.value = undefined;
    documents.value = await documentApi.list(id);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "上传失败";
  } finally {
    busy.value = "";
  }
}

function selectFile(file?: File) {
  dragActive.value = false;
  if (!file) return;
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  if (!ACCEPTED_EXTENSIONS.includes(extension)) {
    selectedFile.value = undefined;
    error.value = "仅支持 PDF、DOCX、TXT 和 Markdown 文件";
    return;
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    selectedFile.value = undefined;
    error.value = "文件不能超过 10 MB，请压缩或拆分后重新上传";
    return;
  }
  selectedFile.value = file;
  error.value = "";
}

function dropFile(event: DragEvent) {
  selectFile(event.dataTransfer?.files?.[0]);
}
async function parse(doc: DocumentRecord) {
  busy.value = `parse-${doc.id}`;
  try {
    await documentApi.parse(doc.id);
    for (let attempt = 0; attempt < 15; attempt += 1) {
      documents.value = await documentApi.list(id);
      const current = documents.value.find((item) => item.id === doc.id);
      if (current && !["PENDING", "PARSING"].includes(current.parseStatus)) break;
      await new Promise((resolve) => window.setTimeout(resolve, 2_000));
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : "解析失败";
    documents.value = await documentApi.list(id);
  } finally {
    busy.value = "";
  }
}
async function removeDoc(doc: DocumentRecord) {
  if (!confirm(`确认删除文档“${doc.originalName}”吗？`)) return;
  busy.value = `delete-${doc.id}`;
  try {
    await documentApi.remove(doc.id);
    documents.value = await documentApi.list(id);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "删除失败";
  } finally {
    busy.value = "";
  }
}
async function showChunks(doc: DocumentRecord) {
  busy.value = `chunk-${doc.id}`;
  try {
    chunks.value = await documentApi.chunks(doc.id);
    chunkDocument.value = doc;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "读取切片失败";
  } finally {
    busy.value = "";
  }
}
async function ask() {
  if (!question.value.trim()) return;
  busy.value = "ask";
  error.value = "";
  const asked = question.value.trim();
  const saved = reactive<AiQuestion>({
    id: Date.now(),
    ticketId: id,
    documentId: selectedDocument.value,
    userId: auth.user?.userId || 0,
    question: asked,
    answer: "",
    modelName: "正在检索知识库",
    status: "SUCCESS",
    references: [],
    createTime: new Date().toISOString(),
  });
  questions.value = [saved, ...questions.value];
  question.value = "";
  try {
    const answer = await streamRagAnswer({
      question: asked,
      documentId: selectedDocument.value,
      topK: 5,
    }, {
      onStatus: (message) => (saved.modelName = message),
      onSources: (rows) => (saved.references = rows),
      onToken: (delta) => {
        saved.answer = `${saved.answer || ""}${delta}`;
      },
    });
    saved.answer = answer.answer || saved.answer;
    saved.modelName = `${ragCompletionLabel(answer)} · ${ragAnswerLabel(answer)}`;
    saved.errorMessage = ragIncompleteMessage(answer);
    saved.costTimeMs = answer.latencyMs;
    saved.references = answer.references;
  } catch (e) {
    saved.status = "FAILED";
    saved.errorMessage = e instanceof Error ? e.message : "提问失败";
  } finally {
    busy.value = "";
  }
}

async function addComment() {
  const content = commentText.value.trim();
  if (!content || busy.value === "comment") return;
  busy.value = "comment";
  try {
    comments.value.push(await ticketApi.comment(id, content));
    commentText.value = "";
  } catch (e) {
    error.value = e instanceof Error ? e.message : "回复失败";
  } finally {
    busy.value = "";
  }
}
async function addWorkRecord() {
  const content = workRecordContent.value.trim();
  if (!content || busy.value === "work-record") return;
  busy.value = "work-record";
  try {
    workRecords.value.push(
      await ticketApi.addWorkRecord(id, {
        recordType: workRecordType.value,
        content,
        evidence: workRecordEvidence.value.trim() || undefined,
      }),
    );
    workRecordContent.value = "";
    workRecordEvidence.value = "";
    recordSaved.value = true;
    toast.show("处置记录已保存");
    clearTimeout(recordTimer);
    recordTimer = setTimeout(() => { recordSaved.value = false; }, 2200);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "处置记录保存失败";
  } finally {
    busy.value = "";
  }
}
const workRecordLabels: Record<WorkRecordType, string> = {
  DIAGNOSIS: "现象与诊断",
  ACTION: "执行动作",
  VERIFICATION: "验证结果",
  ROOT_CAUSE: "根因分析",
  BUSINESS_REPLY: "业务回复",
};
onMounted(load);
</script>
<template>
  <LoadingState v-if="loading && !ticket" class="page-loading" text="正在加载工单详情…" />
  <div v-else-if="ticket" class="detail-page ticket-detail-page">
    <DetailHeader :identifier="ticket.ticketNo" :title="ticket.title">
      <template #back><button class="text-button" @click="router.push('/tickets')"><ArrowLeft :size="16" />返回工单列表</button></template>
      <template #badges><PriorityIndicator :value="ticket.priority" /><StatusBadge :value="ticket.status" /></template>
      <template #meta>
        <dl class="ticket-header-meta">
          <div><dt>受影响服务</dt><dd><code>{{ affectedService }}</code></dd></div>
          <div><dt>当前处理人</dt><dd>{{ ticket.assigneeId ? "#" + ticket.assigneeId : "待分配" }}</dd></div>
          <div><dt>最后更新</dt><dd>{{ formatDateTime(ticket.updateTime) }}</dd></div>
        </dl>
      </template>
      <template #actions>
        <button v-if="availableActions.length === 1" class="button" :class="availableActions[0] === 'suspend' || availableActions[0] === 'reopen' ? 'secondary' : 'primary'" @click="action = availableActions[0]"><Check :size="16" />{{ actionLabels[availableActions[0]] }}</button>
        <details v-else-if="availableActions.length" class="ticket-action-menu"><summary class="button primary">处理工单</summary><div><button v-for="nextAction in availableActions" :key="nextAction" @click="action = nextAction">{{ actionLabels[nextAction] }}</button></div></details>
      </template>
      <template #tabs><nav class="ticket-detail-tabs" aria-label="工单详情视图"><button :class="{ active: activeTab === 'overview' }" :aria-pressed="activeTab === 'overview'" @click="activeTab = 'overview'">概览</button><button :class="{ active: activeTab === 'documents' }" :aria-pressed="activeTab === 'documents'" @click="activeTab = 'documents'">文档与问答</button><button :class="{ active: activeTab === 'records' }" :aria-pressed="activeTab === 'records'" @click="activeTab = 'records'">处置记录</button><button :class="{ active: activeTab === 'activity' }" :aria-pressed="activeTab === 'activity'" @click="activeTab = 'activity'">活动</button></nav></template>
    </DetailHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <div class="detail-grid" :class="{ 'detail-grid-full': activeTab === 'documents' || activeTab === 'records', 'detail-grid-activity': activeTab === 'activity' }">
      <div class="detail-main">
        <section v-show="activeTab === 'overview'" class="panel ticket-overview-panel">
          <header class="panel-header"><div><h3>工单概览</h3><p>问题、服务与责任信息</p></div></header>
          <DescriptionList class="ticket-overview-list">
            <div><dt>告警名称</dt><dd><code>{{ alertName }}</code></dd></div>
            <div v-if="descriptionParts.summary"><dt>告警摘要</dt><dd>{{ descriptionParts.summary }}</dd></div>
            <div><dt>问题描述</dt><dd>{{ descriptionParts.text }}</dd></div>
            <div><dt>受影响服务</dt><dd><code>{{ affectedService }}</code></dd></div>
            <div><dt>创建人</dt><dd>#{{ ticket.creatorId }}</dd></div>
            <div><dt>当前处理人</dt><dd>{{ ticket.assigneeId ? '#' + ticket.assigneeId : '待分配' }}</dd></div>
          </DescriptionList>
          <TechnicalMetadata :metadata="technicalMetadata" :preview-count="3" />
        </section>
        <section v-show="activeTab === 'documents'" class="panel">
          <header class="panel-header">
            <div>
              <h3>关联文档</h3>
            </div>
            <span class="panel-count">{{ documents.length }} 个文件</span>
          </header>
          <div
            v-if="canUpload"
            class="upload-strip upload-dropzone"
            :class="{ 'drag-active': dragActive }"
            @dragenter.prevent="dragActive = true"
            @dragover.prevent="dragActive = true"
            @dragleave.prevent="dragActive = false"
            @drop.prevent="dropFile"
          >
            <label
              ><Upload :size="19" /><span>{{
                selectedFile?.name || "拖拽文件到这里，或点击选择文件"
              }}</span
              ><input
                type="file"
                accept=".pdf,.docx,.txt,.md,.markdown"
                @change="(e) => selectFile((e.target as HTMLInputElement).files?.[0])"
              />
              <small>支持 PDF、DOCX、TXT、Markdown，单文件最大 10 MB</small>
            </label
            ><button
              class="button primary small"
              :disabled="!selectedFile || busy === 'upload'"
              @click="upload"
            >
              {{ busy === "upload" ? "上传中…" : "上传" }}
            </button>
          </div>
          <div v-if="!documents.length" class="empty-state small-empty">
            <FileText :size="30" /><span>还没有关联文档</span>
          </div>
          <div v-else class="document-list">
            <article v-for="doc in documents" :key="doc.id">
              <div class="file-icon">{{ doc.fileExtension.toUpperCase() }}</div>
              <div class="file-info">
                <strong>{{ doc.originalName }}</strong
                ><span
                  >{{ (doc.fileSize / 1024).toFixed(1) }} KB ·
                  {{ new Date(doc.createTime).toLocaleString("zh-CN") }}</span
                >
                <p v-if="doc.parseError" class="file-error">
                  {{ doc.parseError }}
                </p>
              </div>
              <StatusBadge :value="doc.parseStatus" />
              <div class="row-actions">
                <button
                  v-if="doc.parseStatus !== 'PARSING'"
                  class="icon-button"
                  title="解析或重新解析"
                  :disabled="busy === `parse-${doc.id}`"
                  @click="parse(doc)"
                >
                  <Play :size="16" /></button
                ><button
                  v-if="doc.parseStatus === 'SUCCESS'"
                  class="icon-button"
                  title="查看切片"
                  @click="showChunks(doc)"
                >
                  <Layers3 :size="16" /></button
                ><button
                  v-if="auth.isAdmin || doc.createBy === auth.user?.userId"
                  class="icon-button danger"
                  title="删除文档"
                  @click="removeDoc(doc)"
                >
                  <Trash2 :size="16" />
                </button>
              </div>
            </article>
          </div>
        </section>
        <section v-show="activeTab === 'documents'" class="panel ai-panel" data-motion="tab">
          <header class="panel-header">
            <div>
              <h3>文档智能问答</h3>
            </div>
            <Bot :size="26" />
          </header>
          <form class="question-form" @submit.prevent="ask">
            <select v-model="selectedDocument">
              <option :value="undefined">检索本工单全部已解析文档</option>
              <option
                v-for="doc in documents.filter(
                  (d) => d.parseStatus === 'SUCCESS',
                )"
                :key="doc.id"
                :value="doc.id"
              >
                {{ doc.originalName }}
              </option>
            </select>
            <div>
              <textarea
                v-model="question"
                maxlength="2000"
                rows="3"
                placeholder="例如：磁盘使用率超过 90% 时应该如何处理？"
              /><button
                class="button primary"
                :disabled="busy === 'ask' || !question.trim()"
              >
                <Send :size="17" />{{ busy === "ask" ? "生成中…" : "提交问题" }}
              </button>
            </div>
          </form>
          <div v-if="!questions.length" class="empty-state small-empty">
            <MessageSquareText :size="30" /><span
              >解析文档后，可以在这里基于内容提问</span
            >
          </div>
          <div v-else class="qa-list">
            <article v-for="qa in questions" :key="qa.id">
              <div class="qa-question">
                <span>Q</span><strong>{{ qa.question }}</strong
                ><time>{{
                  new Date(qa.createTime).toLocaleString("zh-CN")
                }}</time>
              </div>
              <div class="qa-answer ticket-answer">
                <span>A</span>
                <div>
                  <small v-if="qa.modelName" class="answer-model">{{ qa.modelName }}</small>
                  <AnswerContent
                    :content="qa.answer"
                  />
                  <p v-if="qa.errorMessage" class="rag-incomplete" role="status">{{ qa.errorMessage }}</p>
                </div>
              </div>
              <RagSources :references="qa.references || []" compact />
            </article>
          </div>
        </section>
        <section v-show="activeTab === 'records'" class="panel work-record-panel" data-motion="tab">
          <header class="panel-header">
            <div><h3>结构化处置记录</h3><p>把诊断依据、执行过程与验证结果留在一起</p></div>
            <Wrench :size="22" />
          </header>
          <form class="record-editor" @submit.prevent="addWorkRecord">
            <FormField label="记录类型" class="record-editor__type"><select v-model="workRecordType"><option v-for="(label, value) in workRecordLabels" :key="value" :value="value">{{ label }}</option></select></FormField>
            <FormField label="处置内容" class="record-editor__content"><textarea v-model.trim="workRecordContent" maxlength="2000" rows="3" placeholder="记录诊断依据、执行动作、根因或验证结论…" /></FormField>
            <FormField label="证据、命令或监控链接（选填）" class="record-editor__evidence"><input v-model.trim="workRecordEvidence" maxlength="1000" placeholder="输入证据、命令或监控链接" /></FormField>
            <div class="record-editor__actions"><span>{{ workRecordContent.length }} / 2000</span><ActionButton class="primary" :disabled="!workRecordContent.trim() || busy === 'work-record'" :loading="busy === 'work-record'" :success="recordSaved" loading-text="保存中…" success-text="已保存">保存处置记录</ActionButton></div>
          </form>
          <div v-if="workRecords.length" class="work-record-list">
            <article v-for="record in workRecords" :key="record.id">
              <span>{{ workRecordLabels[record.recordType] }}</span>
              <div><p>{{ record.content }}</p><code v-if="record.evidence">{{ record.evidence }}</code><small>记录 #{{ record.id }} · 用户 #{{ record.createBy }} · {{ new Date(record.createTime).toLocaleString("zh-CN") }}</small></div>
            </article>
          </div>
          <div v-else class="empty-state small-empty">还没有结构化处置记录</div>
        </section>
        <section v-show="activeTab === 'records'" class="panel comment-panel">
          <header class="panel-header">
            <div>
              <h3>处理记录与回复</h3>
            </div>
            <MessageSquareText :size="22" />
          </header>
          <div v-if="comments.length" class="comment-list">
            <article v-for="item in comments" :key="item.id">
              <div class="comment-avatar">{{ String(item.userId).slice(-2) }}</div>
              <div>
                <header><strong>用户 #{{ item.userId }}</strong><time>{{ new Date(item.createTime).toLocaleString('zh-CN') }}</time></header>
                <p>{{ item.content }}</p>
              </div>
            </article>
          </div>
          <div v-else class="empty-state small-empty">暂无处理回复</div>
          <form class="comment-form" @submit.prevent="addComment">
            <textarea
              v-model.trim="commentText"
              rows="3"
              maxlength="2000"
              placeholder="记录排查过程、处理结果或向相关人员回复…"
            />
            <button class="button primary" :disabled="!commentText.trim() || busy === 'comment'">
              <Send :size="16" />{{ busy === "comment" ? "发送中…" : "发送回复" }}
            </button>
          </form>
        </section>
        <section v-if="activeTab === 'activity'" class="panel timeline-panel">
          <header class="panel-header"><div><h3>状态时间线</h3><p>从创建到处理，核对每一次状态变化</p></div><span class="panel-count">{{ logs.length }} 条活动</span></header>
          <ol v-if="logs.length" class="timeline">
            <li v-for="log in logs" :key="log.id">
              <i />
              <div>
                <strong :title="log.operationType">{{ operationLabel(log.operationType) }}</strong>
                <span>{{ log.fromStatus ? `${statusLabel(log.fromStatus)} → ` : "" }}{{ statusLabel(log.toStatus) }}</span>
                <p v-if="log.remark">{{ log.remark }}</p>
                <time>#{{ log.operatorId }} · {{ formatDateTime(log.createTime) }}</time>
              </div>
            </li>
          </ol>
          <EmptyState v-else title="暂无活动记录" description="工单状态变化后会显示在这里" />
        </section>
      </div>
      <aside class="detail-aside" v-show="activeTab === 'overview' || activeTab === 'activity'">
        <section v-if="sla && activeTab === 'overview'" class="panel sla-detail-card">
          <header class="panel-header"><div><h3>SLA 计时</h3></div><Clock3 :size="20" /></header>
          <DescriptionList><div><dt>响应状态</dt><dd>{{ statusLabel(sla.responseStatus) }}</dd></div><div><dt>解决状态</dt><dd>{{ statusLabel(sla.resolutionStatus) }}</dd></div><div><dt>响应截止</dt><dd>{{ formatDateTime(String(sla.responseDeadline)) }}</dd></div><div><dt>解决截止</dt><dd>{{ formatDateTime(String(sla.resolutionDeadline)) }}</dd></div><div><dt>升级级别</dt><dd><code>L{{ sla.escalationLevel }}</code></dd></div></DescriptionList>
        </section>
        <section v-if="activeTab === 'activity'" class="panel trace-summary-panel">
          <header class="panel-header"><div><h3>关联记录</h3><p>分派、操作与事件投递</p></div><Database :size="20" /></header>
          <div class="trace-metrics"><span><strong>{{ trace?.assignments.length || 0 }}</strong>分派记录</span><span><strong>{{ trace?.operations.length || 0 }}</strong>操作记录</span><span><strong>{{ trace?.outboxEvents.length || 0 }}</strong>事件投递</span></div>
          <button class="button secondary trace-button" @click="traceOpen = true">查看链路详情</button>
        </section>
      </aside>
    </div>
    <BaseModal v-if="action" :title="actionLabels[action]" @close="action = ''"
      ><div class="action-confirm">
        <p>本操作会推进工单状态且不可回退，请确认业务处理已经完成。</p>
        <label
          >操作备注<textarea
            v-model.trim="remark"
            maxlength="512"
            rows="5"
            placeholder="选填：记录处理过程或结果"
          />
        </label>
      </div>
      <template #footer
        ><button class="button secondary" @click="action = ''">取消</button
        ><ActionButton
          class="primary"
          :loading="busy === 'action'"
          loading-text="提交中…"
          @click="doAction"
        >
          确认操作
        </ActionButton></template
      ></BaseModal
    ><BaseModal
      v-if="chunkDocument"
      :title="`${chunkDocument.originalName} · 文本切片`"
      wide
      @close="chunkDocument = undefined"
      ><div v-if="!chunks.length" class="empty-state">没有切片数据</div>
      <div v-else class="chunk-list">
        <article v-for="chunk in chunks" :key="chunk.id">
          <header>
            <strong>Chunk {{ chunk.chunkIndex }}</strong
            ><span
              >{{ chunk.tokenCount || 0 }} tokens<span v-if="chunk.pageNumber">
                · 第 {{ chunk.pageNumber }} 页</span
              ></span
            >
          </header>
          <p>{{ chunk.content }}</p>
        </article>
      </div></BaseModal>
    <BaseModal v-if="traceOpen" title="工单后台数据链路" wide @close="traceOpen = false">
      <div class="trace-detail">
        <section><h4>ticket</h4><p>ID #{{ ticket.id }} · version {{ ticket.version }} · {{ ticket.status }}</p></section>
        <section><h4>ticket_assignment</h4><div v-if="!trace?.assignments.length" class="muted">暂无分派记录</div><article v-for="item in trace?.assignments" :key="item.id"><code>#{{ item.id }}</code><span>处理人 #{{ item.assigneeId }} · {{ item.assignmentType }}</span><time>{{ new Date(item.createTime).toLocaleString("zh-CN") }}</time></article></section>
        <section><h4>ticket_operation_log</h4><article v-for="item in trace?.operations" :key="item.id"><code>#{{ item.id }}</code><span>{{ item.operation }} · 操作人 #{{ item.operatorId }}</span><time>{{ new Date(item.createTime).toLocaleString("zh-CN") }}</time></article></section>
        <section><h4>event_outbox → RabbitMQ</h4><article v-for="item in trace?.outboxEvents" :key="item.id"><code>#{{ item.id }}</code><span>{{ item.eventType }}</span><StatusBadge :value="item.status" /><time>{{ new Date(item.updateTime).toLocaleString("zh-CN") }}</time></article></section>
      </div>
    </BaseModal>
  </div>
  <EmptyState v-else class="page-loading" title="工单不存在或无权访问" />
</template>
