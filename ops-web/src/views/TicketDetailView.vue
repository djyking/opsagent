<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
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
import EventWorkspace from "@/components/events/EventWorkspace.vue";
import { eventLifecycleApi, eventActionLabels, type EventLifecycle, type EventAction } from '@/api/event-lifecycle';
import { definitelyRejected, eventRecordAuthor } from '@/utils/event-workspace';

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
const activeTab = ref<"workspace" | "overview" | "documents" | "records" | "activity">("workspace");
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
const recordOpen = ref(false);
const recordUnconfirmed = ref(false);
const lifecycle = ref<EventLifecycle>();
const lifecycleError = ref('');
const eventAction = ref<EventAction>();
const eventContent = ref('');
const eventEvidence = ref('');
const eventActionError = ref('');
let eventAttempt: Parameters<typeof eventLifecycleApi.act>[1] | undefined;
const eventUnconfirmed = ref(false);
const needsEventEvidence = computed(() => !!eventAction.value && ['TECH_PASS', 'TECH_FAIL', 'BUSINESS_CONFIRM'].includes(eventAction.value));
function closeRecord() { if (busy.value === 'work-record') return; if ((workRecordContent.value || workRecordEvidence.value) && !window.confirm('处理记录尚未保存，关闭后保留本页输入，是否关闭？')) return; recordOpen.value = false; }
function openEventAction(value: EventAction) {
  if (eventUnconfirmed.value) return;
  eventAction.value = value; eventContent.value = ''; eventEvidence.value = ''; eventActionError.value = ''; eventAttempt = undefined;
}
function closeEventAction() { if (busy.value === 'event-action') return; if ((eventContent.value || eventEvidence.value) && !window.confirm('事件操作尚未提交，关闭后保留本页输入，是否关闭？')) return; eventAction.value = undefined; }
onBeforeRouteLeave(() => (!workRecordContent.value && !eventContent.value) || window.confirm('当前有未提交内容，确定离开？'));
async function refreshLifecycle() {
  try { lifecycle.value = await eventLifecycleApi.read(id); lifecycleError.value = ''; }
  catch (cause) { lifecycle.value = undefined; lifecycleError.value = cause instanceof Error ? cause.message : '事件确认规则读取失败'; }
}
async function submitEventAction() {
  if (!eventAction.value || !lifecycle.value || busy.value || !eventContent.value.trim() || (needsEventEvidence.value && !eventEvidence.value.trim())) return;
  busy.value = 'event-action'; eventActionError.value = '';
  if (!eventAttempt) eventAttempt = { action: eventAction.value, version: lifecycle.value.version, requestId: crypto.randomUUID(), content: eventContent.value.trim(), evidence: eventEvidence.value.trim() };
  eventUnconfirmed.value = true;
  try {
    lifecycle.value = await eventLifecycleApi.act(id, eventAttempt);
    eventAttempt = undefined; eventUnconfirmed.value = false; eventAction.value = undefined; eventContent.value = ''; eventEvidence.value = '';
    toast.show('事件操作已记录'); await load();
  } catch (cause) {
    const conflict = (cause as { status?: number })?.status === 409 || (cause as { code?: number })?.code === 40900;
    if (definitelyRejected(cause) || conflict) { eventAttempt = undefined; eventUnconfirmed.value = false; await refreshLifecycle(); }
    eventActionError.value = `${cause instanceof Error ? cause.message : '操作结果未确认'}${eventUnconfirmed.value ? '。重试将使用同一请求，不重复推进状态。' : ''}`;
  } finally { busy.value = ''; }
}
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
  if (ticket.value.status === "CREATED" && (auth.isOps || auth.isAdmin
      || (auth.isDemo && ticket.value.ownerActorId === auth.user?.userId && ticket.value.environment === "ISOLATED")))
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
  close: "关闭工单",
};
async function refreshTicket() {
  try { ticket.value = await ticketApi.detail(id); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '事件状态刷新失败'; }
  await refreshLifecycle();
}
async function openDocuments() {
  activeTab.value = 'documents';
  try { documents.value = await documentApi.list(id); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : '关联文档刷新失败'; }
}
async function load() {
  loading.value = true;
  error.value = "";
  const tasks = await Promise.allSettled([
    ticketApi.detail(id).then(value => { ticket.value = value; }),
    ticketApi.logs(id).then(value => { logs.value = value; }),
    documentApi.list(id).then(value => { documents.value = value; }),
    ticketApi.comments(id).then(value => { comments.value = value; }),
    ticketApi.workRecords(id).then(value => { workRecords.value = value; }),
    auth.isDemo ? Promise.resolve() : ticketApi.trace(id).then(value => { trace.value = value; }),
    itsmApi.ticketSla(id).then(value => { sla.value = value; }), refreshLifecycle(),
  ]);
  const failure = tasks.find(result => result.status === 'rejected');
  if (failure?.status === 'rejected') error.value = `部分资料未能读取：${failure.reason instanceof Error ? failure.reason.message : '请重试'}`;
  loading.value = false;
}
async function doAction() {
  if (!action.value) return;
  busy.value = "action";
  try {
    ticket.value = await ticketApi.action(id, action.value, remark.value, ticket.value?.version);
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
async function submitReview(doc: DocumentRecord) {
  if (busy.value || doc.createBy !== auth.user?.userId || doc.parseStatus !== 'SUCCESS') return;
  busy.value = `review-${doc.id}`;
  try {
    await documentApi.submitReview(doc.id);
    documents.value = await documentApi.list(id);
    toast.show('文档已提交知识审核，审核通过前不会作为已发布经验。');
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '提交审核失败'; }
  finally { busy.value = ''; }
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
      ticketId: id,
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
  if (!content || busy.value === "work-record" || recordUnconfirmed.value) return;
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
    recordOpen.value = false;
    toast.show("处置记录已保存");
    clearTimeout(recordTimer);
    recordTimer = setTimeout(() => { recordSaved.value = false; }, 2200);
  } catch (e) {
    recordUnconfirmed.value = !definitelyRejected(e);
    error.value = e instanceof Error ? e.message : "处置记录保存失败";
  } finally {
    busy.value = "";
  }
}
async function checkRecordResult() {
  if (busy.value || !recordUnconfirmed.value) return;
  busy.value = 'record-check';
  try {
    workRecords.value = await ticketApi.workRecords(id);
    const found = workRecords.value.find(row => row.createBy === auth.user?.userId && row.recordType === workRecordType.value && row.content === workRecordContent.value.trim() && (row.evidence || '') === workRecordEvidence.value.trim());
    if (found) { recordUnconfirmed.value = false; workRecordContent.value = ''; workRecordEvidence.value = ''; recordOpen.value = false; error.value = ''; toast.show('已找到保存的处理记录'); }
    else error.value = '暂未找到对应记录，输入继续保留。请稍后再核对，避免重复提交。';
  } catch (cause) { error.value = cause instanceof Error ? cause.message : '记录核对失败'; }
  finally { busy.value = ''; }
}
const workRecordLabels: Record<WorkRecordType, string> = {
  DIAGNOSIS: "现象与诊断",
  ACTION: "执行动作",
  VERIFICATION: "验证结果",
  ROOT_CAUSE: "根因分析",
  BUSINESS_REPLY: "业务回复",
};
onMounted(async () => {
  await load();
  if (route.query.record === '1' && ticket.value && !auth.isDemo) recordOpen.value = true;
});
</script>
<template>
  <LoadingState v-if="loading && !ticket" class="page-loading" text="正在加载事件详情…" />
  <div v-else-if="ticket" class="detail-page ticket-detail-page">
    <section v-if="activeTab === 'workspace'" class="event-compact-header"><div class="event-compact-heading"><h1>{{ ticket.title }}</h1><PriorityIndicator :value="ticket.priority" /><span class="event-state-label">{{ ticket.eventLegacyArchived ? '历史档案' : ticket.eventClosed ? '已关闭' : ticket.eventStage === 'VERIFYING' ? '恢复验证' : '处理中' }}</span><div class="event-compact-menu"><button v-if="availableActions.length === 1" class="button secondary" @click="action = availableActions[0]">{{ actionLabels[availableActions[0]] }}</button><details v-else-if="availableActions.length" class="ticket-action-menu"><summary class="button secondary">工单操作</summary><div><button v-for="nextAction in availableActions" :key="nextAction" @click="action = nextAction">{{ actionLabels[nextAction] }}</button></div></details><details class="ticket-action-menu"><summary class="button secondary">更多资料</summary><div><button @click="activeTab = 'overview'">事件资料</button><button @click="openDocuments">文档与问答</button><button @click="activeTab = 'records'">人工记录与回复</button><button @click="activeTab = 'activity'">工单状态历史</button></div></details></div></div><p>{{ ticket.eventId || `EVT-${ticket.id}` }} · {{ affectedService }} · 负责人 {{ ticket.assigneeId ? `#${ticket.assigneeId}` : '待分配' }} · 工单 {{ ticket.ticketNo }}<span v-if="sla"> · 解决截止 {{ formatDateTime(String(sla.resolutionDeadline)) }}</span></p></section>
    <DetailHeader v-else :identifier="ticket.ticketNo" :title="ticket.title">
      <template #back><button class="text-button" @click="router.push('/tickets')"><ArrowLeft :size="16" />返回事件队列</button></template>
      <template #badges><PriorityIndicator :value="ticket.priority" /><span class="event-state-label">{{ ticket.eventClosed ? '事件已关闭' : ticket.eventStage === 'VERIFYING' ? '恢复验证中' : '事件处理中' }}</span></template>
      <template #meta>
        <dl class="ticket-header-meta">
          <div><dt>受影响服务</dt><dd><code>{{ affectedService }}</code></dd></div>
          <div><dt>当前处理人</dt><dd>{{ ticket.assigneeId ? "#" + ticket.assigneeId : "待分配" }}</dd></div>
          <div><dt>最后更新</dt><dd>{{ formatDateTime(ticket.updateTime) }}</dd></div>
        </dl>
      </template>
      <template #actions>
        <router-link v-if="ticket.incidentId" class="button secondary" :to="{ path: '/automation', query: { ticketId: ticket.id } }">完整运行记录</router-link>
        <button v-if="availableActions.length === 1" class="button" :class="availableActions[0] === 'suspend' || availableActions[0] === 'reopen' ? 'secondary' : 'primary'" @click="action = availableActions[0]"><Check :size="16" />{{ actionLabels[availableActions[0]] }}</button>
        <details v-else-if="availableActions.length" class="ticket-action-menu"><summary class="button secondary">工单操作</summary><div><button v-for="nextAction in availableActions" :key="nextAction" @click="action = nextAction">{{ actionLabels[nextAction] }}</button></div></details>
      </template>
      <template #tabs><nav class="ticket-detail-tabs" aria-label="事件详情视图"><button :aria-pressed="false" @click="activeTab = 'workspace'">处置工作区</button><button :class="{ active: activeTab === 'overview' }" :aria-pressed="activeTab === 'overview'" @click="activeTab = 'overview'">事件资料</button><button :class="{ active: activeTab === 'documents' }" :aria-pressed="activeTab === 'documents'" @click="openDocuments">文档与问答</button><button :class="{ active: activeTab === 'records' }" :aria-pressed="activeTab === 'records'" @click="activeTab = 'records'">人工记录与回复</button><button :class="{ active: activeTab === 'activity' }" :aria-pressed="activeTab === 'activity'" @click="activeTab = 'activity'">活动时间线</button></nav></template>
    </DetailHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <EventWorkspace v-if="activeTab === 'workspace'" :ticket="ticket" :lifecycle="lifecycle" :lifecycle-error="lifecycleError" :records="workRecords" :logs="logs" :sla="sla" :can-resolve="availableActions.includes('resolve')" @refresh="load" @question="openDocuments" @documents="openDocuments" @records="recordOpen = true; workRecordType = 'ACTION'" @lifecycle="openEventAction" @resolve="action = 'resolve'" />
    <div v-show="activeTab !== 'workspace'" class="detail-grid" :class="{ 'detail-grid-full': activeTab === 'documents' || activeTab === 'records', 'detail-grid-activity': activeTab === 'activity' }">
      <div class="detail-main">
        <section v-show="activeTab === 'overview'" class="panel ticket-overview-panel">
          <header class="panel-header"><div><h3>事件资料</h3><p>原始症状、关联服务与责任信息</p></div></header>
          <DescriptionList class="ticket-overview-list">
            <div><dt>告警名称</dt><dd><code>{{ alertName }}</code></dd></div>
            <div v-if="descriptionParts.summary"><dt>告警摘要</dt><dd>{{ descriptionParts.summary }}</dd></div>
            <div><dt>问题描述</dt><dd>{{ descriptionParts.text }}</dd></div>
            <div><dt>受影响服务</dt><dd><code>{{ affectedService }}</code></dd></div>
            <div v-if="ticket.sourceType === 'ISOLATED_DRILL'"><dt>工单来源</dt><dd>隔离演练产生的真实监控告警</dd></div>
            <div v-if="ticket.episodeId"><dt>告警事件</dt><dd><code>{{ ticket.episodeId.slice(0, 16) }}</code></dd></div>
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
              <span v-if="doc.reviewStatus" class="muted">{{ ({ DRAFT: '待审核草稿', IN_REVIEW: '审核中', PUBLISHED: '已发布', REJECTED: '需修订', ARCHIVED: '已归档' } as Record<string, string>)[doc.reviewStatus] || doc.reviewStatus }}</span>
              <div class="row-actions">
                <button v-if="!auth.isDemo && doc.createBy === auth.user?.userId && doc.parseStatus === 'SUCCESS' && ['DRAFT', 'REJECTED'].includes(doc.reviewStatus || '')" class="button secondary" :disabled="!!busy" @click="submitReview(doc)">提交知识审核</button>
                <button
                  v-if="!auth.isDemo && doc.parseStatus !== 'PARSING'"
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
              <h3>文档与服务问答</h3>
            </div>
            <Bot :size="26" />
          </header>
          <div class="ticket-form-body">
          <form class="ticket-form-surface ticket-question-editor" @submit.prevent="ask">
            <FormField label="问答范围" :help="selectedDocument ? '仅依据所选文档回答。' : '文档问题检索本工单附件；服务清单与依赖问题查询当前服务目录。'">
            <select v-model="selectedDocument" aria-label="问答文档范围">
              <option :value="undefined">本工单附件 · 服务目录自动识别</option>
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
            </FormField>
            <FormField label="你的问题">
              <textarea
                v-model="question"
                maxlength="2000"
                rows="4"
                aria-label="工单问答问题"
                placeholder="例如：磁盘使用率超过 90% 时应该如何处理？"
              />
            </FormField>
            <div class="ticket-form-actions">
              <span>{{ question.length }} / 2000</span>
              <ActionButton class="primary" :loading="busy === 'ask'" :disabled="!question.trim()" loading-text="生成中…"><Send :size="16" />提交问题</ActionButton>
            </div>
          </form>
          </div>
          <div v-if="!questions.length" class="empty-state small-empty">
            <MessageSquareText :size="30" /><span
              >可以基于工单附件提问，也可以查询服务清单与依赖关系</span
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
          <div v-if="!auth.isDemo" class="ticket-form-body">
          <form class="ticket-form-surface record-editor" @submit.prevent="addWorkRecord">
            <FormField label="记录类型" class="record-editor__type"><select v-model="workRecordType"><option v-for="(label, value) in workRecordLabels" :key="value" :value="value">{{ label }}</option></select></FormField>
            <FormField label="处置内容" class="record-editor__content"><textarea v-model.trim="workRecordContent" maxlength="2000" rows="3" placeholder="记录诊断依据、执行动作、根因或验证结论…" /></FormField>
            <FormField label="证据、命令或监控链接（选填）" class="record-editor__evidence"><input v-model.trim="workRecordEvidence" maxlength="1000" placeholder="输入证据、命令或监控链接" /></FormField>
            <div class="ticket-form-actions record-editor__actions"><span>{{ workRecordContent.length }} / 2000</span><ActionButton class="primary" :disabled="!workRecordContent.trim() || busy === 'work-record'" :loading="busy === 'work-record'" :success="recordSaved" loading-text="保存中…" success-text="已保存">保存处置记录</ActionButton></div>
          </form>
          </div>
          <div v-if="workRecords.length" class="work-record-list">
            <article v-for="record in workRecords" :key="record.id">
              <span>{{ workRecordLabels[record.recordType] }}</span>
              <div><p>{{ record.content }}</p><code v-if="record.evidence">{{ record.evidence }}</code><small>记录 #{{ record.id }} · {{ eventRecordAuthor(record) }} · {{ new Date(record.createTime).toLocaleString("zh-CN") }}</small></div>
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
          <div v-if="!auth.isDemo" class="ticket-form-body">
          <form class="ticket-form-surface ticket-comment-editor" @submit.prevent="addComment">
            <FormField label="处理回复">
            <textarea
              v-model.trim="commentText"
              rows="3"
              maxlength="2000"
              placeholder="记录排查过程、处理结果或向相关人员回复…"
            />
            </FormField>
            <div class="ticket-form-actions"><span>{{ commentText.length }} / 2000</span><ActionButton class="primary" :loading="busy === 'comment'" :disabled="!commentText.trim()" loading-text="发送中…"><Send :size="16" />发送回复</ActionButton></div>
          </form>
          </div>
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
        <section v-if="activeTab === 'activity' && !auth.isDemo" class="panel trace-summary-panel">
          <header class="panel-header"><div><h3>关联记录</h3><p>分派、操作与事件投递</p></div><Database :size="20" /></header>
          <div class="trace-metrics"><span><strong>{{ trace?.assignments.length || 0 }}</strong>分派记录</span><span><strong>{{ trace?.operations.length || 0 }}</strong>操作记录</span><span><strong>{{ trace?.outboxEvents.length || 0 }}</strong>事件投递</span></div>
          <button class="button secondary trace-button" @click="traceOpen = true">查看链路详情</button>
        </section>
      </aside>
    </div>
    <BaseModal v-if="recordOpen" title="记录处理" @close="closeRecord"><form class="event-dialog-body" @submit.prevent="addWorkRecord"><p>工单 {{ ticket.ticketNo }} · 当前处理人 {{ ticket.assigneeId ? `用户 #${ticket.assigneeId}` : '待分配' }}</p><InlineError v-if="error" :message="error" /><FormField label="操作备注 *"><textarea v-model.trim="workRecordContent" required maxlength="2000" rows="7" :disabled="recordUnconfirmed || !!busy" placeholder="记录已核实的现象、处理动作和实际结果" /></FormField><details><summary>补充证据与记录类型</summary><FormField label="记录类型"><select v-model="workRecordType" :disabled="recordUnconfirmed || !!busy"><option v-for="(label, value) in workRecordLabels" :key="value" :value="value">{{ label }}</option></select></FormField><FormField label="证据（选填）"><input v-model.trim="workRecordEvidence" maxlength="1000" :disabled="recordUnconfirmed || !!busy" /></FormField></details><p>仅保存处理记录，不执行生产变更，也不提交恢复确认。</p><p v-if="recordUnconfirmed" class="event-notice">保存结果未确认，请刷新人工记录核对是否已保存；当前输入已保留，暂停重复提交。</p><button v-if="recordUnconfirmed" type="button" class="button secondary" :disabled="!!busy" @click="checkRecordResult">核对保存结果</button><button class="button primary" :disabled="!workRecordContent || !!busy || recordUnconfirmed">{{ busy === 'work-record' ? '保存中…' : '确认记录' }}</button></form></BaseModal>
    <BaseModal v-if="eventAction" :title="eventActionLabels[eventAction]" @close="closeEventAction"><form class="event-dialog-body" @submit.prevent="submitEventAction"><p>事件 {{ lifecycle?.eventId }} · 本次操作由用户 #{{ auth.user?.userId }} 独立留痕。</p><InlineError v-if="eventActionError" :message="eventActionError" /><FormField :label="eventAction === 'RESULT' ? '实际处理结果 *' : '确认说明 *'"><textarea v-model.trim="eventContent" required maxlength="2000" rows="5" :disabled="eventUnconfirmed || !!busy" :placeholder="eventAction === 'TECH_PASS' ? '填写实际检查范围、指标或探针结果、采样时间与稳定观察结论' : '记录本次操作的事实与依据'" /></FormField><FormField :label="needsEventEvidence ? '检查结果或证据来源 *' : '证据来源（选填）'"><textarea v-model.trim="eventEvidence" :required="needsEventEvidence" maxlength="1000" rows="3" :disabled="eventUnconfirmed || !!busy" placeholder="填写检查结果、监控链接、采样时间或业务反馈依据" /></FormField><p v-if="eventAction === 'TECH_PASS'">这里记录人工核对的技术结论；机器探针证据可在验证明细中核对。缺少实际证据时请选择验证未通过。</p><p v-if="eventAction === 'BUSINESS_CONFIRM'">{{ lifecycle?.confirmationScope === 'VISITOR_DRILL' ? '本次记录的是你对隔离演练业务的确认，不代表正式业务验收；请填写实际业务请求结果。' : '业务确认与技术验证分别记录；管理员可以兼任确认人。' }}</p><p v-if="eventAction === 'CLOSE'">本次只关闭事件，知识仍需另行整理和审核发布。</p><button class="button primary" :disabled="!!busy || !eventContent || (needsEventEvidence && !eventEvidence)">{{ busy ? '提交中…' : eventUnconfirmed ? '使用同一请求重试' : '确认提交' }}</button></form></BaseModal>
    <BaseModal v-if="action" :title="actionLabels[action]" @close="action = ''"
      ><div class="action-confirm">
        <p>本操作更新工单状态。处理结果、技术恢复、业务确认及事件关闭分别记录。</p>
        <FormField label="操作备注"><textarea
            v-model.trim="remark"
            maxlength="512"
            rows="5"
            placeholder="选填：记录处理过程或结果"
          />
        </FormField>
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
