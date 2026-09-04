<script setup lang="ts">
import { onMounted, ref } from "vue";
import { RefreshCw, Siren } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import PageHeader from "@/components/PageHeader.vue";
import FilterBar from "@/components/FilterBar.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import ListSurface from "@/components/ListSurface.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import PriorityIndicator from "@/components/PriorityIndicator.vue";
import { formatDateTime, formatRelativeTime } from "@/utils/datetime";
import { statusLabel } from "@/ui/status-map";

const alerts = ref<Record<string, unknown>[]>([]);
const status = ref("firing");
const error = ref("");
const loading = ref(false);
const selected = ref<Record<string, unknown>>();

function severityPriority(value: unknown) {
  return ({ CRITICAL: "URGENT", WARNING: "HIGH", INFO: "LOW" } as Record<string, string>)[String(value).toUpperCase()] || "MEDIUM";
}

async function load() {
  loading.value = true;
  error.value = "";
  try { alerts.value = await itsmApi.alerts(status.value); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "告警加载失败"; }
  finally { loading.value = false; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page alert-page">
    <PageHeader title="活动告警" description="查看当前告警、受影响服务及关联工单">
      <template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />{{ loading ? "刷新中…" : "刷新" }}</button></template>
    </PageHeader>
    <ListSurface>
      <template #toolbar><FilterBar>
      <label class="filter-field"><span>状态</span><select v-model="status" @change="load"><option value="">全部状态</option><option value="firing">告警中</option><option value="resolved">已恢复</option></select></label>
      <span class="filter-result">{{ alerts.length }} 条告警</span>
      </FilterBar></template>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />

      <LoadingState v-if="loading" text="正在读取告警…" />
      <EmptyState v-else-if="!alerts.length" title="当前没有匹配告警" description="告警接入后会在这里聚合并关联工单" :icon="Siren" />
      <table v-else>
        <thead><tr><th>严重度</th><th>告警</th><th>服务</th><th>状态</th><th>次数</th><th>关联工单</th><th>最近发生</th></tr></thead>
        <tbody><tr v-for="alert in alerts" :key="String(alert.id)" tabindex="0" @click="selected = alert" @keydown.enter="selected = alert">
          <td><PriorityIndicator :value="severityPriority(alert.severity)" /></td>
          <td><button class="table-title table-title-button" @click.stop="selected = alert"><strong>{{ alert.alertName }}</strong><span>{{ alert.fingerprint }}</span></button></td>
          <td><code>{{ alert.serviceCode || "未映射" }}</code></td>
          <td><StatusBadge :value="String(alert.currentStatus)" /></td>
          <td>{{ alert.occurrenceCount }}</td>
          <td><RouterLink v-if="alert.ticketId" :to="`/tickets/${alert.ticketId}`" @click.stop>{{ alert.ticketNo || `#${alert.ticketId}` }}</RouterLink><span v-else>无</span></td>
          <td><time :title="formatDateTime(String(alert.lastSeenTime))">{{ formatRelativeTime(String(alert.lastSeenTime)) }}</time></td>
        </tr></tbody>
      </table>
    </ListSurface>
    <DetailPanel v-if="selected" title="告警详情" :subtitle="String(selected.alertName)" @close="selected = undefined">
      <dl class="oa-definition-list">
        <div><dt>告警状态</dt><dd><StatusBadge :value="String(selected.currentStatus)" /></dd></div>
        <div><dt>严重度</dt><dd :title="String(selected.severity)">{{ statusLabel(selected.severity) }}</dd></div>
        <div><dt>服务</dt><dd><code>{{ selected.serviceCode || "未映射" }}</code></dd></div>
        <div><dt>发生次数</dt><dd>{{ selected.occurrenceCount }}</dd></div>
        <div><dt>最近发生</dt><dd>{{ formatDateTime(String(selected.lastSeenTime)) }}</dd></div>
        <div><dt>Fingerprint</dt><dd><code>{{ selected.fingerprint }}</code></dd></div>
      </dl>
      <template #footer><RouterLink v-if="selected.ticketId" class="button primary" :to="`/tickets/${selected.ticketId}`">打开关联工单</RouterLink></template>
    </DetailPanel>
  </div>
</template>
