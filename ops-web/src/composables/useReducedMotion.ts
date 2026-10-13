import { onBeforeUnmount, onMounted, ref } from "vue";

export function useReducedMotion() {
  const query = window.matchMedia("(prefers-reduced-motion: reduce)");
  const reduced = ref(query.matches);
  const update = () => { reduced.value = query.matches; };
  onMounted(() => query.addEventListener("change", update));
  onBeforeUnmount(() => query.removeEventListener("change", update));
  return reduced;
}
