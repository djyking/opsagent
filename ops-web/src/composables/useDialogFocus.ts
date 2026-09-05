import { onBeforeUnmount, onMounted, type Ref } from "vue";
export function useDialogFocus(root: Ref<HTMLElement | undefined>) {
  let previous: HTMLElement | null = null;
  function onKey(event: KeyboardEvent) {
    if (event.key !== 'Tab' || !root.value) return;
    const items = [...root.value.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')].filter(el => el.getClientRects().length);
    const first = items[0], last = items[items.length - 1];
    if (!first || !last) { event.preventDefault(); return; }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  }
  onMounted(() => { previous = document.activeElement as HTMLElement; root.value?.querySelector<HTMLElement>('button, input, [tabindex="0"]')?.focus(); root.value?.addEventListener('keydown', onKey); });
  onBeforeUnmount(() => { root.value?.removeEventListener('keydown', onKey); if (previous?.isConnected) previous.focus(); });
}
