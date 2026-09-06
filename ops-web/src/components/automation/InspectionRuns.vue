<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { Activity, Play, RefreshCw } from '@lucide/vue';
import { operationsApi, type OperationsWorkflow, type OperationsRun, type OperationsRunDetail } from '@/api/operations';
import { useAuthStore } from '@/stores/auth';
import LoadingState from '@/components/LoadingState.vue';
import InlineError from '@/components/InlineError.vue';
import EmptyState from '@/components/EmptyState.vue';
import DetailPanel from '@/components/DetailPanel.vue';
import PaginationBar from '@/components/PaginationBar.vue';
import '@/styles/components/inspection-runs.css';

const auth = useAuthStore();
const workflows = ref<OperationsWorkflow[]>([]);
const runs = ref<OperationsRun[]>([]);
const total = ref(0);
const page = ref(1);
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const selected = ref<OperationsRunDetail>();
let epoch = 0;
let disposed = false;
function label(value: string) { return ({ RUNNING: '巡检中', SUCCEEDED: '巡检已完成', ATTENTION: '发现需关注项', FAILED: '巡检未完成', OK: '检查通过' } as Record<string, string>)[value] || value; }
function date(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '尚未完成'; }
async function load() {
  const current = ++epoch; loading.value = true; error.value = '';
  const result = await Promise.allSettled([operationsApi.workflows(), operationsApi.runs(page.value, 8)]);
  if (disposed || current !== epoch) return;
  if (result[0].status === 'fulfilled') workflows.value = result[0].value.filter(item => item.mode === 'READ_ONLY');
  if (result[1].status === 'fulfilled') { runs.value = result[1].value.records; total.value = result[1].value.total; }
  const failed = result.find(item => item.status === 'rejected');
  if (failed?.status === 'rejected') error.value = failed.reason instanceof Error ? failed.reason.message : '巡检数据读取失败';
  loading.value = false;
}
async function start(workflow: OperationsWorkflow) {
  if (busy.value || !workflow.available || (!auth.isAdmin && !auth.isOps)) return;
  busy.value = true; error.value = '';
  try { const run = await operationsApi.start(workflow.code); if (!disposed) { selected.value = run; page.value = 1; await load(); } }
  catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '巡检启动失败'; }
  finally { if (!disposed) busy.value = false; }
}
async function inspect(id: number) {
  if (busy.value) return;
  busy.value = true; error.value = '';
  try { const value = await operationsApi.run(id); if (!disposed) selected.value = value; }
  catch (cause) { if (!disposed) error.value = cause instanceof Error ? cause.message : '巡检记录读取失败'; }
  finally { if (!disposed) busy.value = false; }
}
onMounted(load);
onBeforeUnmount(() => { disposed = true; epoch++; });
</script>

<template>
  <div class="inspection-workspace">
    <InlineError v-if="error" :message="error" />
    <section v-for="workflow in workflows" :key="workflow.code" class="panel inspection-definition"><Activity :size="26" /><div><h3>{{ workflow.title }}</h3><p>{{ workflow.description }}</p><small>{{ workflow.scheduleEnabled ? `每 ${workflow.intervalMinutes} 分钟执行` : '当前未启用定时执行' }} · 上次执行 {{ date(workflow.lastRunAt) }}</small><p v-if="workflow.unavailableReason">{{ workflow.unavailableReason }}</p></div><button v-if="auth.isAdmin || auth.isOps" class="button primary" :disabled="busy || !workflow.available" @click="start(workflow)"><Play :size="16" />执行只读巡检</button></section>
    <section class="panel"><header class="panel-header"><div><h3>巡检记录</h3><p>集中查看检查步骤与证据；巡检完成不表示故障已经修复。</p></div><button class="button secondary" :disabled="loading || busy" @click="load"><RefreshCw :size="16" />刷新</button></header><LoadingState v-if="loading && !runs.length" text="正在读取巡检记录…" /><div v-else-if="runs.length" class="inspection-list"><button v-for="run in runs" :key="run.id" :disabled="busy" @click="inspect(run.id)"><div><strong>{{ run.title }}</strong><p>{{ run.summary }}</p><small>{{ run.mode === 'READ_ONLY' ? '只读巡检' : '历史隔离演练' }} · {{ run.actor }} · {{ date(run.startedAt) }}</small></div><span>{{ label(run.status) }}</span></button></div><EmptyState v-else title="尚无巡检记录" description="执行只读巡检后，将在此展示实际检查结果。" /><PaginationBar v-if="total" :page="page" :page-size="8" :total="total" @change="page = $event; load()" /></section>
    <DetailPanel v-if="selected" :title="selected.title" :subtitle="label(selected.status)" @close="selected = undefined"><p class="inspection-summary">{{ selected.summary }}</p><ol class="inspection-steps"><li v-for="step in selected.steps" :key="step.id"><strong>{{ step.title }} · {{ label(step.status) }}</strong><p>{{ step.detail }}</p><details v-if="step.evidence"><summary>检查证据</summary><pre>{{ step.evidence }}</pre></details><small>{{ date(step.finishedAt) }}</small></li></ol></DetailPanel>
  </div>
</template>
