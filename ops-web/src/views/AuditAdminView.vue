<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Check, ClipboardList, Copy, Eye, ExternalLink, Filter, RefreshCw } from "@lucide/vue";
import { useRouter } from "vue-router";
import { adminApi } from "@/api/modules";
import type { OperationLog, PageResponse } from "@/types/api";
import PaginationBar from "@/components/PaginationBar.vue";
import PageHeader from "@/components/PageHeader.vue";
import FilterBar from "@/components/FilterBar.vue";
import ListSurface from "@/components/ListSurface.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import DescriptionList from "@/components/DescriptionList.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import { businessTypeLabel, operationLabel } from "@/ui/status-map";
import { formatDateTime, formatShortDateTime } from "@/utils/datetime";

const audits = ref<PageResponse<OperationLog>>({
  records: [],
  total: 0,
  pageNum: 1,
  pageSize: 10,
});
const page = ref(1);
const loading = ref(false);
const error = ref("");
const bizId = ref("");
const operation = ref("");
const selected = ref<OperationLog>();
const payloadCopied = ref(false);
const router = useRouter();

async function load() {
  loading.value = true;
  error.value = "";
  try {
    audits.value = await adminApi.audits({
      pageNum: page.value,
      pageSize: 10,
      bizId: bizId.value.trim() || undefined,
      operation: operation.value || undefined,
    });
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  } finally {
    loading.value = false;
  }
}
function search() {
  page.value = 1;
  load();
}
function formatContent(content: string) {
  try {
    return JSON.stringify(JSON.parse(content), null, 2);
  } catch {
    return content;
  }
}
async function copyPayload() {
  if (!selected.value) return;
  await navigator.clipboard.writeText(formatContent(selected.value.content));
  payloadCopied.value = true;
  window.setTimeout(() => (payloadCopied.value = false), 1200);
}
onMounted(load);
</script>

<template>
  <div class="stack-page">
    <PageHeader title="操作审计" description="审阅工单、值班与配置项等关键业务操作">
      <template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />{{ loading ? "刷新中…" : "刷新" }}</button></template>
    </PageHeader>
    <ListSurface>
      <template #toolbar><FilterBar>
      <form class="audit-filter" @submit.prevent="search">
        <label>工单 ID<input v-model="bizId" inputmode="numeric" placeholder="例如 2000" /></label>
        <label>操作类型<select v-model="operation"><option value="">全部操作</option><option value="CREATE">创建</option><option value="CLAIM">接单</option><option value="PROCESSING">开始处理</option><option value="WAITING_CONFIRM">业务确认</option><option value="RESOLVED">解决</option><option value="CLOSED">关闭</option></select></label>
        <button class="button primary"><Filter :size="16" />筛选</button>
      </form>
      </FilterBar></template>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <LoadingState v-if="loading && !audits.records.length" text="正在加载审计记录…" />
    <EmptyState v-else-if="!audits.records.length" title="暂无审计记录" description="符合当前筛选条件的操作会显示在这里" :icon="ClipboardList" />

        <table v-else class="audit-table">
          <thead><tr><th>时间</th><th>操作人</th><th>操作</th><th>业务对象</th><th>服务</th><th>Trace ID</th><th></th></tr></thead>
          <tbody>
            <tr v-for="item in audits.records" :key="item.id" tabindex="0" @click="selected = item" @keydown.enter="selected = item">
              <td><time :title="formatDateTime(item.createTime)">{{ formatShortDateTime(item.createTime) }}</time></td>
              <td>#{{ item.operator }}</td>
              <td><strong :title="item.operationType">{{ operationLabel(item.operationType) }}</strong></td>
              <td><span :title="item.bizType">{{ businessTypeLabel(item.bizType) }}</span> <span class="mono">#{{ item.bizId }}</span></td>
              <td>{{ item.serviceName }}</td>
              <td class="mono trace-cell">{{ item.traceId || "未记录" }}</td>
              <td><div class="row-actions reveal-on-row"><button class="icon-button" title="查看事件详情" @click.stop="selected = item"><Eye :size="17" /></button><button v-if="item.bizType === 'TICKET'" class="icon-button" title="进入对应工单" @click.stop="router.push(`/tickets/${item.bizId}`)"><ExternalLink :size="17" /></button></div></td>
            </tr>
          </tbody>
        </table>
      <template #footer>
      <PaginationBar
        v-if="audits.total"
        :page="page"
        :page-size="10"
        :total="audits.total"
        @change="(value) => { page = value; load(); }"
      />
      </template>
    </ListSurface>
    <DetailPanel v-if="selected" title="审计事件详情" :subtitle="`事件 #${selected.id}`" width="wide" @close="selected = undefined">
      <DescriptionList class="audit-detail">
        <div><dt>审计 ID</dt><dd>#{{ selected.id }}</dd></div><div><dt>业务对象</dt><dd :title="selected.bizType">{{ businessTypeLabel(selected.bizType) }} #{{ selected.bizId }}</dd></div><div><dt>操作</dt><dd :title="selected.operationType">{{ operationLabel(selected.operationType) }}</dd></div><div><dt>服务</dt><dd>{{ selected.serviceName }}</dd></div><div><dt>操作人</dt><dd>#{{ selected.operator }}</dd></div><div><dt>Trace ID</dt><dd class="mono">{{ selected.traceId || "未记录" }}</dd></div>
      </DescriptionList>
      <div class="detail-code-header"><strong class="detail-label">事件载荷</strong><button class="icon-button" :title="payloadCopied ? '已复制' : '复制事件载荷'" @click="copyPayload"><Check v-if="payloadCopied" :size="16" /><Copy v-else :size="16" /></button></div><pre class="json-detail">{{ formatContent(selected.content) }}</pre>
      <template #footer><button class="button secondary" @click="selected = undefined">关闭</button><button v-if="selected.bizType === 'TICKET'" class="button primary" @click="router.push(`/tickets/${selected.bizId}`)"><ExternalLink :size="15" />打开工单</button></template>
    </DetailPanel>
  </div>
</template>
