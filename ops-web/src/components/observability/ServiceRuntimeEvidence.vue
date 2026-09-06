<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { Box, GitBranch, RefreshCw } from '@lucide/vue';
import InlineError from '@/components/InlineError.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import { observabilityV3Api, type InstanceSnapshot, type TraceList, type TraceDetail } from '@/api/observabilityV3';
import { observationTime, metric } from '@/utils/observability';
import { useAuthStore } from '@/stores/auth';
const props = defineProps<{ ciCode: string; environment: string; timeRange: string; targetCiCode?: string }>();
const auth = useAuthStore();
const instances = ref<InstanceSnapshot>(); const traces = ref<TraceList>(); const selected = ref<TraceDetail>();
const loading = ref(false); const traceLoading = ref(false); const errors = ref<string[]>([]); const traceError = ref(''); const selectedId = ref('');
let epoch = 0; let selectionEpoch = 0; let disposed = false;
const scope = computed(() => ({ environment: props.environment, timeRange: props.timeRange }));
const spanRows = computed(() => {
  const all = selected.value?.spans || []; const ids = new Map(all.map(span => [span.spanId, span]));
  return [...all].sort((a, b) => Date.parse(a.startTime) - Date.parse(b.startTime)).map(span => {
    let parent = span.parentSpanId; let depth = 0; const seen = new Set([span.spanId]);
    while (parent && ids.has(parent) && !seen.has(parent) && depth < 8) { seen.add(parent); depth++; parent = ids.get(parent)?.parentSpanId; }
    return { ...span, depth };
  });
});
async function load() {
  const own = ++epoch; selectionEpoch++; traceLoading.value = false; traceError.value = ''; loading.value = true; errors.value = []; instances.value = undefined; traces.value = undefined; selected.value = undefined; selectedId.value = '';
  const actor = auth.token; const code = props.ciCode;
  const results = await Promise.allSettled([observabilityV3Api.instances(code, scope.value), observabilityV3Api.traces({ ...scope.value, ciCode: code, ...(props.targetCiCode ? { targetCiCode: props.targetCiCode } : {}) })]);
  if (disposed || own !== epoch || actor !== auth.token) return;
  if (results[0].status === 'fulfilled' && results[0].value.ciCode === code) instances.value = results[0].value;
  else errors.value.push('实例证据未取得，请重试；不以采集 target 冒充进程实例。');
  if (results[1].status === 'fulfilled') traces.value = results[1].value;
  else errors.value.push('调用链暂不可用；登记依赖仍保留在服务关系中。');
  loading.value = false;
}
async function openTrace(id: string) {
  const own = ++selectionEpoch; const actor = auth.token; selected.value = undefined; selectedId.value = id; traceError.value = ''; traceLoading.value = true;
  try { const result = await observabilityV3Api.trace(id, props.ciCode, props.environment);
    if (disposed || own !== selectionEpoch || actor !== auth.token) return;
    if (result.traceId !== id) throw new Error('返回的调用链与当前选择不一致'); selected.value = result;
  } catch (cause) { if (!disposed && own === selectionEpoch) traceError.value = cause instanceof Error ? cause.message : '调用链详情读取失败'; }
  finally { if (own === selectionEpoch) traceLoading.value = false; }
}
watch(() => [props.ciCode, props.environment, props.timeRange, props.targetCiCode, auth.token], load, { flush: 'sync' });
onMounted(load); onBeforeUnmount(() => { disposed = true; epoch++; selectionEpoch++; });
</script>
<template>
  <div class="obs-runtime-evidence"><div class="obs-evidence-heading"><strong>真实实例与调用证据</strong><button class="text-button" type="button" :disabled="loading" @click="load"><RefreshCw :size="14" />刷新</button></div>
    <InlineError v-for="error in errors" :key="error" :message="error" /><LoadingState v-if="loading" text="读取服务实例与已保留的调用证据…" />
    <section v-if="instances" class="obs-runtime-section"><h3><Box :size="16" />运行实例</h3><p class="obs-muted">{{ instances.message }}{{ instances.runtimeServiceCiCode && instances.runtimeServiceCiCode !== ciCode ? ` 当前逻辑服务共用 ${instances.runtimeServiceCiCode} 的运行进程。` : '' }}{{ !instances.podSupported ? ' 当前环境不提供 Kubernetes Pod。' : '' }}</p>
      <article v-for="instance in instances.items" :key="instance.instanceId" class="obs-instance-card"><header><strong>{{ instance.runtimeKind }} · {{ instance.instanceId }}</strong><span>{{ instance.observationStatus }}</span></header><dl class="oa-definition-list"><div><dt>首次观测</dt><dd>{{ observationTime(instance.firstSeenAt) }}</dd></div><div><dt>最近观测</dt><dd>{{ observationTime(instance.lastSeenAt) }}</dd></div><div><dt>证据来源</dt><dd>{{ instance.source }}</dd></div><div v-if="instance.metadata?.runtimeVersion"><dt>版本</dt><dd>{{ instance.metadata.runtimeVersion }}</dd></div><div v-if="instance.metadata?.processId"><dt>进程</dt><dd>{{ instance.metadata.processId }}</dd></div></dl></article>
      <EmptyState v-if="!instances.items.length" title="此服务暂无可用实例证据" :description="instances.message || '尚未接入实例身份或当前时间范围没有有效观测。'" />
    </section>
    <section v-if="traces" class="obs-runtime-section"><h3><GitBranch :size="16" />已保留的调用链</h3><p class="obs-muted">{{ traces.message }}调用链可能经过采样；保留数量不等于真实请求总量。</p><button v-for="trace in traces.items.slice(0, 20)" :key="trace.traceId" class="obs-trace-row" :class="{ selected: selectedId === trace.traceId }" @click="openTrace(trace.traceId)"><span><strong>{{ trace.rootService }}</strong><small>{{ trace.traceId }} · {{ observationTime(trace.startTime) }}</small></span><span>{{ metric(trace.durationMs, ' ms') }}<small>{{ trace.serviceCount == null ? '服务数未统计' : trace.serviceCount + ' 个服务' }}</small></span></button><EmptyState v-if="!traces.items.length" title="此窗口暂无保留的调用链" :description="traces.message || '可能没有请求、尚未采样或链路已超出保留期；不能据此认定依赖不存在。'" /></section>
    <LoadingState v-if="traceLoading" text="读取所选调用链…" /><InlineError v-if="traceError" :message="traceError" />
    <section v-if="selected" class="obs-runtime-section"><h3>调用链详情</h3><p class="obs-muted">{{ selected.traceId }} · {{ selected.source }}{{ selected.partial ? ' · 部分跨度缺失，不能视为完整链路' : '' }}</p><details v-for="span in spanRows" :key="span.spanId" class="obs-span-row" :style="{ marginLeft: `${span.depth * 10}px` }"><summary><span>{{ span.ciCode || span.peerService || '未映射服务' }}<small>{{ span.operation }}</small></span><b>{{ metric(span.durationMs, ' ms') }}</b><em>{{ span.status }}</em></summary><dl class="oa-definition-list"><div><dt>跨度 / 类型</dt><dd>{{ span.spanId }} · {{ span.kind }}</dd></div><div><dt>实例</dt><dd>{{ span.instanceId || '未关联实例身份' }}</dd></div><div><dt>开始时间</dt><dd>{{ observationTime(span.startTime) }}</dd></div><div v-if="span.peerService"><dt>对端</dt><dd>{{ span.peerService }} · 对端标识不证明其中间件服务端健康</dd></div></dl></details></section>
  </div>
</template>
