import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { automationApi, type PendingApproval } from '@/api/automation';
import { approvalActionable, approvalKey } from '@/utils/automation-presentation';
import { useAuthStore } from '@/stores/auth';

export const useApprovalInboxStore = defineStore('approval-inbox', () => {
  const auth = useAuthStore();
  const items = ref<PendingApproval[]>([]);
  const selected = ref<PendingApproval>();
  const open = ref(false);
  const loading = ref(false);
  const error = ref('');
  const notice = ref('');
  const busy = ref('');
  const now = ref(Date.now());
  const decisionVersion = ref(0);
  const availableItems = computed(() => items.value.filter(item => approvalActionable(item, auth.user?.userId, auth.isAdmin, now.value)));
  const count = computed(() => availableItems.value.length);
  let seen = new Set<string>();
  let generation = 0;
  let active = false;
  let lastPoll = 0;
  let timer: ReturnType<typeof setInterval> | undefined;
  let currentRefresh: Promise<boolean> | undefined;
  function storageKey() { return `opsagent-approval-seen:${auth.user?.userId ?? 'none'}`; }
  function persistSeen() {
    seen = new Set([...seen].slice(-200));
    try { sessionStorage.setItem(storageKey(), JSON.stringify([...seen])); } catch { /* Reminders still deduplicate in memory. */ }
  }
  function loadSeen() {
    try {
      const values: unknown = JSON.parse(sessionStorage.getItem(storageKey()) || '[]');
      seen = new Set(Array.isArray(values) ? values.filter(value => typeof value === 'string').slice(-200) : []);
    } catch { seen = new Set(); }
  }
  function select(approval: PendingApproval) {
    selected.value = JSON.parse(JSON.stringify(approval)) as PendingApproval;
    seen.add(approvalKey(approval)); persistSeen(); open.value = true;
  }
  function showNew() {
    if (open.value || busy.value || document.hidden || document.querySelector('[role="dialog"][aria-modal="true"]')) return;
    const next = availableItems.value.find(item => !seen.has(approvalKey(item)));
    if (next) select(next);
  }
  function isCurrent(approval: PendingApproval) {
    return approvalActionable(approval, auth.user?.userId, auth.isAdmin, now.value)
      && availableItems.value.some(item => approvalKey(item) === approvalKey(approval));
  }
  async function refresh(allowPopup = true): Promise<boolean> {
    if (!auth.user || !auth.isAuthenticated) return false;
    if (currentRefresh) return currentRefresh;
    const epoch = generation;
    loading.value = true;
    const task = (async () => {
      try {
        const result = await automationApi.pendingApprovals();
        if (epoch !== generation) return false;
        items.value = result.items; now.value = Date.now(); error.value = '';
        if (allowPopup) showNew();
        return true;
      } catch (cause) {
        if (epoch === generation) error.value = cause instanceof Error ? cause.message : '待审批数据暂时未能更新';
        return false;
      } finally {
        if (epoch === generation) { loading.value = false; currentRefresh = undefined; }
      }
    })();
    currentRefresh = task;
    return task;
  }
  function show() {
    open.value = true; notice.value = '';
    if (!selected.value || !isCurrent(selected.value)) {
      selected.value = undefined;
      if (availableItems.value[0]) select(availableItems.value[0]);
    }
    void refresh(false).then(ok => {
      if (ok && open.value && !selected.value && availableItems.value[0]) select(availableItems.value[0]);
    });
  }
  function later() {
    availableItems.value.forEach(item => seen.add(approvalKey(item))); persistSeen();
    open.value = false;
  }
  function reset() {
    generation++; currentRefresh = undefined;
    items.value = []; selected.value = undefined; open.value = false; loading.value = false;
    error.value = ''; notice.value = ''; busy.value = ''; seen = new Set();
  }
  function start() {
    if (active) return;
    active = true; lastPoll = Date.now(); loadSeen(); void refresh();
    timer = setInterval(() => {
      now.value = Date.now();
      if (!document.hidden && now.value - lastPoll >= 4000) { lastPoll = now.value; void refresh(); }
    }, 1000);
  }
  function stop() { active = false; if (timer) clearInterval(timer); timer = undefined; reset(); }
  watch(() => auth.user?.userId, () => { reset(); loadSeen(); if (active) void refresh(); });

  async function decide(approval: PendingApproval, approved: boolean, reason: string) {
    if (busy.value) throw new Error('另一项审批正在提交，请稍候');
    const explanation = reason.trim();
    if (!explanation || explanation.length > 500) throw new Error('请填写 1 至 500 字的审批说明');
    const epoch = generation;
    busy.value = approval.id; notice.value = '';
    try {
      if (!await refresh(false)) throw new Error('未能核对最新审批状态，请刷新后重试');
      if (epoch !== generation || !isCurrent(approval)) throw new Error('审批已过期、暂停或发生变化，请查看最新运行状态');
      await automationApi.decide(approval, approved, explanation);
      if (epoch !== generation) return;
      items.value = items.value.filter(item => item.id !== approval.id);
      seen.add(approvalKey(approval)); persistSeen(); decisionVersion.value++;
      notice.value = approved ? (approval.payload.name === 'HUMAN_INPUT' ? '补充信息已提交，工作流将继续。' : '审批已通过，执行结果请查看运行记录。') : '拒绝决定已提交。';
      if (selected.value?.id === approval.id) selected.value = undefined;
      await refresh(false);
    } catch (cause) {
      if (epoch === generation) await refresh(false);
      throw cause;
    } finally { if (epoch === generation) busy.value = ''; }
  }
  return { items, availableItems, selected, open, loading, error, notice, busy, now, decisionVersion, count,
    isCurrent, select, show, later, refresh, start, stop, decide };
});
