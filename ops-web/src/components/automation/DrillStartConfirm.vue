<script setup lang="ts">
import { computed, ref } from 'vue';
import type { Target } from '@/api/automation';
import BaseModal from '@/components/BaseModal.vue';
const props = defineProps<{ scenario: string; target?: Target; busy: boolean; canStart: boolean; error?: string }>();
const emit = defineEmits<{ close: []; confirm: [] }>();
const acknowledged = ref(false);
const scenario = computed(() => ({
  NACOS_REDIS_CONFIG_DRIFT: { title: 'Nacos Redis 配置漂移', target: '隔离订单服务', impact: '专用 Redis 配置指向不可连接端口，订单预览将返回 HTTP 503。', restore: '恢复专用 Redis 连接配置，并验证真实订单预览。' },
  SENTINEL_RULE_REGRESSION: { title: 'Sentinel 限流规则回退', target: '隔离订单服务', impact: '专用资源的 QPS 阈值降为零，订单请求将被限流并返回 HTTP 429。', restore: '恢复专用 Sentinel 规则，并验证请求通过。' },
  RABBITMQ_CONSUMER_PAUSED: { title: 'RabbitMQ 消费暂停与消息积压', target: '隔离通知服务', impact: '暂停通知消费者，真实消息继续入队；消息积压，通知送达延迟。', restore: '恢复消费者，并验证消息排空与真实消费回执。' },
} as Record<string, { title: string; target: string; impact: string; restore: string }>)[props.scenario]);
</script>
<template>
  <BaseModal title="发起前确认影响" @close="!busy && emit('close')">
    <div v-if="scenario" class="drill-confirm">
      <div class="drill-confirm-main"><small>{{ scenario.target }} · 隔离演练环境</small><h3>{{ scenario.title }}</h3><p>{{ scenario.impact }}</p></div>
      <dl><div><dt>影响范围</dt><dd>仅影响登记的隔离业务目标。该目标为共享资源，其他访客也可能看到本次故障；生产业务不受本场景影响。</dd></div><div><dt>接下来</dt><dd>注入故障 → 监控建单 → AI 诊断 → 弹窗人工审批 → 执行恢复 → 观察与演练确认。</dd></div><div><dt>恢复保障</dt><dd>{{ scenario.restore }} 故障最长保留 15 分钟。到期保护恢复单独记录，不能计作 AI 恢复成功。</dd></div></dl>
      <p class="drill-confirm-note">暂停或取消 AI 流程不会自动恢复故障。目标处于演练或冷却期时不可重复发起。</p>
      <p v-if="error" role="alert" class="drill-confirm-error">{{ error }}</p>
      <p v-if="!canStart" role="status">当前目标不可发起，请关闭弹窗后刷新并核对健康基线。</p>
      <label class="drill-confirm-check"><input v-model="acknowledged" type="checkbox" :disabled="busy" /><span>我已了解影响，并愿意处理本次演练的审批与恢复确认</span></label>
    </div>
    <template #footer><button class="button secondary" :disabled="busy" @click="emit('close')">暂不发起</button><button class="button primary" :disabled="busy || !canStart || !acknowledged" @click="emit('confirm')">{{ busy ? '正在创建我的演练…' : '确认影响，发起新演练' }}</button></template>
  </BaseModal>
</template>
<style scoped>
.drill-confirm { display:grid; gap:16px; line-height:1.7; padding:4px; }
.drill-confirm-main { padding:16px; background:var(--oa-primary-soft); border-radius:10px; }
.drill-confirm-main small { color:var(--oa-text-secondary); }
.drill-confirm-main h3 { margin:5px 0 8px; font-size:16px; }
.drill-confirm-main p { margin:0; }
.drill-confirm dl { margin:0; display:grid; gap:14px; }
.drill-confirm dl div { display:grid; grid-template-columns:72px minmax(0,1fr); gap:12px; }
.drill-confirm dt { font-weight:600; }
.drill-confirm dd { margin:0; color:var(--oa-text-secondary); }
.drill-confirm-note { font-size:12px; color:var(--oa-text-secondary); margin:0; }
.drill-confirm-check { display:flex; align-items:flex-start; gap:10px; padding-top:12px; border-top:1px solid var(--oa-border-subtle); cursor:pointer; }
.drill-confirm-check input { width:17px; height:17px; min-height:17px; flex:0 0 17px; padding:0; margin:3px 0 0; }
.drill-confirm-check span { min-width:0; }
.drill-confirm-error { color:var(--oa-danger); margin:0; }
@media(max-width:520px) { .drill-confirm dl div { grid-template-columns:1fr; gap:4px; } }
</style>
