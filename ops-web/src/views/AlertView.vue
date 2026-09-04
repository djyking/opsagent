<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RefreshCw, Siren } from "@lucide/vue";
import { itsmApi } from "@/api/modules";

const alerts = ref<Record<string, unknown>[]>([]);
const status = ref("firing");
const error = ref("");
async function load() {
  try { alerts.value = await itsmApi.alerts(status.value); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "告警加载失败"; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page">
    <section class="page-lead"><div><span class="eyebrow">ALERTMANAGER</span><h2>活动告警</h2><p>同一 fingerprint 重复 firing 只累计次数；resolved 记录恢复但不会自动关闭工单。</p></div><button class="button secondary" @click="load"><RefreshCw :size="16" />刷新</button></section>
    <section class="filter-bar alert-filter"><select v-model="status" @change="load"><option value="">全部状态</option><option value="firing">Firing</option><option value="resolved">Resolved</option></select></section>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section class="panel table-panel"><div v-if="!alerts.length" class="empty-state"><Siren :size="36" /><strong>当前没有匹配告警</strong><span>启用 webhook 后，告警将在这里聚合并关联工单。</span></div><div v-else class="responsive-table"><table><thead><tr><th>告警</th><th>状态</th><th>严重级别</th><th>服务 CI</th><th>次数</th><th>关联工单</th><th>最后发生</th></tr></thead><tbody><tr v-for="alert in alerts" :key="String(alert.id)"><td><strong>{{ alert.alertName }}</strong><small class="block-muted">{{ alert.fingerprint }}</small></td><td><span class="status-badge" :class="`status-${alert.currentStatus}`">{{ alert.currentStatus }}</span></td><td>{{ alert.severity }}</td><td>{{ alert.serviceCode || "未映射" }}</td><td>{{ alert.occurrenceCount }}</td><td><RouterLink v-if="alert.ticketId" :to="`/tickets/${alert.ticketId}`">{{ alert.ticketNo || `#${alert.ticketId}` }}</RouterLink><span v-else>无</span></td><td>{{ new Date(String(alert.lastSeenTime)).toLocaleString("zh-CN") }}</td></tr></tbody></table></div></section>
  </div>
</template>
