import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { observabilityApi, type TopologySnapshot, type LayoutSnapshot } from '@/api/observability';
import { useAuthStore } from '@/stores/auth';
import { filterTopology } from '@/utils/observability';

export const useObservabilityStore = defineStore('observability', () => {
  const auth = useAuthStore();
  const selectedEnvironment = ref('ALL');
  const timeRange = ref('15m');
  const topologyMode = ref('CONFIGURED');
  const selectedCiCode = ref('');
  const onlyUnhealthy = ref(false);
  const keyword = ref('');
  const refreshInterval = ref(15_000);
  const topologyData = ref<TopologySnapshot>();
  const layoutData = ref<LayoutSnapshot>();
  const layoutError = ref('');
  const loading = ref(false);
  const error = ref('');
  let generation = 0;
  const filters = computed(() => ({ environment: selectedEnvironment.value, timeRange: timeRange.value, mode: topologyMode.value }));
  const visible = computed(() => filterTopology(topologyData.value?.nodes || [], topologyData.value?.edges || [], keyword.value, onlyUnhealthy.value));
  const selectedNode = computed(() => topologyData.value?.nodes.find(node => node.ciCode === selectedCiCode.value));
  function invalidate() { generation++; topologyData.value = undefined; layoutData.value = undefined; layoutError.value = ''; error.value = ''; loading.value = false; }
  async function load() {
    const requestId = ++generation;
    const identity = auth.identity;
    loading.value = true; error.value = '';
    try {
      const [result, layout] = await Promise.allSettled([observabilityApi.topology(filters.value), observabilityApi.layout(filters.value.environment)]);
      if (requestId !== generation || identity !== auth.identity) return;
      if (result.status === 'rejected') throw result.reason;
      if (layout.status === 'fulfilled') { layoutData.value = layout.value; layoutError.value = ''; }
      else layoutError.value = '个人布局读取失败，暂保留当前画布；重试成功后再保存。';
      topologyData.value = { ...result.value, layout: layoutData.value?.positions || result.value.layout };
    } catch (cause) {
      if (requestId !== generation || identity !== auth.identity) return;
      if (topologyData.value) topologyData.value = { ...topologyData.value };
      error.value = cause instanceof Error ? cause.message : '服务拓扑读取失败';
    } finally { if (requestId === generation) loading.value = false; }
  }
  watch(() => auth.identity, () => { invalidate(); selectedCiCode.value = ''; }, { flush: 'sync' });
  watch(filters, invalidate, { flush: 'sync' });
  return { selectedEnvironment, timeRange, topologyMode, selectedCiCode, onlyUnhealthy, keyword, refreshInterval, topologyData, layoutData, layoutError, loading, error, filters, visible, selectedNode, load, invalidate };
});
