import { ref } from "vue";
interface Toast { id: number; message: string; tone: "success" | "error" | "info"; action?: () => void; actionLabel?: string }
const messages = ref<Toast[]>([]);
const timers = new Map<number, ReturnType<typeof setTimeout>>();
let serial = 0;
function dismiss(id: number) { clearTimeout(timers.get(id)); timers.delete(id); messages.value = messages.value.filter(t => t.id !== id); }
function hold(id: number) { clearTimeout(timers.get(id)); }
function release(id: number) { const t = messages.value.find(t => t.id === id); if (t && t.tone !== "error") timers.set(id, setTimeout(() => dismiss(id), 4000)); }
function show(message: string, tone: Toast["tone"] = "success", action?: () => void, actionLabel = "重试") {
  if (messages.value.length === 3) dismiss(messages.value[0]!.id);
  const id = ++serial;
  messages.value.push({ id, message, tone, action, actionLabel });
  release(id);
  return id;
}
export function useToast() { return { messages, show, dismiss, hold, release }; }
