<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  ArrowUpRight,
  CircleDot,
  Clock3,
  CheckCircle2,
  Archive,
  Plus,
  Sparkles,
} from "@lucide/vue";
import { ticketApi } from "@/api/modules";
import type { Ticket } from "@/types/api";
import StatusBadge from "@/components/StatusBadge.vue";
import { useAuthStore } from "@/stores/auth";
const auth = useAuthStore();
const tickets = ref<Ticket[]>([]);
const loading = ref(true);
const counts = computed(() =>
  Object.fromEntries(
    ["CREATED", "PROCESSING", "RESOLVED", "CLOSED"].map((s) => [
      s,
      tickets.value.filter((t) => t.status === s).length,
    ]),
  ),
);
onMounted(async () => {
  try {
    tickets.value = (
      await ticketApi.page({ pageNum: 1, pageSize: 100 })
    ).records;
  } finally {
    loading.value = false;
  }
});
const date = new Intl.DateTimeFormat("zh-CN", {
  month: "long",
  day: "numeric",
  weekday: "long",
}).format(new Date());
</script>
<template>
  <div class="dashboard-page">
    <section class="welcome-band">
      <div>
        <span class="eyebrow">{{ date }}</span>
        <h2>上午好，{{ auth.user?.displayName || auth.user?.username }}</h2>
        <p>这里是你当前权限范围内的工单运行状态。</p>
      </div>
      <RouterLink class="button light" to="/tickets?create=1"
        ><Plus :size="18" />新建工单</RouterLink
      ><Sparkles class="welcome-spark" :size="100" />
    </section>
    <section class="metric-grid">
      <article>
        <span class="metric-icon blue"><CircleDot /></span>
        <div>
          <span>待接单</span><strong>{{ counts.CREATED || 0 }}</strong>
        </div>
        <small>等待运维响应</small>
      </article>
      <article>
        <span class="metric-icon amber"><Clock3 /></span>
        <div>
          <span>处理中</span><strong>{{ counts.PROCESSING || 0 }}</strong>
        </div>
        <small>正在排查处理</small>
      </article>
      <article>
        <span class="metric-icon green"><CheckCircle2 /></span>
        <div>
          <span>待确认</span><strong>{{ counts.RESOLVED || 0 }}</strong>
        </div>
        <small>等待创建人关闭</small>
      </article>
      <article>
        <span class="metric-icon slate"><Archive /></span>
        <div>
          <span>已关闭</span><strong>{{ counts.CLOSED || 0 }}</strong>
        </div>
        <small>已完成业务闭环</small>
      </article>
    </section>
    <section class="panel">
      <header class="panel-header">
        <div>
          <span class="eyebrow">RECENT TICKETS</span>
          <h3>最近工单</h3>
        </div>
        <RouterLink class="text-button" to="/tickets"
          >查看全部 <ArrowUpRight :size="16"
        /></RouterLink>
      </header>
      <div v-if="loading" class="loading-state">正在加载工单…</div>
      <div v-else-if="!tickets.length" class="empty-state">
        <CircleDot :size="34" /><strong>还没有工单</strong
        ><span>创建第一张工单开始处理运维问题。</span>
      </div>
      <div v-else class="ticket-list compact-list">
        <RouterLink
          v-for="ticket in tickets.slice(0, 6)"
          :key="ticket.id"
          :to="`/tickets/${ticket.id}`"
          ><div class="ticket-id">{{ ticket.ticketNo }}</div>
          <div class="ticket-main">
            <strong>{{ ticket.title }}</strong
            ><span>{{
              new Date(ticket.createTime).toLocaleString("zh-CN")
            }}</span>
          </div>
          <StatusBadge :value="ticket.priority" /><StatusBadge
            :value="ticket.status" /><ArrowUpRight :size="17"
        /></RouterLink>
      </div>
    </section>
  </div>
</template>
