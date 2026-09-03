<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Bell, CheckCheck, CheckCircle2, ExternalLink, Mail } from "@lucide/vue";
import { useRouter } from "vue-router";
import { adminApi } from "@/api/modules";
import type { NotificationRecord, PageResponse } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import PaginationBar from "@/components/PaginationBar.vue";
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
const busy = ref<number>();
const router = useRouter();
async function load() {
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
  <div class="stack-page">
    <section class="page-lead">
      <div>
        <span class="eyebrow">NOTIFICATION RECORDS</span>
        <h2>通知中心</h2>
        <p>查看工单状态事件生成的站内通知处理记录。</p>
      </div>
      <button class="button secondary" :disabled="!unreadTotal || busy === -1" @click="markAllRead">
        <CheckCheck :size="16" />全部标记已读（{{ unreadTotal }}）
      </button>
    </section>
    <section class="panel">
      <div class="record-toolbar">
        <button :class="{ active: !status }" @click="setFilter('')">全部</button>
        <button :class="{ active: status === 'UNREAD' }" @click="setFilter('UNREAD')"><Mail :size="14" />未读</button>
        <button :class="{ active: status === 'READ' }" @click="setFilter('READ')"><CheckCircle2 :size="14" />已读</button>
      </div>
      <div v-if="error" class="inline-error">{{ error }}</div>
      <div v-if="!data.records.length" class="empty-state">
        <Bell :size="36" /><strong>暂无通知记录</strong>
      </div>
      <div v-else class="record-list">
        <article v-for="item in data.records" :key="item.id">
          <div class="record-icon"><Bell :size="20" /></div>
          <div class="record-body">
            <header>
              <strong>{{ item.title }}</strong
              ><StatusBadge :value="item.status" />
            </header>
            <p>{{ item.content }}</p>
            <span
              >工单 #{{ item.ticketId }} · 接收人 #{{ item.receiver }} ·
              {{ new Date(item.createTime).toLocaleString("zh-CN") }}</span
            >
          </div>
          <div v-if="item.status === 'UNREAD'" class="row-actions">
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
          <div v-else class="row-actions"><button class="icon-button" title="标记未读" :disabled="busy === item.id" @click="updateStatus(item, 'UNREAD')"><Mail :size="17" /></button><button class="icon-button" title="进入对应工单" @click="router.push(`/tickets/${item.ticketId}`)"><ExternalLink :size="17" /></button></div>
        </article>
      </div>
      <PaginationBar
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
      />
    </section>
  </div>
</template>
