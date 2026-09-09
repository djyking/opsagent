<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { ArrowRight, BookOpen } from '@lucide/vue';
import { publicCasesApi, type PublicCase } from '@/api/public-cases';
import InlineError from '@/components/InlineError.vue';
import EmptyState from '@/components/EmptyState.vue';
const emit = defineEmits<{ start: [scenarioCode: string, targetCode: string] }>();
const cases = ref<PublicCase[]>([]);
const selected = ref('');
const loading = ref(false);
const error = ref('');
let epoch = 0;
async function load() {
  const current = ++epoch; loading.value = true; error.value = '';
  try { const rows = await publicCasesApi.list(); if (current === epoch) cases.value = rows; }
  catch (cause) { if (current === epoch) error.value = cause instanceof Error ? cause.message : '公共案例读取失败'; }
  finally { if (current === epoch) loading.value = false; }
}
function date(value: string) { return new Date(value).toLocaleString('zh-CN', { hour12: false }); }
onMounted(load);
onBeforeUnmount(() => { epoch++; });
defineExpose({ refresh: load, loading });
</script>

<template>
  <section class="public-cases" aria-label="公共案例">
    <header class="public-cases-heading"><p>从真实处置案例了解故障、审批与恢复，也可以发起自己的演练。</p><span>真实历史 · 只读案例</span></header>
    <InlineError v-if="error" :message="error" />
    <p v-if="loading && !cases.length" role="status">正在读取精选案例…</p>
    <EmptyState v-else-if="!error && !cases.length" title="暂未发布公共案例" description="可以进入我的演练，选择隔离故障场景。" />
    <article v-for="item in cases" :key="item.id" class="panel public-case">
      <header><span class="public-case-icon"><BookOpen :size="23" /></span><div><small>{{ item.environment }} · {{ date(item.recordedAt) }}</small><h3>{{ item.title }}</h3></div><span class="public-case-tag">历史案例</span></header>
      <div class="public-case-body"><p class="public-case-summary">{{ item.summary }}</p><div class="public-case-result"><strong>{{ item.result }}</strong><span>{{ item.confirmation }}</span></div></div>
      <div class="public-case-actions"><button class="button secondary" :aria-expanded="selected === item.id" @click="selected = selected === item.id ? '' : item.id">{{ selected === item.id ? '收起过程' : '查看处置过程' }}</button><button class="button primary" @click="emit('start', item.scenarioCode, item.targetCode)">从此案例发起我的演练<ArrowRight :size="16" /></button></div>
      <div v-if="selected === item.id" class="public-case-detail"><ol><li v-for="step in item.steps" :key="step">{{ step }}</li></ol><details><summary>证据与结果边界</summary><p v-for="evidence in item.evidence" :key="evidence">{{ evidence }}</p></details><small>{{ item.source }}</small><p>{{ item.limitation }}</p></div>
    </article>
  </section>
</template>

<style scoped>
.public-cases { display:grid; gap:16px; min-width:0; }
.public-cases-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; color:var(--oa-text-secondary); font-size:13px; }
.public-cases-heading p { margin:0; }
.public-cases-heading > span { flex:none; font-size:12px; color:var(--oa-text-tertiary); }
.public-case { padding:24px; min-width:0; }
.public-case header { display:flex; align-items:center; gap:14px; }
.public-case header > div { min-width:0; }
.public-case header h3 { font-size:18px; line-height:1.5; margin:5px 0 0; overflow-wrap:anywhere; }
.public-case small { color:var(--oa-text-secondary); font-size:12px; }
.public-case-icon { display:grid; place-items:center; width:42px; height:42px; flex:none; border-radius:10px; background:var(--oa-primary-soft); color:var(--oa-primary); }
.public-case-tag { margin-left:auto; font-size:12px; white-space:nowrap; border:1px solid var(--oa-border-subtle); border-radius:6px; padding:4px 8px; color:var(--oa-text-secondary); }
.public-case-body { display:grid; grid-template-columns:minmax(0,1.2fr) minmax(260px,1fr); align-items:start; gap:24px; margin-top:20px; }
.public-case-summary { line-height:1.8; margin:0; max-width:70ch; color:var(--oa-text-secondary); }
.public-case-result { display:grid; gap:7px; padding:14px 16px; background:var(--oa-bg-subtle); border-radius:10px; line-height:1.7; }
.public-case-result strong { font-size:14px; }
.public-case-result span { font-size:12px; color:var(--oa-text-secondary); }
.public-case-actions { display:flex; gap:10px; flex-wrap:wrap; margin-top:20px; }
.public-case-detail { margin-top:20px; padding-top:16px; border-top:1px solid var(--oa-border-subtle); color:var(--oa-text-secondary); line-height:1.8; }
.public-case-detail ol { padding-left:24px; margin:0; }
.public-case-detail li { padding:4px 0; }
.public-case-detail details { margin:14px 0; }
.public-case-detail summary { cursor:pointer; color:var(--oa-primary); }
@media(max-width:1050px) { .public-case-body { grid-template-columns:1fr; gap:16px; } }
@media(max-width:640px) { .public-case { padding:18px; }.public-case-tag,.public-cases-heading > span { display:none; }.public-case-actions button { width:100%; }.public-case header h3 { font-size:16px; } }
</style>
