<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Search, Plus, RotateCw, ArrowUpRight, TicketCheck } from "@lucide/vue";
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
const showCreate = ref(false);
const creating = ref(false);
const preview = ref<Ticket>();
const previewDescription = computed(() => parseTicketDescription(preview.value?.description));
function sourceTypeLabel(value: string) { return value === "ALERTMANAGER" ? "告警自动建单" : value || "人工创建"; }
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
  creating.value = true;
  error.value = "";
  try {
    const ticket = await ticketApi.create(form);
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
    if (v === "1") showCreate.value = true;
  },
  { immediate: true },
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
  <div class="stack-page">
    <PageHeader title="工单中心" description="创建、跟踪并完成每一个运维问题闭环">
      <template #actions><button class="button primary" @click="showCreate = true"><Plus :size="18" />新建工单</button></template>
    </PageHeader>
    <ListSurface>
      <template #toolbar><FilterBar>
      <div class="search-box">
        <Search :size="18" /><input
          v-model.trim="filters.keyword"
          placeholder="搜索工单编号、标题或描述"
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

      <LoadingState v-if="loading" text="正在加载工单…" />
      <EmptyState v-else-if="!page.records.length" title="没有符合条件的工单" description="调整筛选条件或创建一张新工单" :icon="TicketCheck" />
      <template v-else>
        <table>
          <thead>
            <tr>
              <th>工单</th>
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
              @click="preview = ticket"
              @keydown.enter="preview = ticket"
            >
              <td>
                <button class="table-title table-title-button" @click.stop="preview = ticket"
                  ><strong>{{ ticket.title }}</strong
                  ><span>{{ ticket.ticketNo }}</span></button
                >
              </td>
              <td><PriorityIndicator :value="ticket.priority" /></td>
              <td><StatusBadge :value="ticket.status" /></td>
              <td>
                <span class="identity-pair"
                  >#{{ ticket.creatorId }} <i>→</i>
                  {{
                    ticket.assigneeId ? "#" + ticket.assigneeId : "待分配"
                  }}</span
                >
              </td>
              <td><time :title="formatDateTime(ticket.updateTime)">{{ formatRelativeTime(ticket.updateTime) }}</time></td>
              <td>
                <RouterLink class="icon-button" :to="`/tickets/${ticket.id}`" @click.stop
                  ><ArrowUpRight :size="17"
                /></RouterLink>
              </td>
            </tr>
          </tbody>
        </table>
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
      <InlineNotice>完整页面包含 SLA、处理记录、关联告警、CMDB、附件与 AI 分析。</InlineNotice>
    </DetailPanel>
    <BaseModal
      v-if="showCreate"
      title="新建运维工单"
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
          ><button class="button primary" :disabled="creating">
            {{ creating ? "创建中…" : "创建工单" }}
          </button>
        </div>
      </form></BaseModal
    >
  </div>
</template>
