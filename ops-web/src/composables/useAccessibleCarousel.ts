import { computed, onBeforeUnmount, onMounted, ref, toValue, watch, type MaybeRefOrGetter } from "vue";
import { useReducedMotion } from "./useReducedMotion";

export function useAccessibleCarousel(count: MaybeRefOrGetter<number>, intervalMs = 5500) {
  const reduced = useReducedMotion();
  const activeIndex = ref(0);
  const enabled = ref(!reduced.value);
  const hovered = ref(false);
  const focusPaused = ref(false);
  const hidden = ref(document.hidden);
  let timer: ReturnType<typeof setTimeout> | undefined;
  const playing = computed(() => enabled.value && !hovered.value && !focusPaused.value && !hidden.value && toValue(count) > 1);
  const goTo = (index: number) => { const n = toValue(count); activeIndex.value = n ? (index + n) % n : 0; };
  const next = () => goTo(activeIndex.value + 1);
  const previous = () => goTo(activeIndex.value - 1);
  const pause = () => { enabled.value = false; };
  const resume = () => { enabled.value = true; focusPaused.value = false; };
  const onFocusIn = (event: FocusEvent) => {
    if (!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) focusPaused.value = true;
  };
  const onKeydown = (event: KeyboardEvent) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    pause();
    if (event.key === "ArrowLeft") previous();
    else if (event.key === "ArrowRight") next();
    else goTo(event.key === "Home" ? 0 : toValue(count) - 1);
  };
  watch([playing, activeIndex, () => toValue(count)], () => {
    clearTimeout(timer);
    if (activeIndex.value >= toValue(count)) goTo(0);
    if (playing.value) timer = setTimeout(next, intervalMs);
  }, { immediate: true });
  watch(reduced, (value) => { if (value) pause(); });
  const visibility = () => { hidden.value = document.hidden; };
  onMounted(() => document.addEventListener("visibilitychange", visibility));
  onBeforeUnmount(() => { clearTimeout(timer); document.removeEventListener("visibilitychange", visibility); });
  return { activeIndex, playing, enabled, focusPaused, reduced, next, previous, goTo, pause, resume,
    onPointerEnter: () => { hovered.value = true; }, onPointerLeave: () => { hovered.value = false; }, onFocusIn, onKeydown };
}
