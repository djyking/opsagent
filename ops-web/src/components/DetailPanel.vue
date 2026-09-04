<script setup lang="ts">
import { onBeforeUnmount, onMounted } from "vue";
import { ArrowUpRight, X } from "@lucide/vue";

const props = defineProps<{
  title: string;
  subtitle?: string;
  fullPath?: string;
}>();

const emit = defineEmits<{ close: [] }>();

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Escape") emit("close");
}

onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <Teleport to="body">
    <button class="detail-panel-mask" aria-label="关闭详情" @click="emit('close')" />
    <aside class="detail-panel" role="dialog" aria-modal="true" :aria-label="title">
      <header>
        <div><span v-if="subtitle">{{ subtitle }}</span><h2>{{ title }}</h2></div>
        <button class="icon-button" title="关闭（Esc）" @click="emit('close')"><X :size="17" /></button>
      </header>
      <div class="detail-panel-body"><slot /></div>
      <footer v-if="fullPath">
        <RouterLink class="button primary" :to="props.fullPath || '/'">打开完整页面 <ArrowUpRight :size="15" /></RouterLink>
      </footer>
    </aside>
  </Teleport>
</template>
