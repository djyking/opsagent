<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { automationApi, type RunDetail, type RunUsage } from '@/api/automation';
import { runBudgetSummary } from '@/utils/automation-presentation';
import { modelUsageSummary, tokenAmount } from '@/utils/automation-usage';

const props = defineProps<{ run: RunDetail }>();
const usage = ref<RunUsage>();
const loading = ref(false);
const failed = ref(false);
let epoch = 0;
let disposed = false;
const summary = computed(() => loading.value && !usage.value ? '正在读取用量' : modelUsageSummary(usage.value));
const incomplete = computed(() => usage.value?.model.availability === 'AVAILABLE'
  && (usage.value.model.coverage === 'PARTIAL'
    || usage.value.model.unknownCountIsLowerBound || (usage.value.model.unknownUsageAttempts ?? 0) > 0));
const providers = computed(() => usage.value?.model.providers?.map(item => item.provider).join(' / ') || '模型');

async function refreshUsage() {
  const request = ++epoch;
  const id = props.run.id;
  loading.value = true;
  failed.value = false;
  try {
    const result = await automationApi.usage(id);
    if (!disposed && request === epoch && props.run.id === id) usage.value = result;
  } catch {
    if (!disposed && request === epoch) { usage.value = undefined; failed.value = true; }
  } finally {
    if (!disposed && request === epoch) loading.value = false;
  }
}
watch(() => [props.run.id, props.run.status, props.run.state.tokens, props.run.state.turns, props.run.state.toolCount],
  (current, previous) => {
    if (current[0] !== previous?.[0]) usage.value = undefined;
    void refreshUsage();
  }, { immediate: true });
onBeforeUnmount(() => { disposed = true; epoch++; });
</script>

<template>
  <details class="panel automation-fold automation-usage">
    <summary>Token 用量<span>{{ summary }}</span></summary>
    <div class="automation-fold-body">
      <div class="automation-usage-heading"><div><strong>{{ providers }} 已确认用量</strong><small>按供应商返回的请求回执统计</small></div><button class="button secondary" :disabled="loading" @click="refreshUsage">{{ loading ? '正在读取…' : '刷新用量' }}</button></div>
      <p v-if="failed || usage?.model.availability === 'UNAVAILABLE'" role="status">暂时无法取得模型回执，请稍后刷新。缺失用量不按零计算。</p>
      <dl v-else-if="usage" class="automation-usage-values">
        <div><dt>输入</dt><dd>{{ tokenAmount(usage.model.knownInputTokens) }}</dd></div>
        <div><dt>输出</dt><dd>{{ tokenAmount(usage.model.knownOutputTokens) }}</dd></div>
        <div><dt>已知合计</dt><dd>{{ tokenAmount(usage.model.knownTotalTokens) }} <small>Token</small></dd></div>
        <div><dt>未知用量请求</dt><dd>{{ usage.model.unknownCountIsLowerBound ? '至少 ' : '' }}{{ tokenAmount(usage.model.unknownUsageAttempts) }} <small>次</small></dd></div>
      </dl>
      <p v-if="incomplete" class="automation-usage-note">存在未取得完整回执的请求，实际用量可能高于已知合计。</p>
      <p v-if="(usage?.model.pendingCalls ?? 0) > 0" class="automation-usage-note">{{ usage!.model.pendingCalls }} 次调用正在等待回执。</p>
      <dl class="automation-usage-accounting">
        <div><dt>流程预算</dt><dd>{{ runBudgetSummary(run) }}<small>包括模型用量与保守预留，用于限制本流程的后续调用。</small></dd></div>
        <div v-if="usage"><dt>Embedding</dt><dd>{{ usage.embedding.actualUsageKnown ? `实际 ${tokenAmount(usage.embedding.actualTokens)} Token` : '实际用量未知' }}<small>预算预留 {{ tokenAmount(usage.embedding.reservedTokens) }} Token · {{ tokenAmount(usage.embedding.reservationCount) }} 次预留；不计入上方模型已知合计。</small></dd></div>
      </dl>
      <details v-if="(usage?.model.providers?.length ?? 0) > 1" class="automation-usage-providers"><summary>按模型供应商查看</summary><p v-for="item in usage!.model.providers" :key="item.provider">{{ item.provider }} · 输入 {{ tokenAmount(item.knownInputTokens) }} / 输出 {{ tokenAmount(item.knownOutputTokens) }} / 未知 {{ tokenAmount(item.unknownUsageAttempts) }} 次</p></details>
    </div>
  </details>
</template>

<style scoped>
.automation-usage-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:20px; }
.automation-usage-heading strong { display:block; color:var(--text-primary, #26364c); font-size:14px; }
.automation-usage-heading small, .automation-usage-accounting small { display:block; margin-top:6px; color:var(--text-muted, #718096); font-size:12px; line-height:1.6; }
.automation-usage-values { display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)); gap:20px; margin:0 0 18px; }
.automation-usage-values dt, .automation-usage-accounting dt { color:var(--text-muted, #718096); font-size:12px; }
.automation-usage-values dd { margin:8px 0 0; color:var(--text-primary, #26364c); font-size:22px; font-weight:600; font-variant-numeric:tabular-nums; }
.automation-usage-values dd small { font-size:12px; font-weight:400; }
.automation-usage-note { color:var(--text-muted, #718096); font-size:12px; line-height:1.7; }
.automation-usage-accounting { margin:20px 0 0; border-top:1px solid var(--border-color, #e6edf5); }
.automation-usage-accounting > div { display:grid; grid-template-columns:90px minmax(0, 1fr); gap:16px; padding-top:16px; }
.automation-usage-accounting dd { margin:0; color:var(--text-primary, #26364c); font-size:13px; line-height:1.7; }
.automation-usage-providers { margin-top:18px; font-size:12px; }
.automation-usage-providers summary { cursor:pointer; }
@media (max-width:760px) { .automation-usage-values { grid-template-columns:repeat(2, minmax(0, 1fr)); } .automation-usage > summary { flex-wrap:wrap; } }
</style>
