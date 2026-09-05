<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { CalendarClock, Pencil, PhoneCall, Plus, Shield, Trash2 } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import type { CurrentOnCall } from "@/types/api";
import BaseModal from "@/components/BaseModal.vue";
import PageHeader from "@/components/PageHeader.vue";
import InlineError from "@/components/InlineError.vue";
import EmptyState from "@/components/EmptyState.vue";
import StatusBadge from "@/components/StatusBadge.vue";
import TableSurface from "@/components/TableSurface.vue";
import { useAuthStore } from "@/stores/auth";
import { formatDateTime } from "@/utils/datetime";
import { statusLabel } from "@/ui/status-map";
import { usePageFeedback } from "@/composables/usePageFeedback";
import GuidedEmptyState from "@/components/experience/GuidedEmptyState.vue";
import ActionButton from "@/components/feedback/ActionButton.vue";

const auth = useAuthStore();
const schedules = ref<Record<string, unknown>[]>([]);
const shifts = ref<Record<string, unknown>[]>([]);
const current = ref<CurrentOnCall>({ fallback: true, members: [] });
const error = ref("");
const toast = usePageFeedback(error, load);
const activeView = ref<"schedule" | "manage">("schedule");
const showShiftForm = ref(false);
const showScheduleForm = ref(false);
const editingId = ref(0);
const saving = ref(false);
const scheduleForm = ref({ scheduleCode: "", scheduleName: "", serviceCiCode: "", timezone: "Asia/Shanghai", enabled: true });
const shiftForm = ref({ scheduleId: 0, roleType: "PRIMARY", userId: 2, userName: "", startTime: "", endTime: "" });

const days = computed(() => Array.from({ length: 7 }, (_, index) => {
  const date = new Date(); date.setHours(0, 0, 0, 0); date.setDate(date.getDate() + index); return date;
}));
function memberFor(day: Date, role: string) {
  const end = new Date(day); end.setDate(end.getDate() + 1);
  return shifts.value.find((row) => String(row.roleType) === role && new Date(String(row.startTime)) < end && new Date(String(row.endTime)) > day);
}
function dayLabel(day: Date) { return `${day.getMonth() + 1}/${day.getDate()}`; }

async function load() {
  try { [schedules.value, shifts.value, current.value] = await Promise.all([itsmApi.schedules(), itsmApi.shifts(), itsmApi.currentOnCall("ops-ticket-service")]); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "排班加载失败"; }
}
function localDateTime(value: unknown) {
  if (!value) return ""; const date = new Date(String(value)); const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}
