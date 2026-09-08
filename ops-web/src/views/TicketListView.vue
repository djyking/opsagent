<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { Search, Plus, RotateCw, ArrowUpRight, TicketCheck, Eye, Siren, CalendarClock, TimerReset, UserRound, Server, ArrowRight } from "@lucide/vue";
import { itsmApi, ticketApi } from "@/api/modules";
import { eventQueueApi, type QueueTicket, type QueueSummary } from '@/api/event-queue';
import { queueStageLabel, queueState, queueSla, queueStage } from '@/utils/event-queue';
import type { PageResponse, Ticket } from "@/types/api";
import BaseModal from "@/components/BaseModal.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PaginationBar from "@/components/PaginationBar.vue";
import PageHeader from "@/components/PageHeader.vue";
import FilterBar from "@/components/FilterBar.vue";
import EmptyState from "@/components/EmptyState.vue";
import LoadingState from "@/components/LoadingState.vue";
import InlineError from "@/components/InlineError.vue";
import ListSurface from "@/components/ListSurface.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import DescriptionList from "@/components/DescriptionList.vue";
import TechnicalMetadata from "@/components/TechnicalMetadata.vue";
import InlineNotice from "@/components/InlineNotice.vue";
import { formatDateTime, formatRelativeTime } from "@/utils/datetime";
import { parseTicketDescription } from "@/utils/ticket-description";
import { usePageFeedback } from "@/composables/usePageFeedback";
import ActionButton from "@/components/feedback/ActionButton.vue";
import { useAuthStore } from "@/stores/auth";
import '@/styles/pages/phase3-event-lists.css';
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const mineScope = computed(() => route.query.scope === 'mine');
let listEpoch = 0;
const now = ref(Date.now());
let clock: ReturnType<typeof setInterval> | undefined;
onBeforeUnmount(() => { listEpoch++; clearInterval(clock); });
const summary = ref<QueueSummary>();
const summaryError = ref('');
const page = ref<PageResponse<QueueTicket>>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 10,
});
const loading = ref(false);
const error = ref("");
const toast = usePageFeedback(error, load);
const showCreate = ref(false);
const creating = ref(false);
const preview = ref<Ticket>();
const previewDescription = computed(() => parseTicketDescription(preview.value?.description));
function sourceTypeLabel(value: string) { return ({ ALERTMANAGER: "真实告警建单", ISOLATED_DRILL: "隔离演练 · 真实告警", MANUAL: "人工创建" } as Record<string, string>)[value] || value || "人工创建"; }
const filters = reactive({
  keyword: "",
  status: "",
  eventScope: 'OPEN',
  priority: "",
  affectedCiCode: '', assigneeId: '', eventStage: '',
  pageNum: 1,
  pageSize: 10,
});
const cis = ref<Record<string, unknown>[]>([]);
const form = reactive({
  title: "",
  description: "",
  priority: "MEDIUM",
  affectedCiCode: "",
});
async function load() {
  const epoch = ++listEpoch;
  loading.value = true;
  error.value = "";
  try {
    const params = { ...Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== '')), ...(mineScope.value ? { assigneeId: auth.user?.userId } : {}) };
    const query = Object.fromEntries(Object.entries(params).map(([key, value]) => [key, String(value)]));
    await router.replace({ query: { ...query, ...(mineScope.value ? { scope: 'mine' } : {}) } });
    const results = await Promise.allSettled([eventQueueApi.page(params), eventQueueApi.summary(params)]);
    if (epoch !== listEpoch) return;
    if (results[0].status === 'fulfilled') page.value = results[0].value; else throw results[0].reason;
    if (results[1].status === 'fulfilled') { summary.value = results[1].value; summaryError.value = ''; } else { summaryError.value = '队列汇总未更新'; }
  } catch (e) {
    if (epoch === listEpoch) error.value = e instanceof Error ? e.message : "加载失败";
  } finally {
    if (epoch === listEpoch) loading.value = false;
  }
}
const actorKey = () => `${auth.user?.userId ?? ''}:${auth.user?.roles?.join(',') ?? ''}`;
const scrollKey = () => `opsagent-event-queue-scroll:${actorKey()}:${JSON.stringify(filters)}:${mineScope.value}`;
onBeforeRouteLeave(() => { try { sessionStorage.setItem(scrollKey(), String(window.scrollY)); } catch { /* Navigation remains available without session storage. */ } });
watch(actorKey, () => { listEpoch++; page.value = { records: [], total: 0, pageNum: 1, pageSize: 10 }; summary.value = undefined; preview.value = undefined; showCreate.value = false; error.value = ''; cis.value = []; if (auth.user) { void load(); void loadCis(); } });
async function loadCis() { const actor = actorKey(); try { const rows = await itsmApi.cis({ type: 'SERVICE' }); if (actor === actorKey()) cis.value = rows; } catch { /* Optional create selector cannot block the queue. */ } }
async function create() {
  if (creating.value) return;
  creating.value = true;
  error.value = "";
  try {
    const ticket = await ticketApi.create(form);
    toast.show("工单已创建");
    showCreate.value = false;
    await router.push(`/tickets/${ticket.id}`);
  } catch (e) {
    error.value = e instanceof Error ? e.message : "创建失败";
  } finally {
    creating.value = false;
  }
}
function reset() {
  Object.assign(filters, {
    keyword: "",
    status: "",
    eventScope: 'OPEN',
    priority: "",
    affectedCiCode: '', assigneeId: '', eventStage: '',
    pageNum: 1,
    pageSize: 10,
  });
  load();
}
watch(mineScope, () => { filters.pageNum = 1; void load(); });
watch(
  () => route.query.create,
  (v) => {
    if (v === "1") {
      showCreate.value = true;
      router.replace({ query: { ...route.query, create: undefined } });
    }
  },
  { immediate: true },
);
// Query-only navigation keeps this view mounted, so refresh the queue explicitly.
watch(
  () => route.query.keyword,
  (value, previous) => {
    if (route.name !== "tickets") return;
    const keyword = String(value || "");
    if (keyword === String(previous || "")) return;
    filters.keyword = keyword;
    filters.pageNum = 1;
    load();
  },
);
onMounted(async () => {
  clock = setInterval(() => { now.value = Date.now(); }, 30_000);
  filters.keyword = String(route.query.keyword || "");
  filters.status = String(route.query.status || "");
  for (const key of ['priority', 'eventScope', 'affectedCiCode', 'assigneeId', 'eventStage'] as const) if (route.query[key]) filters[key] = String(route.query[key]);
  filters.pageNum = Math.max(1, Number(route.query.pageNum) || 1);
  await Promise.all([
    load(),
    loadCis(),
  ]);
  await nextTick();
  try { const saved = Number(sessionStorage.getItem(scrollKey())); if (Number.isFinite(saved)) window.scrollTo({ top: saved, behavior: 'instant' }); } catch { /* Queue remains usable without storage. */ }
});
</script>
<template>
  <div class="stack-page ticket-list-page">
    <PageHeader title="事件处置" >
      <template #actions><button v-if="!auth.isDemo" class="button primary" @click="showCreate = true"><Plus :size="18" />报告问题</button></template>
      <template #tabs><nav class="ticket-detail-tabs" aria-label="事件与协作"><RouterLink to="/tickets" class="active" aria-current="page">事件队列</RouterLink><RouterLink to="/itsm/alerts"><Siren :size="16" />原始告警</RouterLink><RouterLink to="/itsm/sla"><TimerReset :size="16" />SLA 与时效</RouterLink><RouterLink to="/itsm/oncall"><CalendarClock :size="16" />值班协作</RouterLink></nav></template>
    </PageHeader>
    <section class="panel event-queue-summary" aria-label="事件队列汇总"><button @click="filters.eventScope = 'OPEN'; filters.eventStage = ''; filters.pageNum = 1; load()">未关闭事件 <strong>{{ summary?.counts.open ?? '—' }}</strong></button><button @click="filters.eventScope = 'OPEN'; filters.eventStage = 'HANDLING'; filters.pageNum = 1; load()">事件处置 <strong>{{ summary?.counts.handling ?? '—' }}</strong></button><button @click="filters.eventScope = 'OPEN'; filters.eventStage = 'VERIFYING'; filters.pageNum = 1; load()">恢复验证 <strong>{{ summary?.counts.verifying ?? '—' }}</strong></button><RouterLink :to="mineScope ? '/tickets' : '/tickets?scope=mine'">{{ mineScope ? '查看全部可见事件' : '查看我负责的' }} →</RouterLink><small v-if="summaryError">{{ summaryError }}</small></section>
    <ListSurface class="ticket-list-surface">

      <template #toolbar><FilterBar>
      <select v-model="filters.eventScope" aria-label="事件关闭状态" @change="filters.pageNum = 1; load()"><option value="OPEN">未关闭事件</option><option value="CLOSED">已关闭事件</option><option value="ARCHIVED">历史档案</option><option value="">全部事件</option></select>
      <select v-model="filters.affectedCiCode" aria-label="受影响服务" @change="filters.pageNum = 1; load()"><option value="">全部服务</option><option v-for="code in summary?.services || []" :key="code" :value="code">{{ code }}</option></select>
      <select v-model="filters.assigneeId" aria-label="负责人" :disabled="mineScope" @change="filters.pageNum = 1; load()"><option value="">全部负责人</option><option v-for="person in summary?.assignees || []" :key="person.id" :value="String(person.id)">{{ person.name || `用户 #${person.id}` }}</option></select>
      <select
        v-model="filters.priority"
        @change="
          filters.pageNum = 1;
          load();
        "
      >
        <option value="">全部优先级</option>
        <option value="URGENT">紧急</option>
        <option value="HIGH">高</option>
        <option value="MEDIUM">中</option>
        <option value="LOW">低</option></select
      ><button class="button secondary" @click="reset">
        <RotateCw :size="16" />重置
      </button>
      <button class="button secondary" :disabled="loading" @click="load"><RotateCw :size="16" />刷新</button>
      <details class="event-advanced-filters" :open="!!filters.keyword || !!filters.eventStage"><summary>更多筛选{{ filters.keyword || filters.eventStage ? ' · 已启用' : '' }}</summary><div class="event-advanced-filter-body">      <div class="search-box">
        <Search :size="18" /><input
          v-model.trim="filters.keyword"
          placeholder="搜索事件编号、标题或描述"
          @keyup.enter="
            filters.pageNum = 1;
            load();
          "
        />
      </div>
      <select v-model="filters.eventStage" aria-label="当前环节" @change="filters.pageNum = 1; load()"><option value="">全部处理环节</option><option value="HANDLING">事件处置</option><option value="VERIFYING">恢复验证</option><option value="READY_TO_CLOSE">等待关闭</option></select>
