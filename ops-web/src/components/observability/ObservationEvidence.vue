<script setup lang="ts">
import type { ServiceNode } from '@/api/observability';
import { healthScopeLabels, observationLabels, observationReason, observationState, observationTime } from '@/utils/observability';
defineProps<{ node: ServiceNode }>();
const stepLabels: Record<string, string> = { binding: '采集绑定', 'prometheus-query': '指标查询', 'target-scrape': '目标抓取', 'business-probe': '独立业务探针' };
</script>
<template>
  <div class="obs-observation-summary"><strong>{{ observationLabels[observationState(node)] || '待观测' }}</strong><p>{{ observationReason(node) }}</p><small>最近成功：{{ observationTime(node.observation?.lastSuccessfulScrapeAt) }}</small></div>
  <dl class="oa-definition-list"><div><dt>判断范围</dt><dd>{{ healthScopeLabels[node.healthScope || ''] || '暂无有效证据' }}</dd></div><div><dt>生命周期</dt><dd>{{ ({ ACTIVE: '在用', INACTIVE: '停用', RETIRED: '退役' } as Record<string, string>)[node.lifecycle || ''] || '未登记' }}{{ node.overlays?.maintenance ? ' · 维护中' : '' }}{{ node.drilling ? ' · 演练中' : '' }}</dd></div><div><dt>有效样本</dt><dd>{{ observationTime(node.observation?.sampledAt) }}</dd></div><div><dt>样本有效期</dt><dd>{{ node.observation?.maximumSampleAgeSeconds ? `${node.observation.maximumSampleAgeSeconds} 秒` : '未返回' }}</dd></div></dl>
  <details class="obs-disclosure"><summary>采集步骤与实例</summary><div class="obs-disclosure-body"><article v-for="check in node.observation?.checks || []" :key="check.step" class="obs-check-evidence"><strong>{{ stepLabels[check.step] || check.step }}</strong><span>{{ observationLabels[check.status] || ({ NOT_RUN: '尚未执行' } as Record<string, string>)[check.status] || check.status }}</span><p>{{ check.message }}</p><small>{{ check.reasonCode }}</small></article><article v-for="instance in node.observation?.instances || []" :key="instance.instance" class="obs-check-evidence"><strong>{{ instance.instance }}</strong><span>{{ instance.health }}</span><p>{{ instance.lastError || '该抓取目标未报告错误' }}</p><small>抓取 {{ observationTime(instance.lastScrape) }} · 成功 {{ observationTime(instance.lastSuccessfulScrapeAt) }}</small></article><p v-if="!node.observation?.instances?.length" class="obs-muted">尚无可用的匹配采集目标。</p></div></details>
</template>
