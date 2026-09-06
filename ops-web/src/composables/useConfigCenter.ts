import { computed, ref, watch, onBeforeUnmount } from 'vue';
import { configCenterApi, type ConfigCenterCatalog, type ConfigCenterDetail, type ConfigCenterHistory, type ConfigCenterDiff } from '@/api/configCenter';
import { useAuthStore } from '@/stores/auth';

export function useConfigCenter(ciCode: () => string) {
  const auth = useAuthStore();
  const catalog = ref<ConfigCenterCatalog>(); const detail = ref<ConfigCenterDetail>();
  const history = ref<ConfigCenterHistory>(); const diff = ref<ConfigCenterDiff>();
  const selectedId = ref(''); const loading = ref(false); const detailLoading = ref(false); const error = ref('');
  let generation = 0; let selection = 0; let diffGeneration = 0;
  const identity = () => auth.token;
  async function select(id: string) {
    selectedId.value = id; detail.value = undefined; history.value = undefined; diff.value = undefined; diffGeneration++;
    const turn = ++selection, token = identity(); detailLoading.value = true; error.value = '';
    const results = await Promise.allSettled([configCenterApi.detail(id), configCenterApi.history(id)]);
    if (turn !== selection || token !== identity()) return;
    if (results[0].status === 'fulfilled' && results[0].value.item.id === id) detail.value = results[0].value;
    else if (results[0].status === 'fulfilled') error.value = '返回内容的完整配置身份不匹配，已拒绝显示。';
    else error.value = results[0].reason instanceof Error ? results[0].reason.message : '配置正文读取失败';
    if (results[1].status === 'fulfilled') history.value = results[1].value;
    else history.value = { status: 'UNAVAILABLE', items: [], message: '版本记录暂不可读' };
    detailLoading.value = false;
  }
  async function load() {
    const turn = ++generation, token = identity(); loading.value = true; error.value = '';
    selection++; diffGeneration++; detail.value = undefined; history.value = undefined; diff.value = undefined;
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
  function dispose() { generation++; selection++; diffGeneration++; catalog.value = undefined; detail.value = undefined; history.value = undefined; diff.value = undefined; loading.value = false; detailLoading.value = false; }
  watch([ciCode, identity], () => { dispose(); void load(); }, { flush: 'sync' });
  onBeforeUnmount(dispose);
  return { catalog, detail, history, diff, selectedId, loading, detailLoading, error, select, load, compare };
}
