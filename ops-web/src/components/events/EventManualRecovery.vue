<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { automationApi, type Target } from '@/api/automation';
import { useAuthStore } from '@/stores/auth';
import { definitelyRejected } from '@/utils/event-workspace';
import BaseModal from '@/components/BaseModal.vue';
import InlineError from '@/components/InlineError.vue';
const props = defineProps<{ ticketId: number; targetCode?: string; incidentId?: string }>();
const emit = defineEmits<{ restored: [] }>();
const auth = useAuthStore();
const open = ref(false);
const busy = ref(false);
const loading = ref(false);
const error = ref('');
const notice = ref('');
const target = ref<Target>();
const uncertain = ref(false);
let attempt: { target: Target; key: string } | undefined;
let disposed = false;
const supported = computed(() => !!props.incidentId && ['ops-demo-order-service', 'ops-demo-notification-service'].includes(props.targetCode || '') && (auth.isAdmin || auth.isOps || auth.isDemo));
const matched = computed(() => target.value?.incidentId === props.incidentId && target.value?.targetCode === props.targetCode);
const permitted = computed(() => auth.isAdmin || auth.isOps || target.value?.ownedByCurrentActor === true);
const ready = computed(() => matched.value && permitted.value && target.value?.status === 'FAULT_ACTIVE' && !!target.value.expectedRevision && !!actionName.value);
const actionName = computed(() => ({ NACOS_REDIS_CONFIG_DRIFT: '恢复本次演练前的配置', SENTINEL_RULE_REGRESSION: '恢复本次演练前的限流规则', RABBITMQ_CONSUMER_PAUSED: '恢复本次演练的消费者' } as Record<string, string>)[target.value?.scenarioCode || '']);
async function load() {
  if (!supported.value || loading.value) return;
  loading.value = true; error.value = '';
  try { const result = await automationApi.target(props.targetCode); if (!disposed) target.value = result; }
  catch (cause) { if (!disposed) { target.value = undefined; error.value = cause instanceof Error ? cause.message : '当前演练状态不可读'; } }
  finally { if (!disposed) loading.value = false; }
}
async function show() { open.value = true; await load(); }
async function restore() {
  if (busy.value || !ready.value) return;
  busy.value = true; error.value = '';
  if (!attempt) attempt = { target: JSON.parse(JSON.stringify(target.value!)) as Target, key: `manual-${crypto.randomUUID()}` };
  uncertain.value = true;
  try {
    const result = await automationApi.restore(attempt.target, attempt.key);
    if (disposed) return;
    target.value = result; uncertain.value = false; attempt = undefined;
    notice.value = result.recoveryVerified ? '人工恢复动作已执行且当前探针通过。请记录实际处理结果并继续事件恢复确认。' : '恢复动作已受理，继续观察真实探针；请核对后记录处理结果。';
    emit('restored');
  } catch (cause) {
    if (disposed) return;
    if (definitelyRejected(cause) || (cause as { status?: number })?.status === 409 || (cause as { code?: number })?.code === 40900) { uncertain.value = false; attempt = undefined; }
    error.value = `${cause instanceof Error ? cause.message : '恢复结果未确认'}${uncertain.value ? '。刷新核对现场；仍可执行时，重试会复用同一请求。' : ''}`;
  } finally { if (!disposed) busy.value = false; }
}
onBeforeUnmount(() => { disposed = true; });
</script>
<template>
  <button v-if="supported" class="button secondary" type="button" @click="show">执行演练恢复</button>
  <BaseModal v-if="open" title="恢复当前事件的演练" @close="!busy && (open = false)"><div class="event-dialog-body"><p>事件 EVT-{{ ticketId }} · {{ targetCode }}</p><p>此操作实际恢复本次演练配置，恢复来源记为人工操作。</p><InlineError v-if="error" :message="error" /><p v-if="notice" class="event-notice" role="status">{{ notice }}</p><p v-if="loading">正在核对当前现场…</p><template v-else-if="target"><p v-if="!matched">该事件的演练已不再是目标当前现场，不能对其他 incident 执行恢复。请查看原事件记录。</p><p v-else-if="!permitted">只有管理员、运维或演练所有者可以恢复当前现场。</p><p v-else-if="target.status !== 'FAULT_ACTIVE'">当前目标没有待恢复故障。业务探针 HTTP {{ target.business.httpStatus || '未知' }}；恢复确认仍需在事件中分别记录。</p><p v-else>{{ actionName || '该场景暂无可执行的恢复动作' }}。当前版本 {{ target.expectedRevision }}。</p></template><button class="button secondary" :disabled="busy || loading" @click="load">刷新当前现场</button><button v-if="ready" class="button primary" :disabled="busy || loading" @click="restore">{{ busy ? '正在执行…' : uncertain ? '使用同一请求重试恢复' : '确认执行恢复' }}</button><p>执行恢复不会自动保存人工处理说明，也不会代替技术确认、业务确认或关闭。</p><RouterLink :to="{ path: '/automation', query: { tab: 'experience', target: targetCode, incidentId, ticketId } }">查看演练观测与恢复来源 →</RouterLink></div></BaseModal>
</template>
