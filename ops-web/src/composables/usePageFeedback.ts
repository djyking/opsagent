import { watch, type Ref } from "vue";
import { useToast } from "./useToast";
export function usePageFeedback(error: Ref<string>, reload?: () => void | Promise<void>) {
  const toast = useToast();
  watch(error, message => { if (message) toast.show(message, "error", reload ? () => { void reload(); } : undefined, "重新加载"); });
  return toast;
}
