import { ref, watch, onBeforeUnmount } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { trafficGovernanceApi, type TrafficWorkspace, type TrafficChange, type TrafficRule, type TrafficRuleSet } from '@/api/trafficGovernance';

export function useTrafficGovernance(ciCode: () => string) {
  const auth = useAuthStore();
  const workspace = ref<TrafficWorkspace>(); const history = ref<TrafficChange[]>([]);
  const loading = ref(false); const busy = ref(false); const error = ref(''); const notice = ref('');
  const selectedType = ref('FLOW');
  const plan = ref<{ type: string; before: TrafficRule[]; after: TrafficRule[]; revision: string; requestId: string; rollbackVersionId?: number }>();
  let generation = 0; let historyGeneration = 0; let actionGeneration = 0;
  const identity = () => auth.identity;
  async function load() {
    const turn = ++generation, token = identity(); loading.value = true; error.value = '';
    try {
      const data = await trafficGovernanceApi.workspace(ciCode() || undefined);
      if (turn !== generation || token !== identity()) return;
      workspace.value = data;
    } catch (cause) {
      if (turn !== generation || token !== identity()) return;
      workspace.value = undefined; error.value = cause instanceof Error ? cause.message : '流量运行态读取失败';
    } finally { if (turn === generation) loading.value = false; }
  }
  async function loadHistory() {
    const turn = ++historyGeneration, token = identity(), type = selectedType.value; history.value = [];
    if (!['FLOW', 'DEGRADE', 'SYSTEM'].includes(type)) return;
    try { const data = await trafficGovernanceApi.history(type); if (turn === historyGeneration && token === identity()) history.value = data.items; }
    catch (cause) { if (turn === historyGeneration) error.value = cause instanceof Error ? cause.message : '审计记录读取失败'; }
  }
  async function prepare(set: TrafficRuleSet, rules: TrafficRule[], rollbackVersionId?: number) {
    if (!auth.isAdmin || busy.value || !set.editable) return;
    const turn = ++actionGeneration, token = identity(); busy.value = true; error.value = ''; plan.value = undefined;
    try {
      const result = await trafficGovernanceApi.validate(set.type, rules);
      if (turn !== actionGeneration || token !== identity()) return;
      plan.value = { type: set.type, before: JSON.parse(JSON.stringify(set.persistedRules)), after: result.rules,
        revision: set.revision, requestId: crypto.randomUUID(), rollbackVersionId };
    } catch (cause) { if (turn === actionGeneration) error.value = cause instanceof Error ? cause.message : '规则校验失败'; }
    finally { if (turn === actionGeneration) busy.value = false; }
  }
  async function confirm(comment: string) {
    const current = plan.value;
    if (!current || !auth.isAdmin || busy.value || !comment.trim()) return;
    const turn = ++actionGeneration, token = identity(); busy.value = true; error.value = '';
    try {
      const data = { expectedRevision: current.revision, requestId: current.requestId, comment: comment.trim() };
      const result = current.rollbackVersionId == null
        ? await trafficGovernanceApi.publish(current.type, { ...data, rules: current.after })
        : await trafficGovernanceApi.rollback(current.type, { ...data, versionId: current.rollbackVersionId });
      if (turn !== actionGeneration || token !== identity()) return;
      notice.value = result.operation.message; plan.value = undefined;
      await Promise.all([load(), loadHistory()]);
    } catch (cause) {
      if (turn === actionGeneration) error.value = cause instanceof Error ? cause.message : '发布结果尚未确认，请刷新核对';
      // Keep the exact request ID for retries: never manufacture a second publication after a network error.
    } finally { if (turn === actionGeneration) busy.value = false; }
  }
  function reset() { generation++; historyGeneration++; actionGeneration++; workspace.value = undefined; history.value = []; plan.value = undefined; busy.value = false; loading.value = false; error.value = ''; notice.value = ''; }
  watch([ciCode, identity, () => auth.isAdmin], () => { reset(); void load(); }, { flush: 'sync' });
  watch(selectedType, () => { actionGeneration++; busy.value = false; plan.value = undefined; void loadHistory(); }, { flush: 'sync' });
  onBeforeUnmount(reset);
  return { workspace, history, loading, busy, error, notice, selectedType, plan, load, loadHistory, prepare, confirm };
}
