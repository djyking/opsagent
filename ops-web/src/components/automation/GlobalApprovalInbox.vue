<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { ArrowUpRight, RefreshCw, ShieldCheck } from '@lucide/vue';
import BaseModal from '@/components/BaseModal.vue';
import ApprovalCard from './ApprovalCard.vue';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import { approvalDescription, approvalKey } from '@/utils/automation-presentation';
import { ref, watch } from 'vue';
const inbox = useApprovalInboxStore();
const router = useRouter();
const route = useRoute();
const decisionError = ref('');
watch(() => route.fullPath, () => { if (inbox.open) inbox.later(); });
watch(() => inbox.selected && approvalKey(inbox.selected), () => { decisionError.value = ''; });
async function decide(decision: { approved: boolean; reason: string }) {
  const approval = inbox.selected;
  if (!approval) return;
  decisionError.value = '';
  try { await inbox.decide(approval, decision.approved, decision.reason); }
  catch (cause) { decisionError.value = cause instanceof Error ? cause.message : '审批提交失败，请核对最新状态'; }
}
function viewRun() {
  if (!inbox.selected) return;
  const id = inbox.selected.run_id;
  inbox.later(); void router.push({ path: '/automation', query: { run: id } });
}
</script>

<template>
  <BaseModal v-if="inbox.open" title="待处理审批" description="核对本次动作后决定。选择稍后处理，可以从任意页面右上角再次打开。" wide @close="inbox.later">
    <div class="approval-inbox">
      <div class="approval-inbox-toolbar"><span>{{ inbox.error ? '待审批数据未能更新' : `${inbox.count} 项待处理` }}</span><button type="button" class="button text" :disabled="inbox.loading || !!inbox.busy" @click="decisionError = ''; inbox.refresh(false)"><RefreshCw :size="15" />刷新</button></div>
      <p v-if="decisionError || inbox.error" class="approval-inbox-error" role="alert">{{ decisionError || inbox.error }}</p>
      <p v-if="inbox.notice" class="approval-inbox-notice" role="status">{{ inbox.notice }}</p>
      <nav v-if="inbox.availableItems.length > 1 || (!inbox.selected && inbox.count)" class="approval-inbox-list" aria-label="选择待审批动作"><button v-for="item in inbox.availableItems" :key="approvalKey(item)" type="button" :disabled="!!inbox.busy" :aria-pressed="inbox.selected?.id === item.id" @click="decisionError = ''; inbox.select(item)"><ShieldCheck :size="17" /><span><strong>{{ approvalDescription(item).title }}</strong><small>工单 #{{ item.ticketId }} · {{ item.nodeLabel }}</small></span></button></nav>
      <template v-if="inbox.selected"><ApprovalCard :key="approvalKey(inbox.selected)" :approval="inbox.selected" :available="inbox.isCurrent(inbox.selected)" :busy="!!inbox.busy" unavailable-reason="此审批已不在当前待处理列表中，可能已处理、过期或运行已暂停。可刷新或进入运行详情核对。" @decision="decide" /><button type="button" class="button text approval-inbox-run" @click="viewRun">查看完整运行与证据<ArrowUpRight :size="15" /></button></template>
      <p v-else-if="inbox.loading && !inbox.count" class="approval-inbox-empty" role="status">正在核对待审批动作…</p>
      <p v-else-if="!inbox.error && !inbox.count" class="approval-inbox-empty">当前没有需要您处理的审批。</p>
    </div>
    <template #footer><button type="button" class="button secondary" @click="inbox.later">{{ inbox.count ? '稍后处理' : '关闭' }}</button></template>
  </BaseModal>
</template>
