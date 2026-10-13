<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { Activity, ArrowRight, Bot, CheckCircle2, CircleDot, ShieldCheck, UserRound } from '@lucide/vue';
import { ticketApi } from '@/api/modules';
import { eventLifecycleApi, type EventLifecycle } from '@/api/event-lifecycle';
import { eventWorkspaceApi, type EventWorkspace } from '@/api/event-workspace';
import { statusLabel } from '@/ui/status-map';
import { eventRunLabels } from '@/utils/event-workspace';
import { useAuthStore } from '@/stores/auth';
import type { Ticket, TicketLog } from '@/types/api';
const props = defineProps<{ tickets: Ticket[]; ready: boolean }>();
const auth = useAuthStore();
const selectedId = ref<number>();
const recent = computed(() => [...props.tickets].sort((a, b) => Date.parse(b.updateTime) - Date.parse(a.updateTime)).slice(0, 12));
const selected = computed(() => props.tickets.find(ticket => ticket.id === selectedId.value));
const logs = ref<TicketLog[]>([]); const lifecycle = ref<EventLifecycle>(); const workspace = ref<EventWorkspace>();
const loading = ref(false); const error = ref(''); let epoch = 0;
const clock = (value: string) => new Date(value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
const actions: Record<string, string> = { RESULT: '提交处理结果', TECH_PASS: '技术恢复确认', TECH_FAIL: '恢复验证未通过', BUSINESS_CONFIRM: '业务恢复确认', CLOSE: '事件关闭', REOPEN: '重新处置', RECOVERY_BINDING: '补全恢复关联' };
const activities = computed(() => {
  if (!selected.value) return [];
  const items = logs.value.map(log => ({ id: `log-${log.id}`, at: log.createTime, label: log.operationType === 'CLAIM' || log.toStatus === 'ASSIGNED' ? '人工接单' : log.toStatus === 'CREATED' ? '事件创建' : `工单${statusLabel(log.toStatus)}`, detail: log.remark || `用户 #${log.operatorId}`, icon: log.toStatus === 'ASSIGNED' ? UserRound : CircleDot, tone: 'blue' }));
  for (const record of lifecycle.value?.history || []) {
    const action = record.recordType.replace(/^EVENT_/, '');
    items.push({ id: `life-${record.id}`, at: record.createTime, label: actions[action] || record.recordType, detail: record.content, icon: ['TECH_PASS', 'BUSINESS_CONFIRM', 'CLOSE'].includes(action) ? CheckCircle2 : ShieldCheck, tone: action === 'TECH_FAIL' ? 'warning' : 'blue' });
  }
  for (const run of workspace.value?.runs || []) {
    items.push({ id: `run-start-${run.id}`, at: run.createdAt, label: 'AI 流程启动', detail: `运行 ${run.id}`, icon: Bot, tone: 'blue' });
    if (run.updatedAt && run.updatedAt !== run.createdAt) items.push({ id: `run-${run.id}`, at: run.updatedAt, label: `自动化${eventRunLabels[run.status] || run.status}`, detail: run.message || `运行 ${run.id}`, icon: Bot, tone: ['NEEDS_ATTENTION', 'BUDGET_EXCEEDED', 'FAILED'].includes(run.status) ? 'warning' : 'blue' });
  }
  const diagnosis = workspace.value?.diagnosis;
  if (diagnosis?.recordedAt && diagnosis.summary) items.push({ id: 'diagnosis', at: diagnosis.recordedAt, label: 'AI 分析已记录', detail: diagnosis.summary, icon: Bot, tone: 'blue' });
  return items.filter(item => Number.isFinite(Date.parse(item.at))).sort((a, b) => Date.parse(a.at) - Date.parse(b.at)).slice(-6);
});
async function load() {
  const current = ++epoch; logs.value = []; lifecycle.value = undefined; workspace.value = undefined; error.value = '';
  loading.value = false; if (!selectedId.value) return; loading.value = true;
  const result = await Promise.allSettled([ticketApi.logs(selectedId.value), eventLifecycleApi.read(selectedId.value), eventWorkspaceApi.read(selectedId.value)]);
  if (current !== epoch) return;
  if (result[0].status === 'fulfilled') logs.value = result[0].value;
  if (result[1].status === 'fulfilled') lifecycle.value = result[1].value;
  if (result[2].status === 'fulfilled') workspace.value = result[2].value;
  if (result.some(item => item.status === 'rejected')) error.value = '部分活动来源暂不可用，仅展示已取得的记录';
  loading.value = false;
}
watch(recent, rows => { if (!rows.some(ticket => ticket.id === selectedId.value)) selectedId.value = rows[0]?.id; }, { immediate: true });
watch(() => [selectedId.value, selected.value?.updateTime, auth.identity], load, { immediate: true });
onBeforeUnmount(() => { epoch++; });
</script>
<template>
  <section class="panel overview-activity event-activity-strip">
    <header class="panel-header"><h3><Activity :size="17" />运维活动流</h3><label v-if="selected">相关事件 <select v-model="selectedId" aria-label="选择活动流事件"><option v-for="ticket in recent" :key="ticket.id" :value="ticket.id">{{ ticket.eventId || `EVT-${ticket.id}` }} · {{ ticket.title }}</option></select></label><RouterLink v-if="selected" class="text-button" :to="`/tickets/${selected.id}`">查看完整活动 <ArrowRight :size="15" /></RouterLink></header>
    <p v-if="!ready || loading" class="overview-source-note">{{ loading ? '正在读取该事件的活动记录…' : '事件活动未获取' }}</p>
    <ol v-else-if="activities.length" class="event-activity-track"><li v-for="item in activities" :key="item.id" :data-tone="item.tone" :title="item.detail"><span class="activity-point"><component :is="item.icon" :size="14" /></span><time :title="new Date(item.at).toLocaleString('zh-CN')">{{ clock(item.at) }}</time><strong>{{ item.label }}</strong></li></ol>
    <p v-else class="overview-source-note">{{ selected ? '该事件暂无可展示的活动记录。' : '当前可见范围内尚无事件。' }}</p>
    <p v-if="error" class="overview-source-note" role="status">{{ error }}<button class="text-button" @click="load">重试</button></p>
  </section>
</template>
<style scoped>
.event-activity-strip .panel-header { display:flex; flex-wrap:wrap; gap:12px; }.event-activity-strip h3 { display:flex; align-items:center; gap:8px; margin:0; white-space:nowrap; }.event-activity-strip label { display:flex; align-items:center; min-width:0; gap:8px; color:#7e8ea8; font-size:12px; }.event-activity-strip select { max-width:330px; min-height:30px; border:0; background:transparent; color:#2467ed; padding:3px 22px 3px 4px; font-size:12px; }.event-activity-strip header > a { margin-left:auto; }.event-activity-track { display:flex; list-style:none; margin:12px 18px 18px; padding:0; overflow-x:auto; border:1px solid #e4ecfc; border-radius:8px; }.event-activity-track li { position:relative; flex:1 0 135px; display:grid; grid-template-columns:22px 1fr; grid-template-rows:25px 28px; align-items:center; padding:12px 14px 8px; gap:4px; }.event-activity-track li::before { position:absolute; content:''; border-left:1px dashed #c4d6fc; height:20px; left:25px; top:34px; }.activity-point { grid-column:1; background:#edf3ff; color:#3478ff; width:22px; height:22px; display:grid; place-items:center; border-radius:50%; }.event-activity-track time { color:#647898; font-size:12px; }.event-activity-track strong { grid-column:2; font-size:12px; color:#263f69; font-weight:500; }.event-activity-track li[data-tone=warning] .activity-point { color:#d88123; background:#fff5e9; }@media(max-width:760px) { .event-activity-strip select { max-width:220px; }.event-activity-strip header > a { margin-left:0; } }
</style>
