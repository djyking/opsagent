<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue';
import { Check, Clock3, ShieldCheck } from '@lucide/vue';
import type { PendingApproval } from '@/api/automation';
import { approvalDescription, approvalKey } from '@/utils/automation-presentation';
import '@/styles/components/automation-approval.css';

const props = defineProps<{ approval: PendingApproval; available: boolean; busy?: boolean; unavailableReason?: string }>();
const emit = defineEmits<{ decision: [decision: { approved: boolean; reason: string }] }>();
const description = computed(() => approvalDescription(props.approval));
const reason = ref('');
const fieldId = useId();
watch(() => approvalKey(props.approval), () => { reason.value = ''; });
const valid = computed(() => props.available && !props.busy && !!reason.value.trim() && reason.value.trim().length <= 500);
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }); }
function decide(approved: boolean) { if (valid.value) emit('decision', { approved, reason: reason.value.trim() }); }
</script>

<template>
  <article class="approval-card">
    <header class="approval-card-heading"><span class="approval-card-icon"><ShieldCheck :size="22" /></span><div><span class="approval-card-eyebrow">{{ description.input ? '需要您补充信息' : '执行前需要您确认' }}</span><h3>{{ description.title }}</h3></div></header>
    <dl class="approval-card-facts">
      <div><dt>操作目标</dt><dd>{{ description.target }}<RouterLink v-if="approval.ticketId > 0" :to="`/tickets/${approval.ticketId}`">工单 #{{ approval.ticketId }}</RouterLink><RouterLink v-else-if="description.configuration" to="/observability/config/managed?ciCode=ops-demo-order-service">查看配置与应用情况</RouterLink></dd></div>
      <div><dt>预期变化</dt><dd>{{ description.change }}</dd></div>
      <div><dt>影响范围</dt><dd>{{ description.scope }}</dd></div>
      <div v-if="description.configuration"><dt>批准基础版本</dt><dd><code>{{ description.baseRevision || '未取得版本摘要' }}</code></dd></div>
      <div v-for="change in description.configChanges" :key="change.field"><dt>{{ change.field }}</dt><dd>{{ change.before || '（空字符串）' }} → {{ change.after || '（空字符串）' }}</dd></div>
    </dl>
    <p v-if="description.prompt" class="approval-card-prompt">{{ description.prompt }}</p>
    <p class="approval-card-expiry"><Clock3 :size="15" /><span>有效期至 {{ date(approval.expires_at) }}</span></p>
    <details class="approval-technical"><summary>技术详情与原始参数</summary><dl><div><dt>运行</dt><dd>{{ approval.run_id }}</dd></div><div><dt>演练事件</dt><dd>{{ approval.incidentId }}</dd></div><div><dt>审批修订</dt><dd>{{ approval.revision }}</dd></div><div><dt>参数摘要</dt><dd>{{ approval.args_hash }}</dd></div></dl><pre>{{ JSON.stringify(approval.payload, null, 2) }}</pre></details>
    <form v-if="available" class="approval-card-form" @submit.prevent="decide(true)">
      <label :for="fieldId">{{ description.input ? '补充信息或拒绝原因' : '审批说明' }}<span>必填 · 最多 500 字</span></label>
      <textarea :id="fieldId" v-model="reason" rows="3" maxlength="500" required :disabled="busy" :placeholder="description.input ? '请填写当前处理需要的信息…' : '请说明您核对的目标、预期变化或拒绝原因…'"></textarea>
      <div class="approval-card-actions"><button class="button primary" type="submit" :disabled="!valid"><Check :size="16" />{{ busy ? '正在提交…' : description.input ? '提交信息并继续' : '批准当前动作' }}</button><button class="button secondary" type="button" :disabled="!valid" @click="decide(false)">拒绝继续</button></div>
    </form>
    <p v-else class="approval-card-unavailable" role="status">{{ unavailableReason || '此审批已过期或运行状态已变化。请刷新或查看运行详情。' }}</p>
  </article>
</template>
