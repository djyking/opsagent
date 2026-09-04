<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
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
import { streamRagAnswer } from "@/api/rag-stream";
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
const documents = ref<DocumentRecord[]>([]);
const questions = ref<AiQuestion[]>([]);
const loading = ref(true);
const error = ref("");
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
      onToken: async (delta) => {
        saved.answer = `${saved.answer || ""}${delta}`;
        await nextTick();
        await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
      },
    });
    saved.answer = answer.answer || saved.answer;
    saved.modelName = `${answer.provider}/${answer.model}`;
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
  <div v-if="loading" class="loading-state page-loading">正在加载工单详情…</div>
  <div v-else-if="ticket" class="detail-page">
    <button class="text-button back-link" @click="router.push('/tickets')">
      <ArrowLeft :size="17" />返回工单列表
    </button>
    <section class="ticket-hero">
      <div class="ticket-hero-main">
        <div class="detail-badges">
          <StatusBadge :value="ticket.priority" /><StatusBadge
            :value="ticket.status"
          />
        </div>
        <span class="ticket-number">{{ ticket.ticketNo }}</span>
        <h2>{{ ticket.title }}</h2>
        <p>{{ ticket.description }}</p>
      </div>
      <div class="ticket-meta">
        <div>
          <span>创建人</span><strong>#{{ ticket.creatorId }}</strong>
        </div>
        <div>
          <span>当前处理人</span
          ><strong>{{
            ticket.assigneeId ? "#" + ticket.assigneeId : "尚未分配"
          }}</strong>
        </div>
        <div>
          <span>受影响 CI</span><strong>{{ ticket.affectedCiCode || "未关联" }}</strong>
        </div>
        <div>
          <span>最后更新</span
          ><strong>{{
            new Date(ticket.updateTime).toLocaleString("zh-CN")
          }}</strong>
        </div>
        <div v-if="availableActions.length" class="ticket-actions">
          <button
            v-for="nextAction in availableActions"
            :key="nextAction"
            class="button"
            :class="nextAction === 'suspend' || nextAction === 'reopen' ? 'secondary' : 'primary'"
            @click="action = nextAction"
          >
            <Check :size="18" />{{ actionLabels[nextAction] }}
          </button>
        </div>
      </div>
    </section>
    <div v-if="error" class="inline-error">
      {{ error }}<button @click="error = ''">×</button>
    </div>
    <div class="detail-grid">
      <div class="detail-main">
        <section class="panel">
          <header class="panel-header">
            <div>
              <span class="eyebrow">DOCUMENTS</span>
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
        <section class="panel ai-panel">
          <header class="panel-header">
            <div>
              <span class="eyebrow">DOCUMENT Q&A</span>
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
                    :content="qa.status === 'SUCCESS' ? qa.answer : qa.errorMessage"
                  />
                </div>
              </div>
              <RagSources :references="qa.references || []" compact />
            </article>
          </div>
        </section>
        <section class="panel work-record-panel">
          <header class="panel-header">
            <div><span class="eyebrow">RESOLUTION WORKBENCH</span><h3>结构化处置记录</h3></div>
            <Wrench :size="22" />
          </header>
          <form class="work-record-form" @submit.prevent="addWorkRecord">
            <select v-model="workRecordType">
              <option v-for="(label, value) in workRecordLabels" :key="value" :value="value">{{ label }}</option>
            </select>
            <textarea v-model.trim="workRecordContent" maxlength="2000" rows="3" placeholder="记录诊断依据、执行动作、根因或验证结论…" />
            <input v-model.trim="workRecordEvidence" maxlength="1000" placeholder="证据、命令或监控链接（选填）" />
            <button class="button primary" :disabled="!workRecordContent.trim() || busy === 'work-record'">{{ busy === "work-record" ? "保存中…" : "保存处置记录" }}</button>
          </form>
          <div v-if="workRecords.length" class="work-record-list">
            <article v-for="record in workRecords" :key="record.id">
              <span>{{ workRecordLabels[record.recordType] }}</span>
              <div><p>{{ record.content }}</p><code v-if="record.evidence">{{ record.evidence }}</code><small>记录 #{{ record.id }} · 用户 #{{ record.createBy }} · {{ new Date(record.createTime).toLocaleString("zh-CN") }}</small></div>
            </article>
          </div>
          <div v-else class="empty-state small-empty">还没有结构化处置记录</div>
        </section>
        <section class="panel comment-panel">
          <header class="panel-header">
            <div>
              <span class="eyebrow">COLLABORATION</span>
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
      </div>
      <aside class="detail-aside">
        <section v-if="sla" class="panel sla-detail-card">
          <header class="panel-header"><div><span class="eyebrow">SERVICE LEVEL</span><h3>SLA 计时</h3></div><Clock3 :size="20" /></header>
          <dl><div><dt>响应状态</dt><dd>{{ sla.responseStatus }}</dd></div><div><dt>解决状态</dt><dd>{{ sla.resolutionStatus }}</dd></div><div><dt>响应截止</dt><dd>{{ new Date(String(sla.responseDeadline)).toLocaleString("zh-CN") }}</dd></div><div><dt>解决截止</dt><dd>{{ new Date(String(sla.resolutionDeadline)).toLocaleString("zh-CN") }}</dd></div><div><dt>升级级别</dt><dd>L{{ sla.escalationLevel }}</dd></div></dl>
        </section>
        <section class="panel trace-summary-panel">
          <header class="panel-header"><div><span class="eyebrow">BACKEND TRACE</span><h3>后台数据链路</h3></div><Database :size="20" /></header>
          <div class="trace-metrics"><span><strong>{{ trace?.assignments.length || 0 }}</strong>ticket_assignment</span><span><strong>{{ trace?.operations.length || 0 }}</strong>ticket_operation_log</span><span><strong>{{ trace?.outboxEvents.length || 0 }}</strong>event_outbox</span></div>
          <button class="button secondary trace-button" @click="traceOpen = true">查看真实表记录</button>
        </section>
        <section class="panel timeline-panel">
          <header class="panel-header">
            <div>
              <span class="eyebrow">ACTIVITY</span>
              <h3>状态时间线</h3>
            </div>
            <Clock3 :size="21" />
          </header>
          <ol class="timeline">
            <li v-for="log in logs" :key="log.id">
              <i />
              <div>
                <strong>{{ log.operationType }}</strong
                ><span
                  >{{ log.fromStatus ? `${log.fromStatus} → ` : ""
                  }}{{ log.toStatus }}</span
                >
                <p v-if="log.remark">{{ log.remark }}</p>
                <time
                  >#{{ log.operatorId }} ·
                  {{ new Date(log.createTime).toLocaleString("zh-CN") }}</time
                >
              </div>
            </li>
          </ol>
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
        ><button
          class="button primary"
          :disabled="busy === 'action'"
          @click="doAction"
        >
          {{ busy === "action" ? "提交中…" : "确认操作" }}
        </button></template
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
  <div v-else class="empty-state page-loading">工单不存在或无权访问</div>
</template>
