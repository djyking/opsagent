import { ref, watch, onBeforeUnmount } from 'vue';
import { configCenterApi, type ConfigCenterCatalog, type ConfigCenterDetail, type ConfigCenterHistory, type ConfigCenterDiff } from '@/api/configCenter';
import { useAuthStore } from '@/stores/auth';

export function useConfigCenter(ciCode: () => string) {
  const auth = useAuthStore();
  const catalog = ref<ConfigCenterCatalog>(); const detail = ref<ConfigCenterDetail>();
  const history = ref<ConfigCenterHistory>(); const diff = ref<ConfigCenterDiff>();
  const selectedId = ref(''); const loading = ref(false); const detailLoading = ref(false); const error = ref('');
  const historyLoading = ref(false);
  let generation = 0; let selection = 0; let diffGeneration = 0; let historyGeneration = 0;
  const identity = () => auth.identity;
  async function select(id: string) {
    selectedId.value = id; detail.value = undefined; history.value = undefined; diff.value = undefined; diffGeneration++; historyGeneration++; historyLoading.value = false;
    const turn = ++selection, token = identity(); detailLoading.value = true; error.value = '';
    try {
      const result = await configCenterApi.detail(id);
      if (turn === selection && token === identity()) detail.value = result;
    } catch (cause) {
      if (turn === selection && token === identity()) error.value = cause instanceof Error ? cause.message : '配置正文读取失败';
    } finally { if (turn === selection && token === identity()) detailLoading.value = false; }
  }
  async function loadHistory() {
    if (!selectedId.value || historyLoading.value || history.value?.status === 'AVAILABLE') return;
    const turn = ++historyGeneration, id = selectedId.value, token = identity(); historyLoading.value = true;
    try {
      const result = await configCenterApi.history(id);
      if (turn === historyGeneration && id === selectedId.value && token === identity()) history.value = result;
    } catch {
      if (turn === historyGeneration && id === selectedId.value && token === identity()) history.value = { status: 'UNAVAILABLE', items: [], message: '版本记录暂不可读，请重试。' };
    } finally { if (turn === historyGeneration && token === identity()) historyLoading.value = false; }
  }
  async function load() {
    const turn = ++generation, token = identity(); loading.value = true; error.value = '';
    selection++; diffGeneration++; historyGeneration++; historyLoading.value = false; detailLoading.value = false; detail.value = undefined; history.value = undefined; diff.value = undefined;
    try {
      const data = await configCenterApi.list(ciCode() || undefined);
      if (turn !== generation || token !== identity()) return;
      catalog.value = data;
      const id = data.items.find(i => i.id === selectedId.value)?.id || data.items[0]?.id;
      selectedId.value = id || '';
      if (id) await select(id);
    } catch (cause) {
      if (turn !== generation || token !== identity()) return;
      catalog.value = undefined; error.value = cause instanceof Error ? cause.message : '配置目录读取失败';
    } finally { if (turn === generation) loading.value = false; }
  }
  async function compare(versionId: number) {
    const turn = ++diffGeneration, id = selectedId.value, token = identity(); diff.value = undefined;
    try {
      const data = await configCenterApi.diff(id, versionId);
      if (turn === diffGeneration && id === selectedId.value && token === identity()) diff.value = data;
    } catch (cause) { if (turn === diffGeneration) error.value = cause instanceof Error ? cause.message : '版本差异读取失败'; }
  }
  function dispose() { generation++; selection++; diffGeneration++; historyGeneration++; catalog.value = undefined; detail.value = undefined; history.value = undefined; diff.value = undefined; loading.value = false; detailLoading.value = false; historyLoading.value = false; }
  watch([ciCode, identity], () => { dispose(); void load(); }, { flush: 'sync' });
  onBeforeUnmount(dispose);
  return { catalog, detail, history, diff, selectedId, loading, detailLoading, historyLoading, error, select, load, compare, loadHistory };
}
