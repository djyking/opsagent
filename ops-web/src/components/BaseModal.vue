<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";
import { X } from "@lucide/vue";
defineProps<{ title: string; wide?: boolean }>();
const emit = defineEmits<{ close: [] }>();
function close() {
  emit("close");
}
function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") close();
}
onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>
<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click.self="close">
      <section
        class="modal"
        :class="{ 'modal-wide': wide }"
        role="dialog"
        aria-modal="true"
      >
        <header>
          <div>
            <span class="eyebrow">OPSAGENT</span>
            <h2>{{ title }}</h2>
          </div>
          <button type="button" class="icon-button modal-close" aria-label="关闭" @click.stop="close">
            <X :size="20" />
          </button>
        </header>
        <div class="modal-body"><slot /></div>
        <footer v-if="$slots.footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>
