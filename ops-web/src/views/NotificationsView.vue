<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Bell, CheckCircle2, XCircle } from "@lucide/vue";
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
const error = ref("");
const busy = ref<number>();
async function load() {
  try {
    data.value = await adminApi.notifications({
      pageNum: page.value,
      pageSize: 10,
    });
  } catch (e) {
    error.value = e instanceof Error ? e.message : "加载失败";
  }
}
async function mark(item: NotificationRecord, status: "SENT" | "FAILED") {
  busy.value = item.id;
  try {
    await adminApi.notificationStatus(item.id, status);
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
    </section>
    <section class="panel">
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
          <div v-if="item.status === 'PENDING'" class="row-actions">
            <button
              class="icon-button success"
              title="标记已发送"
              :disabled="busy === item.id"
              @click="mark(item, 'SENT')"
            >
              <CheckCircle2 :size="18" /></button
            ><button
              class="icon-button danger"
              title="标记失败"
              :disabled="busy === item.id"
              @click="mark(item, 'FAILED')"
            >
              <XCircle :size="18" />
            </button>
          </div>
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
