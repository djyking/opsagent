<script setup lang="ts">
import { onMounted, ref } from "vue";
import { BookCheck, Check, RefreshCw, X } from "@lucide/vue";
import { itsmApi } from "@/api/modules";

const rows = ref<Record<string, unknown>[]>([]);
const status = ref("IN_REVIEW");
const error = ref("");
const busy = ref(0);
async function load() {
  try { rows.value = await itsmApi.reviewDocuments(status.value); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审核列表加载失败"; }
}
async function approve(id: number) {
  busy.value = id;
  try { await itsmApi.approveDocument(id, "审核通过并发布"); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审核失败"; }
  finally { busy.value = 0; }
}
async function reject(id: number) {
  const comment = window.prompt("请输入驳回意见（必填）")?.trim();
  if (!comment) return;
  busy.value = id;
  try { await itsmApi.rejectDocument(id, comment); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "驳回失败"; }
  finally { busy.value = 0; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page">
    <section class="page-lead"><div><span class="eyebrow">KNOWLEDGE GOVERNANCE</span><h2>知识审核中心</h2><p>解析状态与审核状态相互独立；生产 RAG 只检索 PUBLISHED 文档。</p></div><button class="button secondary" @click="load"><RefreshCw :size="16" />刷新</button></section>
    <section class="filter-bar alert-filter"><select v-model="status" @change="load"><option value="">全部审核状态</option><option value="IN_REVIEW">待审核</option><option value="DRAFT">草稿</option><option value="REJECTED">已驳回</option><option value="PUBLISHED">已发布</option><option value="ARCHIVED">已归档</option></select></section>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section class="panel table-panel"><div v-if="!rows.length" class="empty-state"><BookCheck :size="36" /><strong>没有匹配的知识文档</strong></div><div v-else class="responsive-table"><table><thead><tr><th>文档</th><th>知识库</th><th>解析</th><th>审核状态</th><th>提交时间</th><th>意见</th><th>操作</th></tr></thead><tbody><tr v-for="row in rows" :key="String(row.id)"><td><strong>{{ row.originalName }}</strong><small class="block-muted">#{{ row.id }}</small></td><td>{{ row.knowledgeBaseName }}</td><td>{{ row.parseStatus }}</td><td><span class="status-badge" :class="`status-${String(row.reviewStatus).toLowerCase()}`">{{ row.reviewStatus }}</span></td><td>{{ row.submittedTime ? new Date(String(row.submittedTime)).toLocaleString("zh-CN") : "未提交" }}</td><td>{{ row.reviewComment || "—" }}</td><td><div v-if="row.reviewStatus === 'IN_REVIEW'" class="row-actions"><button class="icon-button success" title="审核通过" :disabled="busy === Number(row.id)" @click="approve(Number(row.id))"><Check :size="16" /></button><button class="icon-button danger" title="驳回" :disabled="busy === Number(row.id)" @click="reject(Number(row.id))"><X :size="16" /></button></div><span v-else>—</span></td></tr></tbody></table></div></section>
  </div>
</template>
