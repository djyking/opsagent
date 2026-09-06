import { computed, ref, watch } from 'vue';
import { configurationApi, type BusinessConfiguration, type ConfigurationHistory, type ConfigurationVersion,
  type ManagedConfiguration, type ManagedConfigurationId, type ManagedConfigurationItem } from '@/api/configuration';

export function businessContent(content: Record<string, unknown>): BusinessConfiguration {
  return { catalogTitle: String(content.catalogTitle ?? ''), notice: String(content.notice ?? ''), discountPercent: Number(content.discountPercent ?? 0) };
}
export const configurationFieldLabels = { catalogTitle: '订单目录标题', notice: '业务提示', discountPercent: '演示折扣（%）' };
export function configurationChanges(before: Record<string, unknown>, after: BusinessConfiguration) {
  return (Object.keys(configurationFieldLabels) as (keyof BusinessConfiguration)[])
    .filter(key => before[key] !== after[key])
    .map(key => ({ key, label: configurationFieldLabels[key], before: String(before[key] ?? '—'), after: String(after[key]) || '（空）' }));
}
type Plan = { id: ManagedConfigurationId; expectedRevision: string; requestId: string; content: BusinessConfiguration;
  versionId?: number; versionNumber?: number; changes: ReturnType<typeof configurationChanges> };

