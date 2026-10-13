<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { CalendarClock, Pencil, PhoneCall, Plus, RefreshCw, Shield, Trash2 } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import type { CurrentOnCall, PageResponse } from "@/types/api";
import BaseModal from "@/components/BaseModal.vue";
import PageHeader from "@/components/PageHeader.vue";
import InlineError from "@/components/InlineError.vue";
import LoadingState from "@/components/LoadingState.vue";
import PaginationBar from "@/components/PaginationBar.vue";
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
const shiftPage = ref<PageResponse<Record<string, unknown>>>({ records: [], total: 0, pageNum: 1, pageSize: 10 });
const calendarShifts = ref<Record<string, unknown>[]>([]);
const pageNum = ref(1);
const pageSize = ref(10);
const scheduleId = ref(0);
const pageLoading = ref(false);
const calendarLoading = ref(false);
const pageError = ref("");
const calendarError = ref("");
const currentLoaded = ref(false);
const deletingId = ref(0);
let pageRequest = 0;
let calendarRequest = 0;
let metadataRequest = 0;
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

const calendarStart = ref(startOfToday());
const days = computed(() => Array.from({ length: 7 }, (_, index) => {
  const date = new Date(calendarStart.value); date.setDate(date.getDate() + index); return date;
}));
const calendarRows = computed(() => ["PRIMARY", "SECONDARY"].map((role) => ({
  role,
  cells: days.value.map((day) => {
    const end = new Date(day); end.setDate(end.getDate() + 1);
    return { day, shifts: calendarShifts.value.filter((row) => String(row.roleType) === role
      && new Date(String(row.startTime)) < end && new Date(String(row.endTime)) > day) };
  }),
})));
function startOfToday() { const date = new Date(); date.setHours(0, 0, 0, 0); return date; }
function dayLabel(day: Date) { return `${day.getMonth() + 1}/${day.getDate()}`; }
function shiftTime(row: Record<string, unknown>, day: Date) {
  const nextDay = new Date(day); nextDay.setDate(nextDay.getDate() + 1);
  const start = new Date(String(row.startTime)); const end = new Date(String(row.endTime));
  const clock = (date: Date) => date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", hour12: false });
  return `${start < day ? "跨日" : clock(start)} → ${end >= nextDay ? "次日" : clock(end)}`;
}

async function loadShiftPage() {
  const version = ++pageRequest;
  pageLoading.value = true; pageError.value = "";
  try {
    const result = await itsmApi.shiftPage({ pageNum: pageNum.value, pageSize: pageSize.value, scheduleId: scheduleId.value || undefined });
    if (version !== pageRequest) return;
    shiftPage.value = result; pageNum.value = result.pageNum;
  } catch (cause) {
    if (version === pageRequest) pageError.value = cause instanceof Error ? cause.message : "班次列表加载失败";
  } finally { if (version === pageRequest) pageLoading.value = false; }
}
async function loadCalendar() {
  const version = ++calendarRequest;
  calendarLoading.value = true; calendarError.value = "";
  const end = new Date(calendarStart.value); end.setDate(end.getDate() + 7);
  try {
    const result = await itsmApi.shiftCalendar({
      startTime: `${localDateTime(calendarStart.value)}:00`, endTime: `${localDateTime(end)}:00`, scheduleId: scheduleId.value || undefined,
    });
    if (version === calendarRequest) calendarShifts.value = result;
  } catch (cause) {
    if (version === calendarRequest) calendarError.value = cause instanceof Error ? cause.message : "排班日历加载失败";
  } finally { if (version === calendarRequest) calendarLoading.value = false; }
}
async function loadMetadata() {
  const version = ++metadataRequest;
  error.value = "";
  try {
    const [plans, members] = await Promise.all([itsmApi.schedules(), itsmApi.currentOnCall()]);
    if (version !== metadataRequest) return;
    schedules.value = plans; current.value = members; currentLoaded.value = true;
  } catch (cause) {
    if (version === metadataRequest) error.value = cause instanceof Error ? cause.message : "排班信息加载失败";
  }
}

async function load() {
  calendarStart.value = startOfToday();
  await Promise.all([loadMetadata(), loadShiftPage(), loadCalendar()]);
}
function changePage(value: number) {
  if (pageLoading.value || value === shiftPage.value.pageNum) return;
  pageNum.value = value; loadShiftPage();
}
function localDateTime(value: unknown) {
  if (!value) return ""; const date = new Date(String(value)); const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}
