import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { observabilityV3Api, type TopologyV3Snapshot } from '@/api/observabilityV3';
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
  const topologyData = ref<TopologyV3Snapshot>();
  const loading = ref(false);
  const error = ref('');
  let generation = 0;
  const filters = computed(() => ({ environment: selectedEnvironment.value, timeRange: timeRange.value, mode: topologyMode.value }));
  const visible = computed(() => filterTopology(topologyData.value?.nodes || [], topologyData.value?.edges || [], keyword.value, onlyUnhealthy.value));
  const selectedNode = computed(() => topologyData.value?.nodes.find(node => node.ciCode === selectedCiCode.value));
  function invalidate() { generation++; topologyData.value = undefined; error.value = ''; loading.value = false; }
  async function load() {
    const requestId = ++generation;
    const identity = auth.token;
    loading.value = true; error.value = '';
    try {
      const data = await observabilityV3Api.topology(filters.value);
      if (requestId !== generation || identity !== auth.token) return;
      topologyData.value = data;
    } catch (cause) {
      if (requestId !== generation || identity !== auth.token) return;
      topologyData.value = undefined;
      error.value = cause instanceof Error ? cause.message : '服务拓扑读取失败';
    } finally { if (requestId === generation) loading.value = false; }
  }
  watch(() => auth.token, () => { invalidate(); selectedCiCode.value = ''; }, { flush: 'sync' });
  watch(filters, invalidate, { flush: 'sync' });
  return { selectedEnvironment, timeRange, topologyMode, selectedCiCode, onlyUnhealthy, keyword, refreshInterval, topologyData, loading, error, filters, visible, selectedNode, load, invalidate };
});