export function useManagedConfiguration(isAdmin: () => boolean, identity: () => unknown) {
  const items = ref<ManagedConfigurationItem[]>([]);
  const selectedId = ref<ManagedConfigurationId>('order-business');
  const detail = ref<ManagedConfiguration>();
  const draft = ref<BusinessConfiguration>({ catalogTitle: '', notice: '', discountPercent: 0 });
  const history = ref<ConfigurationHistory>({ items: [], total: 0, page: 1, size: 8 });
  const loading = ref(false);
  const historyLoading = ref(false);
  const busy = ref(false);
  const error = ref('');
  const historyError = ref('');
  const notice = ref('');
  const plan = ref<Plan>();
  const comment = ref('');
  let generation = 0;
  let historyGeneration = 0;
  let directoryGeneration = 0;
  let draftGeneration = 0;
  let disposed = false;
  const dirty = computed(() => !!detail.value?.editable && configurationChanges(detail.value.content, draft.value).length > 0);
  const writable = computed(() => isAdmin() && !!detail.value?.editable && !!detail.value.canPublish && !loading.value);
  const canPublish = computed(() => writable.value && dirty.value && !busy.value);
  function current(epoch: number, actor: unknown) { return !disposed && epoch === generation && actor === identity(); }
  function resetDraft() { if (detail.value) draft.value = businessContent(detail.value.content); plan.value = undefined; }
  const stopDraftWatch = watch(draft, () => { draftGeneration++; plan.value = undefined; }, { deep: true, flush: 'sync' });
  const stopIdentityWatch = watch([identity, isAdmin], () => {
    generation++; historyGeneration++; directoryGeneration++;
    detail.value = undefined; items.value = []; plan.value = undefined; comment.value = '';
    draft.value = { catalogTitle: '', notice: '', discountPercent: 0 };
    history.value = { items: [], total: 0, page: 1, size: 8 };
    busy.value = false; loading.value = false; historyLoading.value = false;
    error.value = ''; historyError.value = ''; notice.value = '账号或权限已变化，请重新读取配置。';
    if (identity() != null) void load();
  }, { flush: 'sync' });

  async function select(id: ManagedConfigurationId) {
    if (disposed || busy.value) return;
    const epoch = ++generation; const actor = identity();
    selectedId.value = id; detail.value = undefined; plan.value = undefined; error.value = ''; notice.value = '';
    history.value = { items: [], total: 0, page: 1, size: 8 }; historyLoading.value = false; loading.value = true;
    try {
      const value = await configurationApi.detail(id);
      if (!current(epoch, actor)) return;
      detail.value = value; resetDraft();
      await loadHistory(1);
    } catch (cause) { if (current(epoch, actor)) error.value = cause instanceof Error ? cause.message : '配置读取失败'; }
    finally { if (current(epoch, actor)) loading.value = false; }
  }
  async function load() {
    if (disposed) return;
    const actor = identity(); const epoch = generation; const request = ++directoryGeneration;
    try {
      const result = await configurationApi.list();
      if (disposed || actor !== identity() || request !== directoryGeneration) return;
      items.value = result.items;
      if (epoch === generation) await select(selectedId.value);
    } catch (cause) { if (current(epoch, actor) && request === directoryGeneration) error.value = cause instanceof Error ? cause.message : '配置目录读取失败'; }
  }
  async function loadHistory(page = 1) {
    if (disposed) return;
    const epoch = generation; const actor = identity(); const request = ++historyGeneration;
    historyLoading.value = true; historyError.value = '';
    try {
      const value = await configurationApi.history(selectedId.value, page);
      if (current(epoch, actor) && request === historyGeneration) history.value = value;
    } catch (cause) { if (current(epoch, actor) && request === historyGeneration) historyError.value = cause instanceof Error ? cause.message : '版本记录读取失败'; }
    finally { if (current(epoch, actor) && request === historyGeneration) historyLoading.value = false; }
  }
  async function prepare(version?: ConfigurationVersion) {
    if (disposed || !writable.value || busy.value || !detail.value) return;
    if (version && version.status !== 'APPLIED') { error.value = '只能回退到已确认生效的版本'; return; }
    if (!version && !dirty.value) return;
    const epoch = generation; const edit = draftGeneration; const actor = identity(); const source = detail.value;
    const proposed = version ? businessContent(version.content) : { ...draft.value };
    busy.value = true; error.value = ''; notice.value = '';
    try {
      const value = await configurationApi.validate(source.id, proposed);
      if (!current(epoch, actor) || !isAdmin()) return;
      if (edit !== draftGeneration) { notice.value = '编辑内容已变化，请重新校验。'; return; }
      if (!value.valid) throw new Error('配置校验未通过');
      const changes = configurationChanges(source.content, value.normalizedContent);
      if (!changes.length) { notice.value = '该内容与当前配置一致，无需发布。'; return; }
      plan.value = { id: source.id, expectedRevision: source.revision, requestId: crypto.randomUUID(),
        content: value.normalizedContent, versionId: version?.id, versionNumber: version?.version, changes };
      comment.value = '';
    } catch (cause) { if (current(epoch, actor)) error.value = cause instanceof Error ? cause.message : '配置校验失败'; }
    finally { if (current(epoch, actor)) busy.value = false; }
  }
  async function confirm() {
    const request = plan.value; const reason = comment.value.trim();
    if (disposed || !request || request.id !== selectedId.value || request.id !== detail.value?.id
      || !writable.value || busy.value || !reason || reason.length > 500) return;
    const epoch = generation; const edit = draftGeneration; const actor = identity();
    busy.value = true; error.value = '';
    try {
      const latest = await configurationApi.detail(request.id);
      if (!current(epoch, actor) || !isAdmin() || plan.value !== request) return;
      if (latest.id !== request.id || !latest.editable || !latest.canPublish || latest.revision !== request.expectedRevision) {
        if (latest.id === request.id) detail.value = latest;
        plan.value = undefined;
        error.value = latest.blockedReason || '配置已更新，编辑内容已保留。请重新核对差异后再提交';
        return;
      }
      const base = { expectedRevision: request.expectedRevision, requestId: request.requestId, comment: reason };
      const result = request.versionId == null
        ? await configurationApi.publish(request.id, { ...base, content: request.content })
        : await configurationApi.rollback(request.id, { ...base, versionId: request.versionId });
      if (!current(epoch, actor)) return;
      detail.value = result.configuration;
      if (result.operation.status !== 'APPLIED') detail.value = { ...detail.value, canPublish: false,
        blockedReason: '上次发布结果尚未确认，请刷新配置与版本记录后继续。' };
      if (edit === draftGeneration) resetDraft(); else plan.value = undefined;
      notice.value = result.operation.status === 'APPLIED' ? '配置已发布，目标服务已确认应用。'
        : result.operation.message || '发布请求已记录，尚未确认目标服务应用。请刷新并核对版本状态。';
      await loadHistory(1);
    } catch (cause) {
      if (current(epoch, actor)) {
        error.value = cause instanceof Error ? cause.message : '发布状态未能确认，请刷新检查';
        plan.value = undefined;
        // A failed response may follow a successful remote write. Require a fresh read before another edit.
        if (detail.value) detail.value = { ...detail.value, canPublish: false, blockedReason: '请刷新最新配置与版本记录，核对上次请求结果后继续。' };
        await loadHistory(1);
      }
    } finally { if (current(epoch, actor)) busy.value = false; }
  }
  function dispose() { disposed = true; generation++; historyGeneration++; directoryGeneration++;
    stopDraftWatch(); stopIdentityWatch(); plan.value = undefined; }
  return { items, selectedId, detail, draft, history, loading, historyLoading, busy, error, historyError, notice,
    plan, comment, dirty, writable, canPublish, select, load, loadHistory, resetDraft, prepare, confirm, dispose };
}
