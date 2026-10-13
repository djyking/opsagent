import { ref, watch } from 'vue';
import { eventWorkspaceApi, type EventWorkspace } from '@/api/event-workspace';

/** Discard late responses across both ticket and account changes; failed reads never retain actionable evidence. */
export function useEventWorkspace(ticketId: () => number, identity: () => string) {
  const data = ref<EventWorkspace>();
  const loading = ref(false);
  const error = ref('');
  let generation = 0;
  let disposed = false;
  let current: Promise<void> | undefined;
  function reset() { generation++; current = undefined; data.value = undefined; error.value = ''; loading.value = false; }
  async function load() {
    if (disposed) return;
    if (current) return current;
    const id = ticketId();
    if (!Number.isSafeInteger(id) || id < 1 || !identity()) { reset(); return; }
    const epoch = generation;
    loading.value = true;
    const task = (async () => {
      try {
        const result = await eventWorkspaceApi.read(id);
        if (disposed || generation !== epoch) return;
        if (result.ticketId !== id || result.schemaVersion !== 1) throw new Error('事件证据与当前页面不匹配，请重新读取。');
        data.value = result; error.value = '';
      } catch (cause) {
        if (!disposed && generation === epoch) {
          data.value = undefined;
          error.value = cause instanceof Error ? cause.message : '事件证据暂时无法读取';
        }
      } finally {
        if (!disposed && generation === epoch) { loading.value = false; current = undefined; }
      }
    })();
    current = task;
    return task;
  }
  const stop = watch([ticketId, identity], () => { reset(); void load(); }, { flush: 'sync' });
  function dispose() { disposed = true; reset(); stop(); }
  return { data, loading, error, load, dispose };
}
