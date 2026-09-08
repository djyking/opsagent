<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { FileCog, RefreshCw, Save, Search, ShieldCheck, RotateCw } from '@lucide/vue';
import { useAuthStore } from '@/stores/auth';
import { configurationFilesApi as api, type ConfigurationFile, type ConfigurationFileDetail, type ConfigurationDraft, type ConfigurationTask, type ConfigurationHistory, type FileAction } from '@/api/configuration-files';
import { configurationChanges, fileActionLabels, fileStatusLabels, terminalFileTasks } from '@/utils/configuration-files';
import InlineError from '@/components/InlineError.vue';
import BaseModal from '@/components/BaseModal.vue';
import '@/styles/pages/configuration-files.css';
const props = defineProps<{ ciCode?: string }>();
const auth = useAuthStore(); const canRead = computed(() => (auth.isAdmin || auth.isOps) && !auth.isDemo); const canEdit = computed(() => auth.isAdmin && !auth.isDemo);
const files = ref<ConfigurationFile[]>([]), detail = ref<ConfigurationFileDetail>(), draft = ref<ConfigurationDraft>(), task = ref<ConfigurationTask>(), history = ref<ConfigurationHistory>();
const selectedId = ref(''), keyword = ref(''), category = ref(''), error = ref(''), pollError = ref(''), busy = ref(''), comment = ref('');
const values = reactive<Record<string, unknown>>({}), replaceSecrets = reactive<Record<string, boolean>>({});
const raw = ref(''), rawMode = ref(false), rollback = ref(false), reviewOpen = ref(false);
const filtered = computed(() => files.value.filter(file => `${file.label} ${file.serviceId}`.toLowerCase().includes(keyword.value.trim().toLowerCase())));
const categories = computed(() => [...new Set(detail.value?.fields.map(field => field.category || '其他') || [])]);
const visibleFields = computed(() => (detail.value?.fields || []).filter(field => (field.category || '其他') === category.value));
const dirty = computed(() => !!detail.value && (rawMode.value ? raw.value !== detail.value.redactedContent : detail.value.fields.some(field => field.sensitive ? replaceSecrets[field.key] : JSON.stringify(values[field.key]) !== JSON.stringify(field.type === 'json' ? JSON.stringify(field.value, null, 2) : field.value))));
const applyLabel = computed(() => detail.value?.applyAction === 'RELOAD' ? '加载' : '重启');
const taskRunning = computed(() => !!task.value && !task.value.awaitingVerification && !terminalFileTasks.has(task.value.status));
const canVerify = computed(() => canEdit.value && !!task.value && (task.value.awaitingVerification || ['RESULT_UNKNOWN', 'FAILED', 'PARTIAL_FAILURE'].includes(task.value.status)));
let lastDraftForm = ''; let taskRead = 0; let refreshedTask = '';
const formSignature = () => JSON.stringify({ rawMode: rawMode.value, raw: rawMode.value ? raw.value : undefined, values, replaceSecrets });
let epoch = 0, timer: ReturnType<typeof setInterval> | undefined; let disposed = false;
const requests = new Map<string, string>();
function id(key: string) { if (!requests.has(key)) requests.set(key, crypto.randomUUID()); return requests.get(key)!; }
function state(value?: string) { return fileStatusLabels[value || ''] || value || '尚未取得'; }
function date(value?: string) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }
function shown(value: unknown) { return value == null ? '未设置' : typeof value === 'object' ? JSON.stringify(value) : String(value); }
function approvedAction(action: FileAction) { return action === 'PUBLISH_RESTART' ? `发布并${applyLabel.value}` : action === 'APPLY_ONLY' ? `${applyLabel.value}已发布版本` : fileActionLabels[action]; }
function resetForm(value: ConfigurationFileDetail) {
  for (const key of Object.keys(values)) delete values[key]; for (const key of Object.keys(replaceSecrets)) delete replaceSecrets[key];
  for (const field of value.fields) values[field.key] = field.sensitive ? '' : field.type === 'json' ? JSON.stringify(field.value, null, 2) : field.value;
  raw.value = value.redactedContent; rawMode.value = false; category.value = categories.value[0] || ''; requests.clear();
}
async function select(fileId: string, force = false) {
  if (!force && dirty.value && !confirm('当前编辑尚未保存，确定离开这份配置吗？')) return;
  const current = ++epoch; selectedId.value = fileId; detail.value = undefined; task.value = undefined; draft.value = undefined; history.value = undefined; error.value = ''; pollError.value = ''; busy.value = 'read';
  try { const value = await api.detail(fileId); if (current !== epoch || disposed) return; detail.value = value; resetForm(value); if (value.lastTaskId) await readTask(value.lastTaskId, current); }
  catch (cause) { if (current === epoch) error.value = cause instanceof Error ? cause.message : '配置读取失败'; }
  finally { if (current === epoch) busy.value = ''; }
}
async function load() {
  if (busy.value || !canRead.value) return; error.value = ''; const current = epoch;
  try { const result = await api.list(); if (disposed || current !== epoch) return; files.value = result.items; const selected = result.items.find(file => file.id === selectedId.value) || result.items.find(file => file.serviceId === props.ciCode) || result.items[0]; if (selected) await select(selected.id); }
  catch (cause) { if (!disposed && current === epoch) error.value = cause instanceof Error ? cause.message : '受控文件目录读取失败'; }
}
async function perform(name: string, operation: () => Promise<void>) {
  if (busy.value) return; const current = epoch; busy.value = name; error.value = '';
  try { await operation(); } catch (cause) { if (current === epoch) error.value = cause instanceof Error ? cause.message : '操作结果未确认，请核对记录'; }
  finally { if (current === epoch) busy.value = ''; }
}
async function createDraft(action: FileAction) {
  if (!detail.value || !canEdit.value || !detail.value.editable || taskRunning.value) return;
  await perform('draft', async () => {
    const current = epoch, file = detail.value!;
    const body = { baseVersion: file.version, action, rollbackOnFailure: action !== 'PUBLISH_ONLY' && rollback.value, ...(action === 'APPLY_ONLY' ? { changes: [] } : rawMode.value ? { content: raw.value } : { changes: configurationChanges(file.fields, values, replaceSecrets) }) };
    if (action !== 'APPLY_ONLY' && !dirty.value) throw new Error('请先修改需要发布的参数。');
    if (action === 'APPLY_ONLY' && dirty.value) throw new Error('当前还有未保存修改，请先保存草稿或撤销修改，再应用已发布版本。');
    const formAtCreate = formSignature();
    const result = await api.create(file.id, { ...body, requestId: id(`create:${JSON.stringify(body)}`) });
    if (current !== epoch || disposed) return; draft.value = result; lastDraftForm = formAtCreate; comment.value = ''; reviewOpen.value = true;
  });
}
async function submit() { if (!canEdit.value || draft.value?.status !== 'DRAFT') return; await perform('submit', async () => { const value = draft.value!, current = epoch; const result = await api.submit(value.id, value.digest, id(`submit:${value.id}`)); if (current === epoch) draft.value = result; }); }
async function approve(decision: 'APPROVE' | 'REJECT') { if (!canEdit.value || draft.value?.status !== 'PENDING_APPROVAL') return; await perform('approve', async () => { const value = draft.value!, current = epoch; const result = await api.approve(value.id, value.digest, decision, comment.value, id(`decision:${JSON.stringify([value.id, decision, comment.value])}`)); if (current === epoch) draft.value = result; }); }
async function execute() { if (!canEdit.value || draft.value?.status !== 'APPROVED' || taskRunning.value) return; await perform('execute', async () => { const value = draft.value!, current = epoch; const result = await api.execute(value.id, value.digest, id(`execute:${value.id}`)); if (current !== epoch || disposed) return; task.value = result; reviewOpen.value = false; await readTask(result.id, current); }); }
async function verifyTask() {
  if (!canVerify.value || !task.value) return;
  await perform('verify', async () => { const value = task.value!, current = epoch; const result = await api.verify(value.id, id(`verify:${value.id}:${value.updatedAt}`)); if (current === epoch && !disposed) { ++taskRead; task.value = result; pollError.value = ''; } });
}
async function readTask(taskId: string, current = epoch) {
  const own = ++taskRead;
  try {
    const result = await api.task(taskId);
    if (current !== epoch || disposed || own !== taskRead) return;
    task.value = result; pollError.value = '';
    if (result.filePublished && (terminalFileTasks.has(result.status) || result.awaitingVerification) && result.id !== refreshedTask && lastDraftForm && lastDraftForm === formSignature()) {
      const value = await api.detail(selectedId.value);
      if (current !== epoch || disposed || own !== taskRead) return;
      detail.value = value; resetForm(value); refreshedTask = result.id; lastDraftForm = '';
    }
  } catch (cause) { if (current === epoch && !disposed && own === taskRead) pollError.value = cause instanceof Error ? cause.message : '执行结果尚未读取，请继续查询同一任务'; }
}
function toggleRawMode() { if (dirty.value && !confirm('切换编辑方式会丢弃当前未保存修改，是否继续？')) return; const next = !rawMode.value; if (detail.value) resetForm(detail.value); rawMode.value = next; }
async function readHistory() { const current = epoch; try { const result = await api.history(selectedId.value); if (current === epoch && !disposed) history.value = result; } catch (cause) { if (current === epoch) error.value = cause instanceof Error ? cause.message : '历史记录读取失败'; } }
async function openDraft(value: ConfigurationDraft) { await perform('read-draft', async () => { const current = epoch; const result = await api.draft(value.id); if (current === epoch) { draft.value = result; reviewOpen.value = true; } }); }
function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = ''; } }
onBeforeRouteLeave(() => !dirty.value || confirm('配置编辑尚未保存，确定离开吗？'));
watch(() => props.ciCode, code => { const file = files.value.find(item => item.serviceId === code); if (file && file.id !== selectedId.value) void select(file.id); });
watch(() => [auth.user?.userId, canRead.value], () => { epoch++; busy.value = ''; files.value = []; detail.value = undefined; draft.value = undefined; task.value = undefined; history.value = undefined; requests.clear(); if (canRead.value) void load(); });
onMounted(() => { void load(); window.addEventListener('beforeunload', beforeUnload); timer = setInterval(() => { if (!document.hidden && taskRunning.value && task.value) void readTask(task.value.id); }, 2500); });
onBeforeUnmount(() => { disposed = true; epoch++; clearInterval(timer); window.removeEventListener('beforeunload', beforeUnload); requests.clear(); });
</script>
<template>
  <section class="managed-files" aria-label="真实配置文件管理">
    <p v-if="!canRead" class="panel managed-file-empty">当前账号可查看运行状态；真实配置文件需管理员或运维角色读取。</p><InlineError v-if="error" :message="error" /><div v-if="canRead" class="managed-file-layout">
      <aside class="panel managed-file-directory"><header><strong>服务配置文件</strong><button class="icon-button" :disabled="!!busy" aria-label="刷新配置文件目录" @click="load"><RefreshCw :size="16" /></button></header><label class="search-box"><Search :size="15" /><input v-model="keyword" placeholder="搜索服务或文件" aria-label="搜索受控文件" /></label><button v-for="file in filtered" :key="file.id" class="managed-file-item" :class="{ active: file.id === selectedId }" :disabled="!!busy" @click="select(file.id)"><FileCog :size="19" /><span><strong>{{ file.label }}</strong><small>{{ file.serviceId }} · {{ file.format.toUpperCase() }}</small></span></button><p v-if="!files.length" class="managed-file-note">{{ busy ? '正在读取目录…' : '尚未取得受控文件，请刷新或核对执行器接入。' }}</p></aside>
      <main class="panel managed-file-content">
        <template v-if="detail"><header class="managed-file-heading"><div><h2>{{ detail.label }}</h2><p>{{ detail.serviceId }} · {{ detail.format.toUpperCase() }} · {{ detail.fields.length }} 项可管理参数</p></div><span class="managed-version">文件版本 {{ detail.version.slice(0, 10) }}</span></header>
          <p v-if="!detail.editable" class="managed-file-note">{{ detail.reason || '此文件暂未接入编辑能力。' }}</p>
          <nav v-if="categories.length" class="managed-category-tabs" aria-label="参数分类"><button v-for="item in categories" :key="item" :class="{ active: category === item }" :disabled="rawMode && dirty" @click="category = item; rawMode = false">{{ item }}</button></nav>
          <form v-if="!rawMode" class="managed-fields" @submit.prevent="createDraft('PUBLISH_ONLY')"><div v-for="field in visibleFields" :key="field.key" class="managed-field"><span><strong>{{ field.label }}</strong><small v-if="field.unit">{{ field.unit }}{{ field.min != null ? ` · 最小 ${field.min}` : '' }}{{ field.max != null ? ` · 最大 ${field.max}` : '' }}</small><details><summary>参数名称</summary><code>{{ field.key }}</code></details></span><span class="managed-field-input"><template v-if="field.sensitive"><label class="managed-secret-toggle"><input v-model="replaceSecrets[field.key]" type="checkbox" :disabled="!canEdit || !field.editable || taskRunning" />{{ field.hasValue ? '已设置 · 替换新值' : '未设置 · 设置新值' }}</label><input v-if="replaceSecrets[field.key]" v-model="values[field.key]" type="password" autocomplete="new-password" :aria-label="field.label" :disabled="!canEdit || !!busy || taskRunning" placeholder="仅提交新值，不回显原值" /></template><select v-else-if="field.type === 'boolean'" v-model="values[field.key]" :aria-label="field.label" :disabled="!canEdit || !field.editable || !!busy || taskRunning"><option :value="true">开启</option><option :value="false">关闭</option></select><select v-else-if="field.options?.length" v-model="values[field.key]" :aria-label="field.label" :disabled="!canEdit || !field.editable || !!busy || taskRunning"><option v-for="option in field.options" :key="String(option)" :value="String(option)">{{ option }}</option></select><textarea v-else-if="field.type === 'json'" v-model="values[field.key] as string" rows="5" :aria-label="field.label" :disabled="!canEdit || !field.editable || !!busy || taskRunning" spellcheck="false" /><input v-else v-model="values[field.key]" :type="['integer','number'].includes(field.type) ? 'number' : 'text'" :step="field.type === 'integer' ? 1 : 'any'" :min="field.min" :max="field.max" :aria-label="field.label" :disabled="!canEdit || !field.editable || !!busy || taskRunning" autocomplete="off" /></span></div><p v-if="!visibleFields.length" class="managed-file-note">此分类暂无已接入的可管理参数。</p></form>
          <div v-else class="managed-raw-editor"><textarea v-model="raw" :disabled="!canEdit || !!busy || taskRunning" rows="14" aria-label="编辑原配置文件" spellcheck="false" /><p>保存时只允许修改当前登记的参数，其他字段保留。</p></div>
          <footer v-if="canEdit && detail.editable" class="managed-file-actions"><span>{{ dirty ? '有未保存修改 · 下一步先核对差异' : '修改参数后，核对差异并提交审批' }}</span><button class="button secondary" :disabled="!!busy || !dirty || taskRunning" @click="createDraft('PUBLISH_ONLY')"><Save :size="15" />仅发布</button><button class="button primary" :disabled="!!busy || !dirty || taskRunning" @click="createDraft('PUBLISH_RESTART')"><RotateCw :size="15" />发布并{{ applyLabel }}</button><button class="button secondary" :disabled="!!busy || dirty || taskRunning" @click="createDraft('APPLY_ONLY')">{{ applyLabel }}已发布版本</button></footer>
          <details class="managed-file-fold"><summary>原文件、加载证据与执行选项</summary><div><p>文件发布与运行采用分别记录；仅发布不主动重启或热加载。</p><dl><div><dt>文件版本</dt><dd>{{ detail.version }}</dd></div><div><dt>已加载版本</dt><dd>{{ detail.loadedVersion || '尚未取得版本证据' }}</dd></div><div><dt>影响服务</dt><dd>{{ detail.affectedServices.join('、') }}</dd></div></dl><pre>{{ detail.redactedContent }}</pre><button v-if="canEdit && detail.rawEditable && !detail.sensitive" class="button secondary" :disabled="dirty && !rawMode" @click="toggleRawMode">{{ rawMode ? '返回参数表单' : '编辑原文件' }}</button><label v-if="canEdit" class="managed-secret-toggle"><input v-model="rollback" type="checkbox" />将执行失败后的受控恢复纳入本次审批</label></div></details>
          <section v-if="task" class="managed-task" :data-status="task.status"><header><strong>{{ state(task.status) }}</strong><button class="text-button" @click="readTask(task.id)">查询当前结果</button></header><p>{{ task.message }}</p><p v-if="task.awaitingVerification" class="managed-file-note">本次核验已结束，业务证据尚待补齐。复查只验证当前批准版本，不重新发布或重启。</p><button v-if="canVerify" class="button secondary" :disabled="!!busy" @click="verifyTask">复查验证</button><p v-if="pollError" class="inline-error">{{ pollError }}；未重复执行任何动作。</p><div class="managed-task-targets"><article v-for="target in task.targets" :key="target.serviceId"><strong>{{ target.serviceId }}</strong><span>{{ state(target.status) }}</span><small>配置{{ target.loaded ? '已加载' : '未确认加载' }} · 健康{{ target.healthy ? '通过' : '未确认' }} · 业务{{ target.businessVerified ? '验证通过' : '未确认' }}</small></article></div><details><summary>执行步骤与证据</summary><p>任务 {{ task.id }} · 目标版本 {{ task.targetVersion.slice(0, 12) }}</p><article v-for="(event, index) in task.events" :key="index"><time>{{ date(event.at) }}</time> {{ state(event.stage) }} · {{ event.message }}</article><pre v-for="target in task.targets" :key="target.serviceId">{{ JSON.stringify(target.evidence, null, 2) }}</pre></details></section>
          <details class="managed-file-fold" @toggle="($event.target as HTMLDetailsElement).open && readHistory()"><summary>草稿、审批与版本记录</summary><div><p v-if="history && !history.drafts.length">暂无变更记录。</p><article v-for="item in history?.drafts || []" :key="item.id" class="managed-history-row"><div><strong>{{ state(item.status) }} · {{ approvedAction(item.action) }}</strong><small>{{ date(item.createdAt) }} · 用户 #{{ item.createdBy }}</small></div><button class="button secondary small" @click="openDraft(item)">核对差异与审批</button><button v-if="item.taskId" class="text-button" @click="readTask(item.taskId)">执行结果</button></article></div></details>
        </template><p v-else class="managed-file-empty">{{ busy ? '正在读取真实配置文件…' : '选择服务配置，查看并修改实际参数。' }}</p>
      </main>
    </div>
    <BaseModal v-if="reviewOpen && draft" title="核对配置变更" :description="`${approvedAction(draft.action)} · ${state(draft.status)}`" wide @close="reviewOpen = false"><div class="managed-review"><InlineError v-if="error" :message="error" /><p>{{ draft.impact }}</p><p>影响服务：{{ draft.affectedServices.join('、') }}</p><p v-if="draft.rollbackOnFailure">审批包括失败后在已登记范围内恢复旧配置。</p><table class="managed-diff"><thead><tr><th>参数</th><th>修改前</th><th>修改后</th></tr></thead><tbody><tr v-for="item in draft.diff" :key="item.key"><th>{{ item.label || item.key }}</th><td>{{ shown(item.before) }}</td><td>{{ shown(item.after) }}</td></tr></tbody></table><p v-if="!draft.diff.length">不修改配置，使用本次绑定的已发布版本执行{{ applyLabel }}与验证。</p><details><summary>审批绑定的版本</summary><p>基础 {{ draft.baseVersion }}</p><p>目标 {{ draft.targetVersion }}</p><p>摘要 {{ draft.digest }}</p></details><textarea v-if="draft.status === 'PENDING_APPROVAL' && canEdit" v-model="comment" rows="3" aria-label="审批说明" placeholder="审批说明（可选）" /><div v-if="canEdit" class="form-actions"><button class="button secondary" @click="reviewOpen = false">关闭</button><button v-if="draft.status === 'DRAFT'" class="button primary" :disabled="!!busy" @click="submit"><ShieldCheck :size="15" />提交审批</button><template v-if="draft.status === 'PENDING_APPROVAL'"><button class="button secondary" :disabled="!!busy" @click="approve('REJECT')">拒绝</button><button class="button primary" :disabled="!!busy" @click="approve('APPROVE')">批准此版本与动作</button></template><button v-if="draft.status === 'APPROVED'" class="button primary" :disabled="!!busy" @click="execute">执行已批准任务</button></div></div></BaseModal>
  </section>
</template>
