import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { conversationApi, type Conversation, type ConversationTurn } from '@/api/conversations';
import { ragCompletionLabel, ragIncompleteMessage, streamRagAnswer } from '@/api/rag-stream';

export function useRagConversations() {
  const question = ref('');
  const sessions = ref<Conversation[]>([]);
  const sessionId = ref('');
  const turns = ref<ConversationTurn[]>([]);
  const listPage = ref(1);
  const total = ref(0);
  const hasEarlier = ref(false);
  const loading = ref(false);
  const busy = ref(false);
  const historyError = ref('');
  const error = ref('');
  const progress = ref('');
  const selectedTurnId = ref<number>();
  const historyOpen = ref(false);
  const contextOpen = ref(true);
  const editMode = ref<'rename' | 'delete' | ''>('');
  const editTitle = ref('');
  const actionBusy = ref(false);
  const chatScroll = ref<HTMLElement>();
  const route = useRoute();
  const router = useRouter();
  let controller: AbortController | undefined;
  let selectionVersion = 0;
  let disposed = false;
  const current = computed(() => sessions.value.find(item => item.id === sessionId.value));
  const referenceTurn = computed(() => turns.value.find(turn => turn.id === selectedTurnId.value) || turns.value.at(-1));
  const references = computed(() => referenceTurn.value?.result?.references || []);
  const message = (cause: unknown) => cause instanceof Error ? cause.message : '请求失败，请重试';

  async function loadSessions(more = false) {
    const page = more ? listPage.value + 1 : 1;
    const result = await conversationApi.list(page);
    sessions.value = more ? [...sessions.value, ...result.records] : result.records;
    total.value = result.total;
    listPage.value = page;
  }
  async function refreshHistory(more = false) {
    historyError.value = '';
    try {
      await loadSessions(more);
      if (!more && sessionId.value && !busy.value) await selectSession(sessionId.value);
    }
    catch (cause) { historyError.value = message(cause); }
  }
  async function scrollBottom() {
    await nextTick();
    if (chatScroll.value) chatScroll.value.scrollTop = chatScroll.value.scrollHeight;
  }
  async function selectSession(id: string) {
    if (busy.value) return;
    const version = ++selectionVersion;
    loading.value = true;
    error.value = '';
    historyOpen.value = false;
    try {
      const result = await conversationApi.messages(id);
      if (version !== selectionVersion || disposed) return;
      sessionId.value = id;
      turns.value = result.records;
      hasEarlier.value = result.hasMore;
      selectedTurnId.value = turns.value.at(-1)?.id;
      question.value = '';
      await router.replace({ query: { conversation: id } });
      await scrollBottom();
    } catch (cause) { if (version === selectionVersion) error.value = message(cause); }
    finally { if (version === selectionVersion) loading.value = false; }
  }
  async function earlier() {
    if (!sessionId.value || loading.value || busy.value) return;
    const id = sessionId.value;
    const version = ++selectionVersion;
    loading.value = true;
    try {
      const page = await conversationApi.messages(id, turns.value[0]?.id);
      if (id !== sessionId.value || version !== selectionVersion || disposed) return;
      turns.value = [...page.records, ...turns.value];
      hasEarlier.value = page.hasMore;
    } catch (cause) { if (version === selectionVersion) error.value = message(cause); }
    finally { if (version === selectionVersion) loading.value = false; }
  }
  async function newSession() {
    if (busy.value) return;
    selectionVersion++;
    loading.value = false;
    sessionId.value = '';
    turns.value = [];
    hasEarlier.value = false;
    selectedTurnId.value = undefined;
    question.value = '';
    error.value = '';
    historyOpen.value = false;
    await router.replace({ query: {} });
  }
  async function ask(value = question.value) {
    const submitted = value.trim();
    if (!submitted || busy.value || loading.value) return;
    busy.value = true;
    error.value = '';
    progress.value = '正在检索知识库';
    controller = new AbortController();
    let pending: ConversationTurn | undefined;
    try {
      if (!sessionId.value) {
        const session = await conversationApi.create();
        if (disposed) return;
        sessionId.value = session.id;
        sessions.value = [session, ...sessions.value];
        total.value++;
        await router.replace({ query: { conversation: session.id } });
      }
      const turn = reactive<ConversationTurn>({ id: -Date.now(), question: submitted, answer: '', status: 'PROCESSING', createTime: new Date().toISOString() });
      pending = turn;
      turns.value.push(turn);
      selectedTurnId.value = turn.id;
      question.value = '';
      await scrollBottom();
      const activeId = sessionId.value;
      const result = await streamRagAnswer({ question: submitted, topK: 5, conversationId: activeId }, {
        onStatus: value => progress.value = value,
        onToken: delta => {
          const follow = chatScroll.value && chatScroll.value.scrollHeight - chatScroll.value.scrollTop - chatScroll.value.clientHeight < 100;
          turn.answer += delta;
          if (follow) void scrollBottom();
        },
      }, controller.signal);
      turn.answer = result.answer || turn.answer;
      turn.result = result;
      turn.status = result.metadata?.generationComplete === false ? 'INCOMPLETE' : 'COMPLETE';
      turn.errorMessage = ragIncompleteMessage(result);
      try {
        const saved = await conversationApi.messages(activeId);
        if (disposed || activeId !== sessionId.value) return;
        const latest = saved.records.at(-1);
        if (latest?.question === turn.question) Object.assign(turn, latest);
        selectedTurnId.value = turn.id;
        await loadSessions();
      } catch { historyError.value = '回答已生成，历史列表刷新失败，请点击刷新重试。'; }
    } catch (cause) {
      if (disposed) return;
      error.value = message(cause);
      if (pending) { pending.status = 'INTERRUPTED'; pending.errorMessage = error.value; }
    } finally { busy.value = false; controller = undefined; }
  }
  function turnLabel(turn: ConversationTurn) {
    if (turn.status === 'PROCESSING') return busy.value ? progress.value : '生成处理中，请稍后刷新';
    if (turn.status === 'INTERRUPTED') return '回答中断';
    return turn.result ? ragCompletionLabel(turn.result) : turn.status === 'INCOMPLETE' ? '回答未完成' : '回答完成';
  }
  async function manageSession() {
    if (!sessionId.value || actionBusy.value) return;
    actionBusy.value = true;
    error.value = '';
    try {
      if (editMode.value === 'rename') {
        const session = await conversationApi.rename(sessionId.value, editTitle.value.trim());
        const index = sessions.value.findIndex(item => item.id === session.id);
        if (index >= 0) sessions.value[index] = session;
      } else {
        await conversationApi.remove(sessionId.value);
        await newSession();
        await loadSessions();
      }
      editMode.value = '';
    } catch (cause) { error.value = message(cause); }
    finally { actionBusy.value = false; }
  }
  async function copyAnswer(turn: ConversationTurn) {
    try { await navigator.clipboard.writeText(turn.answer); }
    catch { error.value = '无法复制，请选择正文手动复制。'; }
  }
  watch(() => route.query.new, value => { if (value === '1' && !busy.value) void newSession(); });
  watch(() => route.query.conversation, value => {
    const id = typeof value === 'string' ? value : '';
    if (id === sessionId.value) return;
    if (busy.value) {
      // Keep the URL aligned with the conversation receiving the active stream.
      void router.replace({ query: { conversation: sessionId.value } });
      return;
    }
    if (id) void selectSession(id);
    else void newSession();
  });
  onMounted(async () => {
    await refreshHistory();
    if (route.query.new === '1') await newSession();
    else if (typeof route.query.conversation === 'string') await selectSession(route.query.conversation);
  });
  onBeforeUnmount(() => { disposed = true; selectionVersion++; controller?.abort(); });
  return { question, sessions, sessionId, turns, total, hasEarlier, loading, busy, historyError, error, selectedTurnId, historyOpen, contextOpen, editMode, editTitle, actionBusy, chatScroll, current, referenceTurn, references, refreshHistory, selectSession, earlier, newSession, ask, turnLabel, manageSession, copyAnswer };
}