function openSchedule() { scheduleForm.value = { scheduleCode: "", scheduleName: "", serviceCiCode: "", timezone: "Asia/Shanghai", enabled: true }; showScheduleForm.value = true; }
async function saveSchedule() {
  if (saving.value) return;
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
  if (deletingId.value) return;
  if (!window.confirm("确认删除这个班次？")) return;
  deletingId.value = id;
  try { await itsmApi.deleteShift(id); toast.show("班次已删除"); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "班次删除失败"; }
  finally { deletingId.value = 0; }
}
watch(scheduleId, () => { pageNum.value = 1; loadShiftPage(); loadCalendar(); });
watch(pageSize, () => { pageNum.value = 1; loadShiftPage(); });
onMounted(load);
onBeforeUnmount(() => { ++pageRequest; ++calendarRequest; ++metadataRequest; });
</script>

<template>
  <div class="stack-page oncall-page">
    <PageHeader title="值班排班" description="查看当前主备值班与未来排班">
      <template #meta><span>{{ schedules.length }} 个值班计划 · 当前及未来班次独立管理</span></template>
      <template #actions><button class="button secondary" :disabled="pageLoading || calendarLoading" @click="load"><RefreshCw :size="15" />刷新</button><button v-if="auth.isAdmin" class="button secondary" @click="openSchedule"><Plus :size="15" />新建计划</button><button v-if="auth.isAdmin" class="button primary" @click="openShift()"><Plus :size="15" />新增班次</button></template>
    </PageHeader>
    <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
    <section class="oncall-current-grid" aria-label="当前有效值班（全部计划）">
      <div class="oncall-current-heading"><h3>当前有效值班</h3><span>全部计划 · 不受下方计划筛选影响</span></div>
      <article v-for="member in current.members" :key="`${member.scheduleCode}-${member.roleType}-${member.userId}`" class="oncall-current-item" :class="{ secondary: member.roleType !== 'PRIMARY' }">
        <span><PhoneCall v-if="member.roleType === 'PRIMARY'" :size="18" /><Shield v-else :size="18" /></span>
        <div><small>{{ statusLabel(member.roleType) }} · {{ member.scheduleName }}</small><strong>{{ member.userName }}</strong><p><CalendarClock :size="13" />{{ formatDateTime(String(member.startTime)) }} → {{ formatDateTime(String(member.endTime)) }}</p></div>
      </article>
      <GuidedEmptyState v-if="currentLoaded && current.fallback && !error" kind="oncall" title="当前没有有效班次" description="未排班可能影响告警升级和责任人分配。请核对各计划的值班安排。" :action="auth.isAdmin ? (schedules.length ? '新建班次' : '新建值班计划') : undefined" @action="schedules.length ? openShift() : openSchedule()" />
    </section>
    <div class="oncall-view-toolbar">
      <div class="segmented-control oncall-view-switch"><button :class="{ active: activeView === 'schedule' }" :aria-pressed="activeView === 'schedule'" @click="activeView = 'schedule'">排班日历</button><button :class="{ active: activeView === 'manage' }" :aria-pressed="activeView === 'manage'" @click="activeView = 'manage'">班次管理</button></div>
      <label class="oncall-plan-filter"><span>值班计划</span><select v-model.number="scheduleId"><option :value="0">全部计划</option><option v-for="schedule in schedules" :key="String(schedule.id)" :value="Number(schedule.id)">{{ schedule.scheduleName }}</option></select></label>
    </div>
    <section v-if="activeView === 'schedule'" class="oncall-schedule-surface">
      <header class="section-header"><div><h3>未来 7 天</h3><p>仅展示已启用计划 · 跨日班次按天呈现 · 多班次可在格内滚动</p></div><span class="panel-count">{{ calendarLoading || calendarError ? '—' : calendarShifts.length }} 个班次</span></header>
      <LoadingState v-if="calendarLoading" />
      <div v-else-if="calendarError" class="oncall-query-error"><InlineError :message="calendarError" /><button class="button secondary" @click="loadCalendar">重新加载日历</button></div>
      <div v-else class="oncall-timeline-grid" role="region" aria-label="未来七天主备值班" tabindex="0">
        <div class="timeline-corner">值班角色</div><div v-for="(day, index) in days" :key="day.toISOString()" class="timeline-day" :class="{ today: index === 0 }"><strong>{{ dayLabel(day) }}</strong><span>{{ index === 0 ? '今天' : day.toLocaleDateString('zh-CN', { weekday: 'short' }) }}</span></div>
        <template v-for="row in calendarRows" :key="row.role">
          <div class="timeline-role">{{ statusLabel(row.role) }}</div>
          <div v-for="cell in row.cells" :key="`${row.role}-${cell.day.toISOString()}`" class="timeline-member" :class="{ empty: !cell.shifts.length }">
            <template v-if="cell.shifts.length">
              <small class="timeline-shift-count">{{ cell.shifts.length }} 个班次</small>
              <div class="timeline-shift-list" :aria-label="`${dayLabel(cell.day)}${statusLabel(row.role)}班次`" tabindex="0">
                <article v-for="shift in cell.shifts" :key="String(shift.id)" class="timeline-shift-card" :title="`${shift.scheduleName} · ${formatDateTime(String(shift.startTime))} → ${formatDateTime(String(shift.endTime))}`">
                  <strong>{{ shift.userName }}</strong><span>{{ shift.scheduleName }}</span><time>{{ shiftTime(shift, cell.day) }}</time>
                </article>
              </div>
            </template>
            <span v-else class="timeline-empty-label">未排班</span>
          </div>
        </template>
      </div>
    </section>
    <TableSurface v-else>
      <template #header><div><h3>班次管理</h3><p>当前及未来班次 · 可维护已停用计划的排班</p></div><span class="panel-count">{{ pageLoading || pageError ? '—' : shiftPage.total }} 个班次</span></template>
      <LoadingState v-if="pageLoading" />
      <div v-else-if="pageError" class="oncall-query-error"><InlineError :message="pageError" /><button class="button secondary" @click="loadShiftPage">重新加载班次</button></div>
      <GuidedEmptyState v-else-if="!shiftPage.records.length" kind="oncall" :title="scheduleId ? '该计划暂无当前及未来班次' : '暂无当前及未来班次'" description="可切换值班计划查看，或创建计划后安排主备值班。" :action="auth.isAdmin ? (schedules.length ? '新建班次' : '新建值班计划') : undefined" @action="schedules.length ? openShift() : openSchedule()" />
      <table v-else><thead><tr><th>排班</th><th>角色</th><th>值班人</th><th>开始</th><th>结束</th><th v-if="auth.isAdmin"></th></tr></thead><tbody><tr v-for="shift in shiftPage.records" :key="String(shift.id)"><td>{{ shift.scheduleName }}</td><td><StatusBadge :value="String(shift.roleType)" /></td><td>{{ shift.userName }} <small>#{{ shift.userId }}</small></td><td>{{ formatDateTime(String(shift.startTime)) }}</td><td>{{ formatDateTime(String(shift.endTime)) }}</td><td v-if="auth.isAdmin"><div class="row-actions reveal-on-row"><button class="icon-button" title="编辑班次" @click="openShift(shift)"><Pencil :size="15" /></button><button class="icon-button" title="删除班次" :disabled="!!deletingId" @click="removeShift(Number(shift.id))"><Trash2 :size="15" /></button></div></td></tr></tbody></table>
      <template #footer>
        <fieldset class="oncall-pagination" :disabled="pageLoading">
          <label>每页<select v-model.number="pageSize" aria-label="每页班次数"><option :value="10">10 条</option><option :value="20">20 条</option><option :value="50">50 条</option></select></label>
          <PaginationBar v-if="!pageError" :page="shiftPage.pageNum" :page-size="shiftPage.pageSize" :total="shiftPage.total" @change="changePage" />
        </fieldset>
      </template>
    </TableSurface>
    <BaseModal v-if="showScheduleForm" title="新建值班计划" description="创建班次使用的排班计划" @close="showScheduleForm = false"><form class="form-grid" @submit.prevent="saveSchedule"><label>计划编码<input v-model.trim="scheduleForm.scheduleCode" required maxlength="64" /></label><label>计划名称<input v-model.trim="scheduleForm.scheduleName" required maxlength="128" /></label><label>关联 CI<input v-model.trim="scheduleForm.serviceCiCode" maxlength="64" placeholder="例如 order-service" /></label><label>时区<input v-model.trim="scheduleForm.timezone" required maxlength="64" /></label><label class="full"><input v-model="scheduleForm.enabled" type="checkbox" /> 启用计划</label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showScheduleForm = false">取消</button><ActionButton class="primary" :loading="saving" loading-text="保存中…">保存计划</ActionButton></div></form></BaseModal>
    <BaseModal v-if="showShiftForm" :title="editingId ? '编辑班次' : '新增班次'" description="设置值班角色与生效时间" @close="showShiftForm = false"><form class="form-grid" @submit.prevent="saveShift"><label>值班计划<select v-model.number="shiftForm.scheduleId" required><option v-for="schedule in schedules" :key="String(schedule.id)" :value="Number(schedule.id)">{{ schedule.scheduleName }}</option></select></label><label>角色<select v-model="shiftForm.roleType"><option value="PRIMARY">主值班</option><option value="SECONDARY">备值班</option></select></label><label>用户 ID<input v-model.number="shiftForm.userId" type="number" min="1" required /></label><label>值班人<input v-model.trim="shiftForm.userName" required maxlength="64" /></label><label>开始时间<input v-model="shiftForm.startTime" type="datetime-local" required /></label><label>结束时间<input v-model="shiftForm.endTime" type="datetime-local" required /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showShiftForm = false">取消</button><ActionButton class="primary" :loading="saving" loading-text="保存中…">保存班次</ActionButton></div></form></BaseModal>
  </div>
</template>
