<script setup lang="ts">
import { onMounted, ref } from "vue";
import { BookCheck, Check, Eye, RefreshCw, X } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import PageHeader from "@/components/PageHeader.vue";
import FilterBar from "@/components/FilterBar.vue";
import EmptyState from "@/components/EmptyState.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import ListSurface from "@/components/ListSurface.vue";
import DetailPanel from "@/components/DetailPanel.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import FormField from "@/components/FormField.vue";
import { formatDateTime, formatShortDateTime } from "@/utils/datetime";

const rows = ref<Record<string, unknown>[]>([]);
const status = ref("IN_REVIEW");
const error = ref("");
const busy = ref(0);
const loading = ref(false);
const selected = ref<Record<string, unknown>>();
const rejectComment = ref("");

async function load() {
  loading.value = true;
  error.value = "";
  try { rows.value = await itsmApi.reviewDocuments(status.value); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审核列表加载失败"; }
  finally { loading.value = false; }
}
function open(row: Record<string, unknown>) { selected.value = row; rejectComment.value = ""; }
async function approve(id: number) {
  busy.value = id;
  try { await itsmApi.approveDocument(id, "审核通过并发布"); selected.value = undefined; await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "审核失败"; }
  finally { busy.value = 0; }
}
async function reject(id: number) {
  const comment = rejectComment.value.trim();
  if (!comment) { error.value = "请输入驳回意见"; return; }
  busy.value = id;
  try { await itsmApi.rejectDocument(id, comment); selected.value = undefined; await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "驳回失败"; }
  finally { busy.value = 0; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page review-page">
    <PageHeader title="知识审核" description="审核解析完成的知识文档并决定是否发布">
      <template #actions><button class="button secondary" :disabled="loading" @click="load"><RefreshCw :size="16" />{{ loading ? "刷新中…" : "刷新" }}</button></template>
    </PageHeader>
    <ListSurface>
      <template #toolbar><FilterBar>
      <div class="segmented-control">
        <button :class="{ active: status === 'IN_REVIEW' }" @click="status = 'IN_REVIEW'; load()">待审核</button>
        <button :class="{ active: status === 'PUBLISHED' }" @click="status = 'PUBLISHED'; load()">已通过</button>
        <button :class="{ active: status === 'REJECTED' }" @click="status = 'REJECTED'; load()">已驳回</button>
        <button :class="{ active: !status }" @click="status = ''; load()">全部</button>
      </div>
      <span class="filter-result">{{ rows.length }} 篇文档</span>
      </FilterBar></template>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />

      <LoadingState v-if="loading" text="正在读取审核队列…" />
      <EmptyState v-else-if="!rows.length" title="当前筛选下没有知识文档" description="新的待审核文档会显示在这里" :icon="BookCheck" />
      <table v-else>
        <thead><tr><th>文档</th><th>知识库</th><th>解析状态</th><th>审核状态</th><th>提交时间</th><th>意见</th><th></th></tr></thead>
        <tbody><tr v-for="row in rows" :key="String(row.id)" tabindex="0" @click="open(row)" @keydown.enter="open(row)">
          <td><button class="table-title table-title-button" @click.stop="open(row)"><strong>{{ row.originalName }}</strong><span>文档 #{{ row.id }}</span></button></td>
          <td>{{ row.knowledgeBaseName }}</td><td><StatusBadge :value="String(row.parseStatus)" /></td><td><StatusBadge :value="String(row.reviewStatus)" /></td>
          <td><time :title="row.submittedTime ? formatDateTime(String(row.submittedTime)) : '未提交'">{{ row.submittedTime ? formatShortDateTime(String(row.submittedTime)) : "未提交" }}</time></td>
          <td>{{ row.reviewComment || "—" }}</td><td><div class="row-actions reveal-on-row"><button class="icon-button" title="查看审核详情" @click.stop="open(row)"><Eye :size="16" /></button></div></td>
        </tr></tbody>
      </table>
    </ListSurface>
    <DetailPanel v-if="selected" title="审核文档" :subtitle="String(selected.originalName)" @close="selected = undefined">
      <dl class="oa-definition-list">
        <div><dt>知识库</dt><dd>{{ selected.knowledgeBaseName }}</dd></div><div><dt>解析状态</dt><dd><StatusBadge :value="String(selected.parseStatus)" /></dd></div>
        <div><dt>审核状态</dt><dd><StatusBadge :value="String(selected.reviewStatus)" /></dd></div><div><dt>提交时间</dt><dd>{{ selected.submittedTime ? formatDateTime(String(selected.submittedTime)) : "未提交" }}</dd></div>
        <div><dt>当前意见</dt><dd>{{ selected.reviewComment || "无" }}</dd></div>
      </dl>
      <FormField v-if="selected.reviewStatus === 'IN_REVIEW'" class="drawer-field" label="驳回意见" help="驳回时必须填写原因"><textarea v-model.trim="rejectComment" rows="4" maxlength="500" placeholder="说明需要修改的内容" /></FormField>
      <template v-if="selected.reviewStatus === 'IN_REVIEW'" #footer>
        <button class="button secondary danger-text" :disabled="busy === Number(selected.id)" @click="reject(Number(selected.id))"><X :size="15" />驳回</button>
        <button class="button primary" :disabled="busy === Number(selected.id)" @click="approve(Number(selected.id))"><Check :size="15" />通过并发布</button>
      </template>
    </DetailPanel>
  </div>
</template>
