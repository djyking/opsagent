<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Search, Plus, RotateCw, ArrowUpRight, TicketCheck, Eye, Siren, CalendarClock, TimerReset } from "@lucide/vue";
import { itsmApi, ticketApi } from "@/api/modules";
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
const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const page = ref<PageResponse<Ticket>>({
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
  priority: "",
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
  loading.value = true;
  error.value = "";
  try {
    page.value = await ticketApi.page(
      Object.fromEntries(Object.entries(filters).filter(([, v]) => v !== "")),
    );
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  } finally {
    loading.value = false;
  }
}
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
    priority: "",
    pageNum: 1,
    pageSize: 10,
  });
  load();
}
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
  filters.keyword = String(route.query.keyword || "");
  filters.status = String(route.query.status || "");
  if (route.query.priority === "HIGH") filters.priority = "HIGH";
  await Promise.all([
    load(),
    itsmApi.cis({ type: "SERVICE" }).then((rows) => (cis.value = rows)),
  ]);
});
</script>
<template>
  <div class="stack-page ticket-list-page">
    <PageHeader title="事件处置" description="围绕同一事件，连续查看证据、诊断、审批与业务恢复结果。">
      <template #actions><button v-if="!auth.isDemo" class="button primary" @click="showCreate = true"><Plus :size="18" />报告问题</button></template>
      <template #tabs><nav class="ticket-detail-tabs" aria-label="事件与协作"><RouterLink to="/tickets" class="active" aria-current="page">事件队列</RouterLink><RouterLink to="/itsm/alerts"><Siren :size="16" />原始告警</RouterLink><RouterLink to="/itsm/sla"><TimerReset :size="16" />SLA 与时效</RouterLink><RouterLink to="/itsm/oncall"><CalendarClock :size="16" />值班协作</RouterLink></nav></template>
    </PageHeader>
    <ListSurface class="ticket-list-surface">
      <template #header><div><h3>需要跟进的事件</h3><p>沿用工单编号与责任流程，打开事件进入完整处置工作区</p></div><span class="panel-count">{{ page.total }} 项</span></template>
      <template #toolbar><FilterBar>
      <div class="search-box">
        <Search :size="18" /><input
          v-model.trim="filters.keyword"
          placeholder="搜索事件编号、标题或描述"
          @keyup.enter="
            filters.pageNum = 1;
            load();
          "
        />
      </div>
      <select
        v-model="filters.status"
        @change="
          filters.pageNum = 1;
          load();
        "
      >
        <option value="">全部状态</option>
        <option value="CREATED">待接单</option>
        <option value="ASSIGNED">已接单</option>
        <option value="PROCESSING">处理中</option>
        <option value="SUSPENDED">已挂起</option>
        <option value="WAITING_CONFIRM">待业务确认</option>
        <option value="RESOLVED">待确认</option>
        <option value="CLOSED">已关闭</option></select
      ><select
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
      </FilterBar></template>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />

      <LoadingState v-if="loading && !page.records.length" text="正在加载事件…" />
      <EmptyState v-else-if="!page.records.length" title="没有符合条件的事件" description="调整筛选条件，或报告一个需要处理的问题。" :icon="TicketCheck" />
      <template v-else>
        <div class="responsive-table" role="region" aria-label="事件列表" tabindex="0"><table class="ticket-table">
          <thead>
            <tr>
              <th>事件 / 受影响服务</th>
              <th>优先级</th>
              <th>状态</th>
              <th>创建人 / 处理人</th>
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
                  ><span>{{ ticket.ticketNo }} · {{ ticket.affectedCiCode || '待关联服务' }}</span></RouterLink
                >
              </td>
              <td><PriorityIndicator :value="ticket.priority" /></td>
              <td><StatusBadge :value="ticket.status" /></td>
              <td>
                <span class="ticket-assignment"><strong>{{ ticket.assigneeId ? "#" + ticket.assigneeId : "待分配" }}</strong><small>创建人 #{{ ticket.creatorId }}</small></span>
              </td>
              <td><time :title="formatDateTime(ticket.updateTime)">{{ formatRelativeTime(ticket.updateTime) }}</time></td>
              <td>
                <button class="icon-button" title="快速查看事件摘要" @click.stop="preview = ticket"><Eye :size="17" /></button>
                <RouterLink class="icon-button" :to="`/tickets/${ticket.id}`" title="进入事件处置工作区" @click.stop
                  ><ArrowUpRight :size="17"
                /></RouterLink>
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
