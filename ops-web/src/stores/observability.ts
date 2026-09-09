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
  const observationClock = ref(Date.now());
  const topologyData = ref<TopologySnapshot>();
  const layoutData = ref<LayoutSnapshot>();
  const layoutError = ref('');
  const loading = ref(false);
  const error = ref('');
  const layoutCache = new Map<string, LayoutSnapshot>();
  let generation = 0;
  let layoutGeneration = 0;
  let currentRead: { key: string; controller: AbortController; promise: Promise<void> } | undefined;
  let currentLayout: { key: string; controller: AbortController; promise: Promise<void> } | undefined;
  let layoutRetryAt = 0;
  const filters = computed(() => ({ environment: selectedEnvironment.value, timeRange: timeRange.value, mode: topologyMode.value }));
  const visible = computed(() => filterTopology(topologyData.value?.nodes || [], topologyData.value?.edges || [], keyword.value, onlyUnhealthy.value, observationClock.value));
  const selectedNode = computed(() => topologyData.value?.nodes.find(node => node.ciCode === selectedCiCode.value));
  const layoutKey = () => `${auth.identity}:${selectedEnvironment.value}`;
  function invalidate() {
    generation++; layoutGeneration++;
    currentRead?.controller.abort(); currentLayout?.controller.abort();
    currentRead = undefined; currentLayout = undefined; layoutRetryAt = 0;
    topologyData.value = undefined; layoutData.value = undefined; layoutError.value = ''; error.value = ''; loading.value = false;
  }
  function loadLayout(force = false): Promise<void> {
    const key = layoutKey(), identity = auth.identity, environment = selectedEnvironment.value;
    if (force) { layoutCache.delete(key); currentLayout?.controller.abort(); currentLayout = undefined; layoutRetryAt = 0; }
    const cached = layoutCache.get(key);
    if (cached) { layoutData.value = cached; return Promise.resolve(); }
    if (currentLayout?.key === key) return currentLayout.promise;
    if (!force && Date.now() < layoutRetryAt) return Promise.resolve();
    currentLayout?.controller.abort();
    const controller = new AbortController(), epoch = ++layoutGeneration;
    const promise = (async () => {
      try {
        const value = await observabilityApi.layout(environment, { signal: controller.signal, expectedIdentity: identity ?? undefined });
        if (controller.signal.aborted || epoch !== layoutGeneration || key !== layoutKey()) return;
        layoutCache.set(key, value); layoutData.value = value; layoutError.value = ''; layoutRetryAt = 0;
        if (topologyData.value) topologyData.value = { ...topologyData.value, layout: value.positions };
      } catch {
        if (!controller.signal.aborted && epoch === layoutGeneration && key === layoutKey()) {
          layoutError.value = '个人布局读取失败，当前服务观测仍可查看；稍后重试或手动刷新布局。';
          layoutRetryAt = Date.now() + 60_000;
        }
      } finally { if (currentLayout?.controller === controller) currentLayout = undefined; }
    })();
    currentLayout = { key, controller, promise };
    return promise;
  }
  function refreshLayout() { return loadLayout(true); }
  function load(): Promise<void> {
    const identity = auth.identity, scope = { ...filters.value };
    const key = JSON.stringify([identity, scope]);
    if (currentRead?.key === key) return currentRead.promise;
    currentRead?.controller.abort();
    const controller = new AbortController(), requestId = ++generation;
    loading.value = true;
    // Layout is stable by identity/environment and never blocks fresh service observations.
    void loadLayout();
    const promise = (async () => {
      try {
        const result = await observabilityApi.topology(scope, { signal: controller.signal, expectedIdentity: identity ?? undefined });
        if (controller.signal.aborted || requestId !== generation || identity !== auth.identity) return;
        topologyData.value = { ...result, layout: layoutData.value?.positions || result.layout };
        observationClock.value = Date.now(); error.value = '';
      } catch (cause) {
        if (controller.signal.aborted || requestId !== generation || identity !== auth.identity) return;
        observationClock.value = Date.now();
        error.value = cause instanceof Error ? cause.message : '服务拓扑读取失败';
      } finally {
        if (requestId === generation) loading.value = false;
        if (currentRead?.controller === controller) currentRead = undefined;
      }
    })();
    currentRead = { key, controller, promise };
    return promise;
  }
  watch(() => auth.identity, () => { layoutCache.clear(); invalidate(); selectedCiCode.value = ''; }, { flush: 'sync' });
  watch(filters, invalidate, { flush: 'sync' });
  return { selectedEnvironment, timeRange, topologyMode, selectedCiCode, onlyUnhealthy, keyword, refreshInterval, observationClock,
    topologyData, layoutData, layoutError, loading, error, filters, visible, selectedNode, load, refreshLayout, invalidate };
});
