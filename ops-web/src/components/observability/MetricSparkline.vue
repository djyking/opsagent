<script setup lang="ts">
import { computed } from 'vue';
import type { MetricHistoryPoint } from '@/api/observability';
const props = defineProps<{ points?: Pick<MetricHistoryPoint, 'at' | 'value'>[]; from?: string; to?: string; label: string }>();
const valid = computed(() => (props.points || []).filter(point => Number.isFinite(point.value) && Number.isFinite(Date.parse(point.at))));
const lines = computed(() => {
  if (valid.value.length < 2) return [];
  const start = Date.parse(props.from || valid.value[0]!.at), end = Date.parse(props.to || valid.value.at(-1)!.at);
  const values = valid.value.map(point => point.value); const low = Math.min(...values), high = Math.max(...values);
  const segments: string[][] = [[]]; let previous = 0;
  for (const point of valid.value) {
    const at = Date.parse(point.at);
    if (previous && at - previous > 150000) segments.push([]);
    segments.at(-1)!.push(`${3 + (at - start) / Math.max(1, end - start) * 106},${high === low ? 19 : 34 - (point.value - low) / (high - low) * 30}`);
    previous = at;
  }
  return segments.filter(segment => segment.length >= 2).map(segment => segment.join(' '));
});
</script>
<template><svg v-if="lines.length" class="obs-sparkline" viewBox="0 0 112 40" role="img" :aria-label="`${label}，${valid.length} 个历史采样`"><title>{{ label }} · {{ valid.length }} 个真实样本</title><path d="M 3 37 H 109" stroke="#e6edf9" /><polyline v-for="(line, index) in lines" :key="index" :points="line" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg><span v-else class="obs-sparkline-empty">历史样本不足</span></template>
<style scoped>.obs-sparkline { display:block; width:100%; height:38px; color:#3478f6; }.obs-sparkline-empty { display:flex; align-items:center; height:38px; color:#91a0b6; font-size:10px; }</style>
