import { computed, nextTick, reactive, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { useAuthStore } from '@/stores/auth';
import { conversationApi, ragProviderApi, type AiProvider, type ProviderOption, type Conversation, type ConversationTurn } from '@/api/conversations';
import { ragCompletionLabel, ragIncompleteMessage, streamRagAnswer } from '@/api/rag-stream';
import { cleanAiContext, contextualQuestion, contextLabel, serverObservabilityContext, type AiContext } from '@/utils/ai-context';

export const useAiAssistantStore = defineStore('ai-assistant', () => {
  const auth = useAuthStore();
  const open = ref(false);
  const minimized = ref(false);
  const context = ref<AiContext>({});
  const question = ref('');
  const sessions = ref<Conversation[]>([]);
  const sessionId = ref('');
  const turns = ref<ConversationTurn[]>([]);
  const listPage = ref(1), total = ref(0), hasEarlier = ref(false);
  const loading = ref(false), busy = ref(false), historyError = ref(''), error = ref(''), progress = ref('');
  const selectedTurnId = ref<number>();
  const historyOpen = ref(false), contextOpen = ref(true);
  const editMode = ref<'rename' | 'delete' | ''>(''), editTitle = ref(''), actionBusy = ref(false);
  const chatScroll = ref<HTMLElement>(), questionInput = ref<HTMLTextAreaElement>();
  const draftImported = ref(false);
  const providers = ref<ProviderOption[]>([]), selectedProvider = ref<AiProvider | ''>('');
  const providersLoading = ref(false), providersReady = ref(false), providerError = ref('');
  const current = computed(() => sessions.value.find(item => item.id === sessionId.value));
  const referenceTurn = computed(() => turns.value.find(turn => turn.id === selectedTurnId.value) || turns.value.at(-1));
  const references = computed(() => referenceTurn.value?.result?.references || []);
  const contextSummary = computed(() => contextLabel(context.value));
  let controller: AbortController | undefined;
  let identityVersion = 0, selectionVersion = 0, historyVersion = 0;
  let initialized = false;
  const message = (cause: unknown) => cause instanceof Error ? cause.message : '请求失败，请重试';
  const valid = (identity: number) => identity === identityVersion;

  function reset() {
    identityVersion++; selectionVersion++; historyVersion++; controller?.abort(); controller = undefined; initialized = false;
    open.value = false; minimized.value = false; context.value = {}; question.value = ''; sessions.value = []; sessionId.value = ''; turns.value = [];
    total.value = 0; listPage.value = 1; hasEarlier.value = false; selectedTurnId.value = undefined;
    loading.value = false; busy.value = false; actionBusy.value = false; providersLoading.value = false; providersReady.value = false;
    historyError.value = ''; error.value = ''; providerError.value = ''; progress.value = ''; providers.value = []; selectedProvider.value = '';
    historyOpen.value = false; contextOpen.value = true; draftImported.value = false; editMode.value = ''; editTitle.value = '';
  }
  watch(() => [auth.isAuthenticated, auth.user?.userId], reset, { flush: 'sync' });
  function setContext(value: AiContext) { context.value = cleanAiContext(value); }
  function show(value?: AiContext) {
    if (value) setContext({ ...context.value, ...value });
    open.value = true; minimized.value = false; initialize();
    void nextTick(() => questionInput.value?.focus());
  }
  function hide() { open.value = false; }
  function initialize() {
    if (initialized) return;
    initialized = true; void refreshHistory(); void loadProviders();
  }
  async function loadProviders() {
    if (providersLoading.value) return;
    const identity = identityVersion;
    providersLoading.value = true; providerError.value = '';
    try {
      const result = await ragProviderApi.list(); if (!valid(identity)) return;
      providers.value = result.providers;
      const available = result.providers.filter(item => item.available);
      if (!available.some(item => item.provider === selectedProvider.value)) {
        const previous = [...turns.value].reverse().find(turn => available.some(item => item.provider === turn.result?.provider))?.result?.provider;
        selectedProvider.value = available.find(item => item.provider === previous)?.provider
          || available.find(item => item.provider === result.defaultProvider)?.provider || available[0]?.provider || '';
      }
      providersReady.value = true;
    } catch (cause) { if (valid(identity)) providerError.value = message(cause); }
    finally { if (valid(identity)) providersLoading.value = false; }
  }
  async function loadSessions(more = false) {
    const identity = identityVersion, version = ++historyVersion;
    const page = more ? listPage.value + 1 : 1;
    const result = await conversationApi.list(page);
    if (!valid(identity) || version !== historyVersion) return;
    sessions.value = more ? [...sessions.value, ...result.records.filter(item => !sessions.value.some(current => current.id === item.id))] : result.records;
    total.value = result.total; listPage.value = page;
  }
  async function refreshHistory(more = false) {
    const identity = identityVersion, id = sessionId.value, version = selectionVersion;
    historyError.value = '';
    try { await loadSessions(more); if (valid(identity) && !more && id && id === sessionId.value && version === selectionVersion && !busy.value && !loading.value) await selectSession(id); }
    catch (cause) { if (valid(identity)) historyError.value = message(cause); }
  }
  async function scrollBottom() { await nextTick(); if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight; }
  async function selectSession(id: string) {
    if (busy.value) return;
    const identity = identityVersion, version = ++selectionVersion;
    loading.value = true; error.value = ''; historyOpen.value = false;
    try {
      const result = await conversationApi.messages(id); if (!valid(identity) || version !== selectionVersion) return;
      sessionId.value = id; turns.value = result.records; hasEarlier.value = result.hasMore; selectedTurnId.value = turns.value.at(-1)?.id;
      const previous = [...turns.value].reverse().find(turn => providers.value.some(item => item.available && item.provider === turn.result?.provider))?.result?.provider;
      if (previous) selectedProvider.value = previous as AiProvider;
      question.value = ''; draftImported.value = false; await scrollBottom();
    } catch (cause) { if (valid(identity) && version === selectionVersion) error.value = message(cause); }
    finally { if (valid(identity) && version === selectionVersion) loading.value = false; }
  }
  async function earlier() {
    if (!sessionId.value || loading.value || busy.value) return;
    const identity = identityVersion, id = sessionId.value, version = ++selectionVersion;
    loading.value = true;
    try {
      const page = await conversationApi.messages(id, turns.value[0]?.id);
      if (!valid(identity) || id !== sessionId.value || version !== selectionVersion) return;
      turns.value = [...page.records, ...turns.value]; hasEarlier.value = page.hasMore;
    } catch (cause) { if (valid(identity) && version === selectionVersion) error.value = message(cause); }
    finally { if (valid(identity) && version === selectionVersion) loading.value = false; }
  }
  async function startDraft(draft = '') {
    if (busy.value) return;
    selectionVersion++; loading.value = false; sessionId.value = ''; turns.value = []; hasEarlier.value = false;
    selectedTurnId.value = undefined; question.value = draft.slice(0, 2000); draftImported.value = !!draft; error.value = ''; historyOpen.value = false;
    await nextTick(); questionInput.value?.focus();
  }
  async function newSession() { await startDraft(); }
  async function ask(value = question.value) {
    if (!value.trim() || busy.value || loading.value || !providersReady.value || providersLoading.value) return;
    const identity = identityVersion;
    const requestContext = { ...context.value };
    busy.value = true; error.value = ''; progress.value = '正在读取本次问题需要的数据';
    const requestController = new AbortController(); controller = requestController;
    let pending: ConversationTurn | undefined;
    try {
      if (!valid(identity) || requestController.signal.aborted) return;
      const submitted = contextualQuestion(value, requestContext);
      if (!sessionId.value) {
        const session = await conversationApi.create(); if (!valid(identity)) return;
        sessionId.value = session.id; sessions.value = [session, ...sessions.value]; total.value++;
      }
      const activeId = sessionId.value;
      const turn = reactive<ConversationTurn>({ id: -Date.now(), question: submitted, answer: '', status: 'PROCESSING', createTime: new Date().toISOString() });
      pending = turn; turns.value.push(turn); selectedTurnId.value = turn.id; question.value = ''; draftImported.value = false;
      await scrollBottom(); if (!valid(identity)) return;
      const result = await streamRagAnswer({ question: submitted, topK: 5, conversationId: activeId, provider: selectedProvider.value || undefined,
        observabilityContext: serverObservabilityContext(requestContext, value),
        ...(requestContext.ticketId ? { ticketId: requestContext.ticketId } : {}) }, {
        onStatus: value => { if (valid(identity)) progress.value = value; },
        onToken: delta => {
          if (!valid(identity)) return;
          const follow = chatScroll.value && chatScroll.value.scrollHeight - chatScroll.value.scrollTop - chatScroll.value.clientHeight < 100;
          turn.answer += delta; if (follow) void scrollBottom();
        },
      }, requestController.signal);
      if (!valid(identity)) return;
      turn.answer = result.answer || turn.answer; turn.result = result;
      turn.status = result.metadata?.generationComplete === false ? 'INCOMPLETE' : 'COMPLETE'; turn.errorMessage = ragIncompleteMessage(result);
      try {
        const saved = await conversationApi.messages(activeId); if (!valid(identity) || activeId !== sessionId.value) return;
        const latest = saved.records.at(-1); if (latest?.question === turn.question) Object.assign(turn, latest);
        selectedTurnId.value = turn.id; await loadSessions();
      } catch { if (valid(identity)) historyError.value = '回答已生成，历史列表刷新失败，请点击刷新重试。'; }
    } catch (cause) {
      if (!valid(identity)) return;
      error.value = message(cause); if (pending) { pending.status = 'INTERRUPTED'; pending.errorMessage = error.value; }
    } finally { if (valid(identity)) { busy.value = false; controller = undefined; } }
  }
  function turnLabel(turn: ConversationTurn) {
    if (turn.status === 'PROCESSING') return busy.value ? progress.value : '生成处理中，请稍后刷新';
    if (turn.status === 'INTERRUPTED') return '回答中断';
    return turn.result ? ragCompletionLabel(turn.result) : turn.status === 'INCOMPLETE' ? '回答未完成' : '回答完成';
  }
  async function manageSession() {
    if (!sessionId.value || actionBusy.value || busy.value) return;
    const identity = identityVersion, id = sessionId.value; actionBusy.value = true; error.value = '';
    try {
      if (editMode.value === 'rename') {
        const session = await conversationApi.rename(id, editTitle.value.trim()); if (!valid(identity)) return;
        const index = sessions.value.findIndex(item => item.id === session.id); if (index >= 0) sessions.value[index] = session;
      } else if (editMode.value === 'delete') {
        await conversationApi.remove(id); if (!valid(identity)) return;
        await newSession(); await loadSessions();
      }
      if (valid(identity)) editMode.value = '';
    } catch (cause) { if (valid(identity)) error.value = message(cause); }
    finally { if (valid(identity)) actionBusy.value = false; }
  }
  async function copyAnswer(turn: ConversationTurn) {
    try { await navigator.clipboard.writeText(turn.answer); }
    catch { error.value = '无法复制，请选择正文手动复制。'; }
  }
  return { open, minimized, context, contextSummary, show, hide, setContext, initialize, reset,
    question, questionInput, draftImported, providers, selectedProvider, providersLoading, providersReady, providerError, loadProviders,
    sessions, sessionId, turns, total, hasEarlier, loading, busy, historyError, error, selectedTurnId, historyOpen, contextOpen,
    editMode, editTitle, actionBusy, chatScroll, current, referenceTurn, references, refreshHistory, selectSession, earlier,
    startDraft, newSession, ask, turnLabel, manageSession, copyAnswer };
});
