<script setup lang="ts">
import { onMounted, ref } from "vue";
import { CalendarClock, Pencil, PhoneCall, Plus, Shield, Trash2 } from "@lucide/vue";
import { itsmApi } from "@/api/modules";
import BaseModal from "@/components/BaseModal.vue";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const schedules = ref<Record<string, unknown>[]>([]);
const shifts = ref<Record<string, unknown>[]>([]);
const current = ref<Record<string, unknown>>({ members: [] });
const error = ref("");
const showShiftForm = ref(false);
const showScheduleForm = ref(false);
const editingId = ref(0);
const saving = ref(false);
const scheduleForm = ref({ scheduleCode: "", scheduleName: "", serviceCiCode: "", timezone: "Asia/Shanghai", enabled: true });
const shiftForm = ref({ scheduleId: 0, roleType: "PRIMARY", userId: 2, userName: "", startTime: "", endTime: "" });

async function load() {
  try {
    [schedules.value, shifts.value, current.value] = await Promise.all([
      itsmApi.schedules(),
      itsmApi.shifts(),
      itsmApi.currentOnCall("ops-ticket-service"),
    ]);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "排班加载失败";
  }
}

function localDateTime(value: unknown) {
  if (!value) return "";
  const date = new Date(String(value));
  const offset = date.getTimezoneOffset() * 60000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function openSchedule() {
  scheduleForm.value = { scheduleCode: "", scheduleName: "", serviceCiCode: "", timezone: "Asia/Shanghai", enabled: true };
  showScheduleForm.value = true;
}

async function saveSchedule() {
  saving.value = true;
  try {
    await itsmApi.createSchedule(scheduleForm.value);
    showScheduleForm.value = false;
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "排班计划保存失败"; }
  finally { saving.value = false; }
}

function openShift(row?: Record<string, unknown>) {
  editingId.value = Number(row?.id || 0);
  const start = new Date(); start.setMinutes(0, 0, 0);
  const end = new Date(start); end.setDate(end.getDate() + 1);
  shiftForm.value = row
    ? { scheduleId: Number(row.scheduleId), roleType: String(row.roleType), userId: Number(row.userId), userName: String(row.userName), startTime: localDateTime(row.startTime), endTime: localDateTime(row.endTime) }
    : { scheduleId: Number(schedules.value[0]?.id || 0), roleType: "PRIMARY", userId: 2, userName: "", startTime: localDateTime(start), endTime: localDateTime(end) };
  showShiftForm.value = true;
}

async function saveShift() {
  saving.value = true;
  try {
    const payload = { ...shiftForm.value, startTime: `${shiftForm.value.startTime}:00`, endTime: `${shiftForm.value.endTime}:00` };
    if (editingId.value) await itsmApi.updateShift(editingId.value, payload);
    else await itsmApi.createShift(payload);
    showShiftForm.value = false;
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "班次保存失败"; }
  finally { saving.value = false; }
}

async function removeShift(id: number) {
  if (!window.confirm("确认删除这个班次？")) return;
  try { await itsmApi.deleteShift(id); await load(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "班次删除失败"; }
}
onMounted(load);
</script>

<template>
  <div class="stack-page">
    <section class="page-lead"><div><span class="eyebrow">ON-CALL LITE</span><h2>值班排班</h2><p>展示当前主备值班和未来两周排班；SLA 升级按 PRIMARY、SECONDARY 顺序通知。</p></div></section>
    <p v-if="error" class="inline-error">{{ error }}</p>
    <section class="oncall-current-grid">
      <article v-for="member in (current.members as Record<string, unknown>[])" :key="String(member.roleType)" class="panel oncall-card">
        <span :class="String(member.roleType).toLowerCase()"><PhoneCall v-if="member.roleType === 'PRIMARY'" :size="22" /><Shield v-else :size="22" /></span>
        <div><small>{{ member.roleType }} 当前值班</small><strong>{{ member.userName }}</strong><p>{{ new Date(String(member.startTime)).toLocaleString("zh-CN") }} — {{ new Date(String(member.endTime)).toLocaleString("zh-CN") }}</p></div>
      </article>
      <article v-if="current.fallback" class="panel oncall-card warning"><CalendarClock :size="24" /><div><strong>无有效排班</strong><p>{{ current.message }}</p></div></article>
    </section>
    <section class="panel table-panel">
      <header class="panel-header"><div><span class="eyebrow">NEXT 14 DAYS</span><h3>排班明细</h3></div><div class="row-actions"><span class="panel-count">{{ shifts.length }}</span><button v-if="auth.isAdmin" class="button secondary" @click="openSchedule"><Plus :size="15" />新建计划</button><button v-if="auth.isAdmin" class="button primary" @click="openShift()"><Plus :size="15" />新增班次</button></div></header>
      <div class="responsive-table"><table><thead><tr><th>排班</th><th>角色</th><th>值班人</th><th>开始</th><th>结束</th><th v-if="auth.isAdmin">操作</th></tr></thead><tbody><tr v-for="shift in shifts" :key="String(shift.id)"><td>{{ shift.scheduleName }}</td><td><span class="status-badge" :class="`status-${String(shift.roleType).toLowerCase()}`">{{ shift.roleType }}</span></td><td>{{ shift.userName }} (#{{ shift.userId }})</td><td>{{ new Date(String(shift.startTime)).toLocaleString("zh-CN") }}</td><td>{{ new Date(String(shift.endTime)).toLocaleString("zh-CN") }}</td><td v-if="auth.isAdmin"><div class="row-actions"><button class="icon-button" title="编辑班次" @click="openShift(shift)"><Pencil :size="15" /></button><button class="icon-button danger" title="删除班次" @click="removeShift(Number(shift.id))"><Trash2 :size="15" /></button></div></td></tr></tbody></table></div>
    </section>
    <BaseModal v-if="showScheduleForm" title="新建值班计划" @close="showScheduleForm = false"><form class="form-grid" @submit.prevent="saveSchedule"><label>计划编码<input v-model.trim="scheduleForm.scheduleCode" required maxlength="64" /></label><label>计划名称<input v-model.trim="scheduleForm.scheduleName" required maxlength="128" /></label><label>关联 CI<input v-model.trim="scheduleForm.serviceCiCode" maxlength="64" placeholder="例如 order-service" /></label><label>时区<input v-model.trim="scheduleForm.timezone" required maxlength="64" /></label><label class="full"><input v-model="scheduleForm.enabled" type="checkbox" /> 启用计划</label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showScheduleForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存计划" }}</button></div></form></BaseModal>
    <BaseModal v-if="showShiftForm" :title="editingId ? '编辑班次' : '新增班次'" @close="showShiftForm = false"><form class="form-grid" @submit.prevent="saveShift"><label>值班计划<select v-model.number="shiftForm.scheduleId" required><option v-for="schedule in schedules" :key="String(schedule.id)" :value="Number(schedule.id)">{{ schedule.scheduleName }}</option></select></label><label>角色<select v-model="shiftForm.roleType"><option>PRIMARY</option><option>SECONDARY</option></select></label><label>用户 ID<input v-model.number="shiftForm.userId" type="number" min="1" required /></label><label>值班人<input v-model.trim="shiftForm.userName" required maxlength="64" /></label><label>开始时间<input v-model="shiftForm.startTime" type="datetime-local" required /></label><label>结束时间<input v-model="shiftForm.endTime" type="datetime-local" required /></label><p v-if="error" class="form-error full">{{ error }}</p><div class="form-actions full"><button type="button" class="button secondary" @click="showShiftForm = false">取消</button><button class="button primary" :disabled="saving">{{ saving ? "保存中…" : "保存班次" }}</button></div></form></BaseModal>
  </div>
</template>
