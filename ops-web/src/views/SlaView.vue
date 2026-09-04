<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { AlertTriangle, CheckCircle2, Clock3, TimerReset } from "@lucide/vue";
import { itsmApi } from "@/api/modules";

const rows = ref<Record<string, unknown>[]>([]);
const error = ref("");
const now = ref(Date.now());
const breached = computed(() => rows.value.filter((row) => row.resolutionStatus === "BREACHED").length);
const running = computed(() => rows.value.filter((row) => row.resolutionStatus === "RUNNING").length);

function remaining(value: unknown) {
  const milliseconds = new Date(String(value)).getTime() - now.value;
  const sign = milliseconds < 0 ? "已超时 " : "剩余 ";
  const minutes = Math.floor(Math.abs(milliseconds) / 60000);
  return `${sign}${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`;
}
async function load() {
  try { rows.value = await itsmApi.slaOverview(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "SLA 加载失败"; }
}
onMounted(() => {
  load();
  window.setInterval(() => (now.value = Date.now()), 1000);
});
</script>

<template>
  <div class="stack-page">
    <section class="page-lead"><div><span class="eyebrow">SERVICE LEVEL</span><h2>SLA 看板</h2><p>响应和解决计时自动运行，预警、超时及两级升级均进入 Outbox 事件链路。</p></div><button class="button secondary" @click="load"><TimerReset :size="17" />刷新</button></section>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section class="metric-grid"><article><span class="metric-icon blue"><Clock3 /></span><div><strong>{{ running }}</strong><small>计时中</small></div></article><article><span class="metric-icon red"><AlertTriangle /></span><div><strong>{{ breached }}</strong><small>已超时</small></div></article><article><span class="metric-icon green"><CheckCircle2 /></span><div><strong>{{ rows.length - running - breached }}</strong><small>已完成</small></div></article></section>
    <section class="panel table-panel"><div class="responsive-table"><table><thead><tr><th>工单</th><th>优先级</th><th>受影响 CI</th><th>响应 SLA</th><th>解决 SLA</th><th>倒计时</th><th>升级</th></tr></thead><tbody><tr v-for="row in rows" :key="String(row.id)"><td><RouterLink class="table-title" :to="`/tickets/${row.ticketId}`"><strong>{{ row.title }}</strong><span>{{ row.ticketNo }}</span></RouterLink></td><td>{{ row.priority }}</td><td>{{ row.affectedCiCode || "未关联" }}</td><td><span class="status-badge" :class="`status-${String(row.responseStatus).toLowerCase()}`">{{ row.responseStatus }}</span></td><td><span class="status-badge" :class="`status-${String(row.resolutionStatus).toLowerCase()}`">{{ row.resolutionStatus }}</span></td><td :class="{ 'danger-text': new Date(String(row.resolutionDeadline)).getTime() < now }">{{ remaining(row.resolutionDeadline) }}</td><td>L{{ row.escalationLevel }}</td></tr></tbody></table></div></section>
  </div>
</template>
