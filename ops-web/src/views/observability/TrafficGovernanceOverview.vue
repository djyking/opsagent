<script setup lang="ts">
import type { TrafficOverview } from '@/api/trafficGovernance';
import { trafficNumber, trafficState } from '@/utils/traffic-governance';
defineProps<{ overview: TrafficOverview }>();
const time = (value: string | null) => value ? new Date(value).toLocaleTimeString('zh-CN', { hour12: false }) : '—';
</script>
<template>
  <section class="traffic-overview" aria-label="平台与AI问答流量">
    <header><h2>流量总览</h2><span>生产平台 · 最近 {{ overview.windowSeconds / 60 }} 分钟 · 每 15 秒刷新</span></header>
    <div class="traffic-streams">
      <article v-for="stream in overview.streams" :key="stream.id" :data-traffic-stream="stream.id">
        <header><h3>{{ stream.label }}</h3><span class="gov-badge" :data-state="stream.status">{{ trafficState(stream.status) }}</span></header>
        <div class="traffic-stream-value"><strong>{{ trafficNumber(stream.requestsPerSecond) }}</strong><span>次 / 秒</span></div>
        <p v-if="stream.id === 'gateway'" class="traffic-stream-count">含健康 / 监控请求 · 其中 API 转发 {{ trafficNumber(stream.apiRequestsPerSecond) }} 次 / 秒</p>
        <p class="traffic-stream-count">近 5 分钟{{ stream.id === 'question' ? '入口通过' : '请求' }} <strong>{{ trafficNumber(stream.requestCount, true) }}</strong> 次<span v-if="stream.id === 'question'"> · Sentinel 拦截 {{ trafficNumber(stream.blockedCount, true) }} 次</span></p>
        <p v-if="stream.status !== 'AVAILABLE'" class="traffic-stream-status" role="status">{{ stream.message }}</p>
        <small>采样于 {{ time(stream.sampledAt) }} · Prometheus</small>
        <details><summary>统计口径</summary><p>{{ stream.scope }}</p><p>{{ stream.message }}</p><p>来源服务：{{ stream.serviceId }}；两类指标有交集，不相加为总访问量。采样更新存在延迟。</p></details>
      </article>
    </div>
  </section>
</template>
<style scoped>
.traffic-overview { display: grid; gap: 14px; }
.traffic-overview > header,.traffic-streams article > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.traffic-overview h2,.traffic-streams h3 { margin: 0; font-size: 15px; color: #354e78; }
.traffic-overview > header > span { color: #7c8ca7; font-size: 12px; }
.traffic-streams { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; }
.traffic-streams article { display: grid; gap: 15px; padding: 22px; border: 1px solid #e1e9f6; border-radius: 15px; background: #fff; min-width: 0; }
.traffic-stream-value { display: flex; align-items: baseline; gap: 10px; }
.traffic-stream-value strong { color: #3658ab; font-size: 32px; font-weight: 600; font-variant-numeric: tabular-nums; }
.traffic-stream-value span,.traffic-stream-count,.traffic-stream-status { color: #71839f; font-size: 13px; }
.traffic-streams p { margin: 0; line-height: 1.7; overflow-wrap: anywhere; }
.traffic-streams small { color: #8a9ab2; font-size: 11px; }
.traffic-streams details { color: #7286a8; font-size: 12px; border-top: 1px solid #edf1f8; padding-top: 12px; }
.traffic-streams details p { margin-top: 8px; }
.traffic-streams summary { cursor: pointer; }
@media (max-width: 720px) { .traffic-streams { grid-template-columns: minmax(0,1fr); } .traffic-overview > header { align-items: flex-start; flex-direction: column; } .traffic-streams article { padding: 18px; } }
</style>
