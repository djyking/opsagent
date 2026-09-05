<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Bell, CheckCheck, CheckCircle2, ExternalLink, Mail } from "@lucide/vue";
import { useRouter } from "vue-router";
import { adminApi } from "@/api/modules";
import type { NotificationRecord, PageResponse } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import PaginationBar from "@/components/PaginationBar.vue";
import PageHeader from "@/components/PageHeader.vue";
import FilterBar from "@/components/FilterBar.vue";
import ListSurface from "@/components/ListSurface.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import { formatDateTime, formatRelativeTime } from "@/utils/datetime";
import { operationLabel } from "@/ui/status-map";
import { usePageFeedback } from "@/composables/usePageFeedback";
const data = ref<PageResponse<NotificationRecord>>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 10,
});
const page = ref(1);
const status = ref("");
const unreadTotal = ref(0);
const error = ref("");
const toast = usePageFeedback(error, load);
const loading = ref(false);
const busy = ref<number>();
const router = useRouter();
const groups = computed(() => {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const key = (value: Date) => `${value.getFullYear()}-${value.getMonth()}-${value.getDate()}`;
  const labels = new Map<string, string>([
    [key(today), "今天"],
    [key(yesterday), "昨天"],
  ]);
  const result = new Map<string, NotificationRecord[]>();
  for (const item of data.value.records) {
    const date = new Date(item.createTime);
    const group = labels.get(key(date)) || date.toLocaleDateString("zh-CN");
    result.set(group, [...(result.get(group) || []), item]);
  }
  return [...result.entries()].map(([label, records]) => ({ label, records }));
});
function localizeContent(value: string) {
  const notificationOperations: Record<string, string> = { CREATED: "创建", CLAIMED: "接单", PROCESSING: "开始处理", WAITING_CONFIRM: "提交业务确认", RESOLVED: "解决", CLOSED: "关闭" };
  return value.replace(/['\"]([A-Z][A-Z0-9_]*)['\"]/g, (_match, raw) => `“${notificationOperations[raw] || operationLabel(raw)}”`);
}
async function load() {
  loading.value = true;
  error.value = "";
  try {
    const result = await adminApi.notifications({
      pageNum: page.value,
      pageSize: 10,
      status: status.value || undefined,
    });
    data.value = result;
    unreadTotal.value = result.unreadTotal;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  } finally {
    loading.value = false;
  }
}
async function setFilter(value: string) {
  status.value = value;
  page.value = 1;
  await load();
}
async function markAllRead() {
  busy.value = -1;
  try {
    await adminApi.readAllNotifications();
    toast.show("通知已全部标为已读");
    await load();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "批量更新失败";
  } finally {
    busy.value = undefined;
  }
}
async function updateStatus(item: NotificationRecord, value: "READ" | "UNREAD") {
  busy.value = item.id;
  try {
    await adminApi.notificationStatus(item.id, value);
    toast.show(value === 'READ' ? "通知已标为已读" : "通知已标为未读");
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "更新失败";
  } finally {
    busy.value = undefined;
  }
}
onMounted(load);
</script>
<template>
  <div class="stack-page notifications-page">
    <PageHeader title="通知中心" description="查看工单状态事件生成的站内通知处理记录">
      <template #meta><span>{{ unreadTotal }} 条未读</span></template>
      <template #actions>
        <button class="button secondary" :disabled="!unreadTotal || busy === -1" @click="markAllRead">
          <CheckCheck :size="16" />全部标记已读
        </button>
      </template>
    </PageHeader>
    <ListSurface class="notification-surface">
      <template #toolbar><FilterBar>
        <div class="record-toolbar">
        <button :class="{ active: !status }" @click="setFilter('')">全部</button>
        <button :class="{ active: status === 'UNREAD' }" @click="setFilter('UNREAD')"><Mail :size="14" />未读</button>
        <button :class="{ active: status === 'READ' }" @click="setFilter('READ')"><CheckCircle2 :size="14" />已读</button>
        </div>
        <span class="filter-result">{{ data.total }} 条通知</span>
      </FilterBar></template>
      <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
      <LoadingState v-if="loading && !data.records.length" text="正在加载通知…" />
      <EmptyState v-else-if="!data.records.length" title="暂无通知记录" description="新通知会按日期归入这里" :icon="Bell" />
      <div v-else class="notification-groups">
        <section v-for="group in groups" :key="group.label" class="notification-group">
          <h3>{{ group.label }}</h3>
        <article v-for="item in group.records" :key="item.id" class="notification-row" :class="{ unread: item.status === 'UNREAD' }">
          <div class="record-icon"><Bell :size="20" /></div>
          <div class="record-body">
            <header>
              <strong>{{ item.title }}</strong
              ><StatusBadge :value="item.status" />
            </header>
            <p>{{ localizeContent(item.content) }}</p>
            <span class="notification-meta"><RouterLink :to="`/tickets/${item.ticketId}`">工单 #{{ item.ticketId }}</RouterLink><span>接收人 #{{ item.receiver }}</span><time :title="formatDateTime(item.createTime)">{{ formatRelativeTime(item.createTime) }}</time></span>
          </div>
          <div v-if="item.status === 'UNREAD'" class="row-actions reveal-on-row">
            <button
              class="icon-button success"
              title="标记已读"
              :disabled="busy === item.id"
              @click="updateStatus(item, 'READ')"
            >
              <CheckCircle2 :size="18" />
            </button>
            <button class="icon-button" title="进入对应工单" @click="router.push(`/tickets/${item.ticketId}`)"><ExternalLink :size="17" /></button>
          </div>
          <div v-else class="row-actions reveal-on-row"><button class="icon-button" title="标记未读" :disabled="busy === item.id" @click="updateStatus(item, 'UNREAD')"><Mail :size="17" /></button><button class="icon-button" title="进入对应工单" @click="router.push(`/tickets/${item.ticketId}`)"><ExternalLink :size="17" /></button></div>
        </article>
        </section>
      </div>
      <template v-if="data.total" #footer><PaginationBar
        v-if="data.total"
        :page="page"
        :page-size="10"
        :total="data.total"
        @change="
          (p) => {
            page = p;
            load();
          }
        "
      /></template>
    </ListSurface>
  </div>
</template>