</div></details>
      </FilterBar></template>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />

      <LoadingState v-if="loading && !page.records.length" text="正在加载事件…" />
      <EmptyState v-else-if="!page.records.length" title="没有符合条件的事件" description="调整筛选条件，或报告一个需要处理的问题。" :icon="TicketCheck" />
      <template v-else>
        <div class="responsive-table" role="region" aria-label="事件列表" tabindex="0"><table class="ticket-table">
          <thead>
            <tr>
              <th>事件标题 / ID</th><th>受影响服务</th>
              <th>优先级</th>
              <th>事件状态</th><th>当前环节</th>
              <th>负责人</th><th>SLA</th>
              <th>更新时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="ticket in page.records"
              :key="ticket.id"
              class="ticket-table-row"
              tabindex="0"
              @click="router.push(`/tickets/${ticket.id}`)"
              @keydown.enter.self="router.push(`/tickets/${ticket.id}`)"
            >
              <td>
                <RouterLink class="table-title table-title-button" :to="`/tickets/${ticket.id}`" @click.stop
                  ><strong>{{ ticket.title }}</strong
                  ><span>{{ ticket.eventId || `EVT-${ticket.id}` }}</span></RouterLink
                >
              </td>
              <td><span v-if="ticket.affectedCiCode" class="event-service-tag"><Server :size="13" />{{ ticket.affectedCiCode }}</span><span v-else class="event-service-missing">待关联服务</span></td><td><span class="event-priority-chip" :data-priority="ticket.priority" :title="ticket.priority">{{ ({ URGENT: 'P1', HIGH: 'P2', MEDIUM: 'P3', LOW: 'P4' } as Record<string, string>)[ticket.priority] || ticket.priority }}</span></td>
              <td><span class="event-state-label" :data-stage="queueStage(ticket)">{{ queueState(ticket) }}</span></td><td><span class="event-stage-chip" :data-stage="queueStage(ticket)">{{ queueStageLabel(ticket) }}</span></td>
              <td><span class="ticket-assignment"><span class="event-assignee-avatar" :class="{ empty: !ticket.assigneeId }"><UserRound v-if="!ticket.assigneeId" :size="14" /><template v-else>{{ (ticket.assigneeName || `#${ticket.assigneeId}`).slice(0, 1) }}</template></span><strong>{{ ticket.assigneeName || (ticket.assigneeId ? `用户 #${ticket.assigneeId}` : '待分配') }}</strong></span></td>
              <td><span class="event-sla" :data-tone="queueSla(ticket, now).tone">{{ queueSla(ticket, now).label }}<small>{{ queueSla(ticket, now).detail }}</small></span></td>
              <td><time :title="formatDateTime(ticket.updateTime)">{{ formatRelativeTime(ticket.updateTime) }}</time></td>
              <td>
                <button class="icon-button" title="快速查看事件摘要" @click.stop="preview = ticket"><Eye :size="17" /></button>
                <RouterLink class="event-enter" :to="`/tickets/${ticket.id}`" title="进入事件处置工作区" @click.stop
                  >{{ ticket.eventClosed || ticket.eventLegacyArchived ? '查看事件' : '进入处置' }} <ArrowRight :size="15" /></RouterLink>
              </td>
            </tr>
          </tbody>
        </table></div>
      </template>
      <template v-if="page.total" #footer><PaginationBar :page="filters.pageNum" :page-size="filters.pageSize" :total="page.total" @change="(p) => { filters.pageNum = p; load(); }" /></template>
    </ListSurface>
    <DetailPanel
      v-if="preview"
      :title="preview.title"
      :subtitle="preview.ticketNo"
      :full-path="`/tickets/${preview.id}`"
      @close="preview = undefined"
    >
      <div class="ticket-preview-badges">
        <PriorityIndicator :value="preview.priority" /><StatusBadge :value="preview.status" />
      </div>
      <DescriptionList class="ticket-preview-meta">
        <div><dt>负责人</dt><dd>{{ preview.assigneeId ? "#" + preview.assigneeId : "待分配" }}</dd></div>
        <div><dt>受影响服务</dt><dd><code>{{ previewDescription.affectedService || preview.affectedCiCode || "未关联" }}</code></dd></div>
        <div><dt>来源</dt><dd :title="preview.sourceType">{{ sourceTypeLabel(preview.sourceType) }}</dd></div>
        <div><dt>更新时间</dt><dd>{{ formatDateTime(preview.updateTime) }}</dd></div>
      </DescriptionList>
      <section class="ticket-preview-description">
        <h3>问题描述</h3><p>{{ previewDescription.text }}</p>
      </section>
      <TechnicalMetadata :metadata="previewDescription.metadata" :preview-count="3" />
      <InlineNotice>进入事件工作区可连续查看诊断证据、待审批动作、执行记录和业务恢复结果。</InlineNotice>
    </DetailPanel>
    <BaseModal
      v-if="showCreate && !auth.isDemo"
      title="报告运维问题"
      @close="showCreate = false"
      ><form class="form-grid" @submit.prevent="create">
        <label
          >工单标题<input
            v-model.trim="form.title"
            required
            maxlength="128"
            placeholder="简明描述问题，例如：生产服务器磁盘使用率告警" /></label
        ><label
          >优先级<select v-model="form.priority">
            <option value="LOW">低</option>
            <option value="MEDIUM">中</option>
            <option value="HIGH">高</option>
            <option value="URGENT">紧急</option>
          </select></label
        ><label
          >受影响 CI<select v-model="form.affectedCiCode">
            <option value="">暂不关联</option>
            <option v-for="ci in cis" :key="String(ci.ciCode)" :value="ci.ciCode">
              {{ ci.ciName }}（{{ ci.ciCode }}）
            </option>
          </select></label
        ><label class="full"
          >问题描述<textarea
            v-model.trim="form.description"
            required
            maxlength="10000"
            rows="7"
            placeholder="描述现象、影响范围、发生时间和已尝试的操作…"
          />
        </label>
        <p v-if="error" class="form-error full">{{ error }}</p>
        <div class="form-actions full">
          <button
            type="button"
            class="button secondary"
            @click="showCreate = false"
          >
            取消</button
          ><ActionButton class="primary" :loading="creating" loading-text="创建中…">创建工单</ActionButton>
        </div>
      </form></BaseModal
    >
  </div>
</template>
