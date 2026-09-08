<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { eventLifecycleApi, type RecoveryBinding, type RecoveryBindingInput } from '@/api/event-lifecycle';
import { definitelyRejected } from '@/utils/event-workspace';
import BaseModal from '@/components/BaseModal.vue';
import FormField from '@/components/FormField.vue';
import InlineError from '@/components/InlineError.vue';

const props = defineProps<{ ticketId: number; version: number; closed?: boolean }>();
const emit = defineEmits<{ saved: []; status: [blockers: string[]] }>();
const binding = ref<RecoveryBinding>();
const error = ref('');
const notice = ref('');
const open = ref(false);
const busy = ref(false);
const target = ref('');
const environment = ref('');
const reason = ref('');
const uncertain = ref(false);
let attempt: RecoveryBindingInput | undefined;
let generation = 0;
async function load() {
  const epoch = ++generation;
  try { const result = await eventLifecycleApi.binding(props.ticketId); if (epoch === generation) { binding.value = result; emit('status', result.blockers); if (!open.value) error.value = ''; } }
  catch (cause) { if (epoch === generation) { binding.value = undefined; error.value = cause instanceof Error ? cause.message : '恢复关联暂不可读'; emit('status', [error.value]); } }
}
function edit() {
  if (!binding.value?.canEdit || busy.value) return;
  if (!uncertain.value && !reason.value) { target.value = binding.value.targetCode || ''; environment.value = binding.value.environment || ''; attempt = undefined; }
  open.value = true; error.value = '';
}
function close() {
  if (busy.value) return;
  if (reason.value && !window.confirm('补全内容尚未提交，关闭后保留本页输入，是否关闭？')) return;
  open.value = false;
}
async function save() {
  if (!binding.value?.canEdit || busy.value || !target.value || !environment.value || !reason.value.trim()) return;
  const epoch = generation;
  busy.value = true; error.value = '';
  if (!attempt) attempt = { targetCode: target.value, environment: environment.value, reason: reason.value.trim(), version: binding.value.version, requestId: crypto.randomUUID() };
  uncertain.value = true;
  try {
    const result = await eventLifecycleApi.bind(props.ticketId, attempt);
    if (epoch !== generation) return;
    binding.value = result; emit('status', result.blockers); uncertain.value = false; attempt = undefined; open.value = false; reason.value = '';
    notice.value = '恢复关联已更新。请重新提交实际处理结果，系统将重新积累恢复证据。';
    emit('saved');
  } catch (cause) {
    if (epoch !== generation) return;
    if (definitelyRejected(cause) || (cause as { status?: number })?.status === 409 || (cause as { code?: number })?.code === 40900) { uncertain.value = false; attempt = undefined; }
    error.value = `${cause instanceof Error ? cause.message : '保存结果未确认'}${uncertain.value ? '。请用同一请求重试，避免重复提交。' : ''}`;
    // Keep entered values; refresh only the expected version after a definite rejection.
    if (!uncertain.value) { try { binding.value = await eventLifecycleApi.binding(props.ticketId); emit('status', binding.value.blockers); } catch { /* Keep the original error and user input. */ } }
  } finally { if (epoch === generation) busy.value = false; }
}
watch(() => [props.ticketId, props.version], (_, previous) => {
  if (previous && previous[0] !== props.ticketId) { open.value = false; reason.value = ''; uncertain.value = false; attempt = undefined; notice.value = ''; busy.value = false; }
  if (!busy.value) void load();
}, { immediate: true });
onBeforeUnmount(() => { generation++; });
</script>

<template>
  <section class="recovery-binding" :class="{ 'needs-binding': binding?.blockers.length }" aria-label="恢复关联与规则">
    <InlineError v-if="error && !open" :message="error" /><button v-if="error && !open" class="button text" type="button" @click="load">重新读取恢复关联</button>
    <p v-if="notice" class="event-notice" role="status">{{ notice }}</p>
    <template v-if="binding">
      <div v-if="binding.blockers.length" class="recovery-binding-missing"><strong>恢复确认缺少必要关联</strong><ul><li v-for="item in binding.blockers" :key="item">{{ item }}</li></ul><button v-if="binding.canEdit" class="button primary small" @click="edit">补全恢复关联</button><p v-else>{{ binding.editHint }}</p></div>
      <details><summary>恢复关联与规则 · {{ binding.targetCode || '未关联服务' }} / {{ binding.environment || '未设置环境' }}</summary><p>{{ binding.rule }}</p><p v-if="binding.observedEnvironment && binding.kind === 'REGULAR'">实际观测环境：{{ binding.observedEnvironment }}</p><p v-if="binding.incidentId">原演练：{{ binding.incidentId }}</p><p>{{ binding.editHint }}</p><button v-if="binding.canEdit && !binding.blockers.length && !closed" class="button secondary small" @click="edit">更正恢复关联</button></details>
    </template>
    <BaseModal v-if="open && binding" title="补全恢复关联" @close="close"><form class="event-dialog-body" @submit.prevent="save"><InlineError v-if="error" :message="error" /><p>选择本事件实际受影响的服务及事件环境。已保存的历史记录保留，原恢复确认将失效。</p><FormField label="实际受影响服务 *"><select v-model="target" required :disabled="busy || uncertain"><option value="" disabled>请选择服务</option><option v-for="item in binding.targets" :key="item" :value="item">{{ item }}</option></select></FormField><FormField label="事件环境 *"><select v-model="environment" required :disabled="busy || uncertain"><option value="" disabled>请选择环境</option><option v-for="(observed, source) in binding.environments" :key="source" :value="source">{{ source }}（观测 {{ observed }}）</option></select></FormField><FormField label="更正原因及核对依据 *"><textarea v-model="reason" rows="4" required maxlength="500" :disabled="busy || uncertain" /></FormField><p>{{ binding.rule }}。保存后请重新提交处理结果，旧样本不能作为本次恢复证据。</p><button class="button primary" :disabled="busy || !target || !environment || !reason.trim()">{{ busy ? '正在保存…' : uncertain ? '使用同一请求重试' : '保存关联并重新验证' }}</button></form></BaseModal>
  </section>
</template>

<style scoped>
.recovery-binding { margin: 16px 0; color: var(--text-secondary, #64748b); font-size: 13px; }
.recovery-binding summary { cursor: pointer; color: var(--text-secondary, #64748b); }
.recovery-binding details p { margin: 10px 0; }
.recovery-binding-missing { padding: 14px 16px; margin-bottom: 12px; border: 1px solid #e6c884; background: #fffbf0; border-radius: 10px; color: #795820; }
.recovery-binding-missing strong { font-size: 14px; }
.recovery-binding-missing ul { padding-left: 18px; margin: 8px 0 12px; }
</style>
