import { onBeforeUnmount, onMounted, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { useRoute, useRouter } from 'vue-router';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { ragNavigation, type RagNavigation } from '@/utils/rag-navigation';

/** Full-page presentation of the same conversation used by the global assistant dock. */
export function useRagConversations() {
  const assistant = useAiAssistantStore();
  const state = storeToRefs(assistant);
  const route = useRoute(), router = useRouter();
  let pendingNavigation: RagNavigation | undefined;
  let pendingSelectionId = '', navigationVersion = 0, disposed = false;
  const active = () => !disposed && route.path === '/rag/chat';
  async function syncLocation() {
    if (active()) await router.replace({ query: assistant.sessionId ? { conversation: assistant.sessionId } : {} });
  }
  async function selectSession(id: string) {
    const version = ++navigationVersion; pendingSelectionId = id;
    await assistant.selectSession(id);
    if (version !== navigationVersion) return;
    pendingSelectionId = '';
    if (assistant.sessionId === id) await syncLocation();
  }
  async function startDraft(draft: string) {
    navigationVersion++; pendingSelectionId = '';
    await assistant.startDraft(draft); await syncLocation();
  }
  async function newSession() { if (!assistant.busy) await startDraft(''); }
  async function applyNavigation(navigation: RagNavigation) {
    if (navigation.kind === 'conversation') await selectSession(navigation.id);
    else await startDraft(navigation.draft);
  }
  watch(() => [route.query.new, route.query.draft, route.query.conversation], () => {
    if (!active()) return;
    const navigation = ragNavigation(route.query);
    if (navigation.kind === 'conversation' && navigation.id === assistant.sessionId && (!pendingSelectionId || pendingSelectionId === navigation.id)) return;
    // An empty URL after consuming an import must preserve the draft and shared conversation.
    if (navigation.kind === 'new' && route.query.new !== '1' && route.query.draft == null) {
      if (assistant.sessionId && !pendingSelectionId) void syncLocation();
      return;
    }
    if (assistant.busy) { pendingNavigation = navigation; void syncLocation(); return; }
    void applyNavigation(navigation);
  }, { immediate: true });
  watch(() => assistant.sessionId, () => { if (!pendingSelectionId && active()) void syncLocation(); });
  watch(() => assistant.busy, busy => {
    if (busy || !pendingNavigation || !active()) return;
    const navigation = pendingNavigation; pendingNavigation = undefined; void applyNavigation(navigation);
  });
  onMounted(() => { assistant.hide(); assistant.initialize(); });
  onBeforeUnmount(() => { disposed = true; navigationVersion++; });
  return { ...state, loadProviders: assistant.loadProviders, refreshHistory: assistant.refreshHistory,
    selectSession, earlier: assistant.earlier, newSession, ask: assistant.ask, turnLabel: assistant.turnLabel,
    manageSession: assistant.manageSession, copyAnswer: assistant.copyAnswer };
}
