<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ArrowLeft, Maximize, RefreshCw, Monitor } from '@lucide/vue';
import ServiceTopology from '@/components/observability/ServiceTopology.vue';
import ServiceHealthBadge from '@/components/observability/ServiceHealthBadge.vue';
import EmptyState from '@/components/EmptyState.vue';
import InlineError from '@/components/InlineError.vue';
import { useObservabilityStore } from '@/stores/observability';
import { effectiveHealth, observationTime } from '@/utils/observability';
import '@/styles/pages/observability.css';
const store = useObservabilityStore(); const route = useRoute(); const root = ref<HTMLElement>(); const fullscreenError = ref('');
let timer: ReturnType<typeof setInterval> | undefined;
const nodes = computed(() => store.topologyData?.nodes || []);
const healthy = computed(() => nodes.value.filter(node => effectiveHealth(node) === 'HEALTHY').length);
const attention = computed(() => nodes.value.filter(node => ['CRITICAL', 'DEGRADED', 'DRILLING'].includes(effectiveHealth(node))));
const unknown = computed(() => nodes.value.filter(node => effectiveHealth(node) === 'UNKNOWN').length);
const alerts = computed(() => store.topologyData?.activeAlertCount);
async function fullscreen() { try { if (document.fullscreenElement) await document.exitFullscreen(); else await root.value?.requestFullscreen(); } catch { fullscreenError.value = '当前浏览器未允许全屏，可以继续使用窗口展示。'; } }
onMounted(() => { store.selectedEnvironment = String(route.query.environment || 'ALL'); store.timeRange = String(route.query.timeRange || '15m'); store.topologyMode = 'CONFIGURED'; void store.load(); timer = setInterval(() => { if (!document.hidden && !store.loading) void store.load(); }, 15_000); });
onBeforeUnmount(() => { clearInterval(timer); store.invalidate(); });
</script>
<template><div class="obs-wallboard" ref="root"><header><div class="obs-wallboard-brand"><Monitor :size="27" /><div><span>OPSAGENT · SERVICE OBSERVABILITY</span><h1>服务运行全景</h1></div></div><div class="row-actions"><small>{{ observationTime(store.topologyData?.checkedAt) }} · 15 秒自动刷新</small><button class="icon-button" title="刷新大屏" aria-label="刷新大屏" :disabled="store.loading" @click="store.load"><RefreshCw :size="17" /></button><button class="button secondary" @click="fullscreen"><Maximize :size="16" />全屏</button><RouterLink class="button secondary" :to="{ path: '/observability/topology', query: route.query }"><ArrowLeft :size="16" />返回工作台</RouterLink></div></header><InlineError v-if="store.error || fullscreenError" :message="store.error || fullscreenError" /><div class="obs-wallboard-metrics"><article><span>服务节点</span><strong>{{ nodes.length }}</strong><small>当前环境台账</small></article><article><span>有效健康观测</span><strong>{{ healthy }}</strong><small>有有效采样且无已知异常</small></article><article><span>需关注服务</span><strong>{{ attention.length }}</strong><small>降级 / 故障 / 演练</small></article><article><span>活动告警</span><strong>{{ store.topologyData ? alerts ?? '—' : '—' }}</strong><small>{{ unknown }} 个节点待观测</small></article></div><div class="obs-wallboard-main"><section class="panel"><ServiceTopology v-if="nodes.length" :nodes="nodes" :edges="store.topologyData?.edges || []" :layout="store.topologyData?.layout" wallboard /><EmptyState v-else title="等待服务观测数据" description="数据暂不可用时，不展示默认健康状态。" /></section><aside class="panel obs-wallboard-attention"><h2>活动告警与服务异常</h2><p class="obs-muted">只读展示 · 处置请返回工作台</p><article v-for="alert in store.topologyData?.activeAlerts?.slice(0, 5)" :key="String(alert.id || alert.fingerprint)"><strong>{{ alert.title || alert.summary || alert.alertName || alert.name }}</strong><p>{{ alert.ciCode || alert.serviceCiCode }} · {{ alert.severity }}</p><small>{{ observationTime(String(alert.startsAt || '')) }}</small></article><article v-for="node in attention" :key="node.ciCode"><strong>{{ node.ciName }}</strong><ServiceHealthBadge :health="effectiveHealth(node)" /><p>{{ node.statusReason }}</p><small>活动告警 {{ node.activeAlertCount ?? '—' }}</small></article><EmptyState v-if="!attention.length && !store.topologyData?.activeAlerts?.length" title="暂无已知服务异常" description="请同时关注待观测节点与数据源可用性。" /><div class="obs-source-note"><span v-for="source in store.topologyData?.dataSources" :key="source.name">{{ source.name }} · {{ source.healthy ? '数据可用' : source.message || '暂不可用' }}</span></div></aside></div></div></template>
