<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ClipboardList, Eye, ExternalLink, Filter, RefreshCw } from "@lucide/vue";
import { useRouter } from "vue-router";
import { adminApi } from "@/api/modules";
import type { OperationLog, PageResponse } from "@/types/api";
import PaginationBar from "@/components/PaginationBar.vue";
import BaseModal from "@/components/BaseModal.vue";

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
onMounted(load);
</script>

<template>
  <div class="stack-page">
    <section class="page-lead">
      <div>
        <span class="eyebrow">OPERATION AUDIT</span>
        <h2>操作审计</h2>
        <p>审阅工单创建、接单、状态流转等关键业务操作。</p>
      </div>
      <button class="button secondary" :disabled="loading" @click="load">
        <RefreshCw :size="16" />刷新
      </button>
    </section>
    <section class="panel">
      <form class="audit-filter" @submit.prevent="search">
        <label>工单 ID<input v-model="bizId" inputmode="numeric" placeholder="例如 2000" /></label>
        <label>操作类型<select v-model="operation"><option value="">全部操作</option><option value="CREATE">创建</option><option value="CLAIM">接单</option><option value="PROCESSING">开始处理</option><option value="WAITING_CONFIRM">业务确认</option><option value="RESOLVED">解决</option><option value="CLOSED">关闭</option></select></label>
        <button class="button primary"><Filter :size="16" />筛选</button>
      </form>
      <div v-if="error" class="inline-error">{{ error }}</div>
      <div v-if="loading" class="loading-state">正在加载审计记录…</div>
      <div v-else-if="!audits.records.length" class="empty-state">
        <ClipboardList :size="36" /><strong>暂无审计记录</strong>
      </div>
      <div v-else class="record-list">
        <article v-for="item in audits.records" :key="item.id">
          <div class="record-icon"><ClipboardList :size="20" /></div>
          <div class="record-body">
            <header>
              <strong>{{ item.operationType }}</strong>
              <span class="mono">{{ item.bizType }} #{{ item.bizId }}</span>
            </header>
            <p>{{ item.content }}</p>
            <span>{{ item.serviceName }} · 操作人 #{{ item.operator }} · {{ new Date(item.createTime).toLocaleString("zh-CN") }}</span>
          </div>
          <div class="row-actions"><button class="icon-button" title="查看事件详情" @click="selected = item"><Eye :size="17" /></button><button class="icon-button" title="进入对应工单" @click="router.push(`/tickets/${item.bizId}`)"><ExternalLink :size="17" /></button></div>
        </article>
      </div>
      <PaginationBar
        v-if="audits.total"
        :page="page"
        :page-size="10"
        :total="audits.total"
        @change="(value) => { page = value; load(); }"
      />
    </section>
    <BaseModal v-if="selected" title="审计事件详情" @close="selected = undefined">
      <dl class="audit-detail">
        <div><dt>审计 ID</dt><dd>#{{ selected.id }}</dd></div><div><dt>业务对象</dt><dd>{{ selected.bizType }} #{{ selected.bizId }}</dd></div><div><dt>操作</dt><dd>{{ selected.operationType }}</dd></div><div><dt>服务</dt><dd>{{ selected.serviceName }}</dd></div><div><dt>操作人</dt><dd>#{{ selected.operator }}</dd></div><div><dt>Trace ID</dt><dd class="mono">{{ selected.traceId || "未记录" }}</dd></div>
      </dl>
      <strong class="detail-label">事件载荷</strong><pre class="json-detail">{{ formatContent(selected.content) }}</pre>
    </BaseModal>
  </div>
</template>