function openSchedule() { scheduleForm.value = { scheduleCode: "", scheduleName: "", serviceCiCode: "", timezone: "Asia/Shanghai", enabled: true }; showScheduleForm.value = true; }
async function saveSchedule() {
  saving.value = true;
  try { await itsmApi.createSchedule(scheduleForm.value); toast.show("值班计划已创建"); showScheduleForm.value = false; await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "排班计划保存失败"; }
  finally { saving.value = false; }
}
function openShift(row?: Record<string, unknown>) {
  editingId.value = Number(row?.id || 0); const start = new Date(); start.setMinutes(0, 0, 0); const end = new Date(start); end.setDate(end.getDate() + 1);
  shiftForm.value = row ? { scheduleId: Number(row.scheduleId), roleType: String(row.roleType), userId: Number(row.userId), userName: String(row.userName), startTime: localDateTime(row.startTime), endTime: localDateTime(row.endTime) } : { scheduleId: Number(schedules.value[0]?.id || 0), roleType: "PRIMARY", userId: 2, userName: "", startTime: localDateTime(start), endTime: localDateTime(end) };
  showShiftForm.value = true;
}
async function saveShift() {
  if (saving.value) return;
  saving.value = true;
  try { const payload = { ...shiftForm.value, startTime: `${shiftForm.value.startTime}:00`, endTime: `${shiftForm.value.endTime}:00` }; if (editingId.value) await itsmApi.updateShift(editingId.value, payload); else await itsmApi.createShift(payload); toast.show(editingId.value ? "班次已更新" : "班次已创建"); showShiftForm.value = false; await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "班次保存失败"; }
  finally { saving.value = false; }
}
async function removeShift(id: number) {
  if (!window.confirm("确认删除这个班次？")) return;
  try { await itsmApi.deleteShift(id); toast.show("班次已删除"); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "班次删除失败"; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page oncall-page">
    <PageHeader title="值班排班" description="查看当前主备值班与未来排班">
      <template #meta><span>{{ schedules.length }} 个值班计划 · {{ shifts.length }} 个班次</span></template>
      <template #actions><button v-if="auth.isAdmin" class="button secondary" @click="openSchedule"><Plus :size="15" />新建计划</button><button v-if="auth.isAdmin" class="button primary" @click="openShift()"><Plus :size="15" />新增班次</button></template>
    </PageHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <section class="oncall-current-grid" aria-label="当前值班人员">
      <article v-for="member in current.members" :key="`${member.scheduleCode}-${member.roleType}-${member.userId}`" class="oncall-current-item" :class="{ secondary: member.roleType !== 'PRIMARY' }">
        <span><PhoneCall v-if="member.roleType === 'PRIMARY'" :size="18" /><Shield v-else :size="18" /></span>
        <div><small>当前{{ statusLabel(member.roleType) }}</small><strong>{{ member.userName }}</strong><p><CalendarClock :size="13" />{{ formatDateTime(String(member.startTime)) }} → {{ formatDateTime(String(member.endTime)) }}</p></div>
      </article>
      <GuidedEmptyState v-if="current.fallback && !error" kind="oncall" title="当前没有有效班次" description="未排班可能影响告警升级和责任人分配。请核对对应服务的值班安排。" :action="auth.isAdmin ? (schedules.length ? '新建班次' : '新建值班计划') : undefined" @action="schedules.length ? openShift() : openSchedule()" />
    </section>
    <div class="oncall-view-toolbar"><div class="segmented-control oncall-view-switch"><button :class="{ active: activeView === 'schedule' }" :aria-pressed="activeView === 'schedule'" @click="activeView = 'schedule'">排班日历</button><button :class="{ active: activeView === 'manage' }" :aria-pressed="activeView === 'manage'" @click="activeView = 'manage'">班次管理</button></div><span>主备值班，交接有序</span></div>
    <section v-if="activeView === 'schedule'" class="oncall-schedule-surface">
      <header class="section-header"><div><h3>未来 7 天</h3><p>按当前班次数据汇总主备值班</p></div><span class="panel-count">{{ shifts.length }} 个班次</span></header>
      <div class="oncall-timeline-grid" role="region" aria-label="未来七天主备值班" tabindex="0">
        <div class="timeline-corner">值班角色</div><div v-for="(day, index) in days" :key="day.toISOString()" class="timeline-day" :class="{ today: index === 0 }"><strong>{{ dayLabel(day) }}</strong><span>{{ index === 0 ? '今天' : day.toLocaleDateString('zh-CN', { weekday: 'short' }) }}</span></div>
        <template v-for="role in ['PRIMARY', 'SECONDARY']" :key="role">
          <div class="timeline-role">{{ statusLabel(role) }}</div>
          <div v-for="day in days" :key="`${role}-${day.toISOString()}`" class="timeline-member" :class="{ empty: !memberFor(day, role) }"><strong>{{ memberFor(day, role)?.userName || '未排班' }}</strong><span v-if="memberFor(day, role)">{{ memberFor(day, role)?.scheduleName }}</span></div>
        </template>
      </div>
    </section>
    <TableSurface v-else>
      <template #header><div><h3>班次管理</h3><p>维护排班计划、角色与生效时间</p></div><span class="panel-count">{{ shifts.length }}</span></template>
      <GuidedEmptyState v-if="!shifts.length" kind="oncall" title="当前没有班次" description="未排班可能影响告警升级和责任人分配。先创建计划，再安排主备值班。" :action="auth.isAdmin ? (schedules.length ? '新建班次' : '新建值班计划') : undefined" @action="schedules.length ? openShift() : openSchedule()" />
      <table v-else><thead><tr><th>排班</th><th>角色</th><th>值班人</th><th>开始</th><th>结束</th><th v-if="auth.isAdmin"></th></tr></thead><tbody><tr v-for="shift in shifts" :key="String(shift.id)"><td>{{ shift.scheduleName }}</td><td><StatusBadge :value="String(shift.roleType)" /></td><td>{{ shift.userName }} <small>#{{ shift.userId }}</small></td><td>{{ formatDateTime(String(shift.startTime)) }}</td><td>{{ formatDateTime(String(shift.endTime)) }}</td><td v-if="auth.isAdmin"><div class="row-actions reveal-on-row"><button class="icon-button" title="编辑班次" @click="openShift(shift)"><Pencil :size="15" /></button><button class="icon-button" title="删除班次" @click="removeShift(Number(shift.id))"><Trash2 :size="15" /></button></div></td></tr></tbody></table>
    </TableSurface>
    <BaseModal v-if="showScheduleForm" title="新建值班计划" description="创建班次使用的排班计划" @close="showScheduleForm = false"><form class="form-grid" @submit.prevent="saveSchedule"><label>计划编码<input v-model.trim="scheduleForm.scheduleCode" required maxlength="64" /></label><label>计划名称<input v-model.trim="scheduleForm.scheduleName" required maxlength="128" /></label><label>关联 CI<input v-model.trim="scheduleForm.serviceCiCode" maxlength="64" placeholder="例如 order-service" /></label><label>时区<input v-model.trim="scheduleForm.timezone" required maxlength="64" /></label><label class="full"><input v-model="scheduleForm.enabled" type="checkbox" /> 启用计划</label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showScheduleForm = false">取消</button><ActionButton class="primary" :loading="saving" loading-text="保存中…">保存计划</ActionButton></div></form></BaseModal>
    <BaseModal v-if="showShiftForm" :title="editingId ? '编辑班次' : '新增班次'" description="设置值班角色与生效时间" @close="showShiftForm = false"><form class="form-grid" @submit.prevent="saveShift"><label>值班计划<select v-model.number="shiftForm.scheduleId" required><option v-for="schedule in schedules" :key="String(schedule.id)" :value="Number(schedule.id)">{{ schedule.scheduleName }}</option></select></label><label>角色<select v-model="shiftForm.roleType"><option value="PRIMARY">主值班</option><option value="SECONDARY">备值班</option></select></label><label>用户 ID<input v-model.number="shiftForm.userId" type="number" min="1" required /></label><label>值班人<input v-model.trim="shiftForm.userName" required maxlength="64" /></label><label>开始时间<input v-model="shiftForm.startTime" type="datetime-local" required /></label><label>结束时间<input v-model="shiftForm.endTime" type="datetime-local" required /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showShiftForm = false">取消</button><ActionButton class="primary" :loading="saving" loading-text="保存中…">保存班次</ActionButton></div></form></BaseModal>
  </div>
</template>
