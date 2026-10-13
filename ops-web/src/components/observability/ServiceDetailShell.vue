<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { X } from '@lucide/vue';
import DetailPanel from '@/components/DetailPanel.vue';
const props = defineProps<{ title: string; subtitle: string; inline?: boolean }>();
const emit = defineEmits<{ close: [] }>();
const shell = ref<HTMLElement>(); const height = ref<string>();
let resize: ResizeObserver | undefined;
function fitHeight() { if (!shell.value || window.innerWidth <= 980) { height.value = undefined; return; } height.value = `${Math.max(280, window.innerHeight - Math.max(14, shell.value.getBoundingClientRect().top) - 14)}px`; }
function escape(event: KeyboardEvent) { if (props.inline && event.key === 'Escape') emit('close'); }
onMounted(() => { window.addEventListener('keydown', escape); window.addEventListener('resize', fitHeight); window.addEventListener('scroll', fitHeight, { passive: true }); if (shell.value) { resize = new ResizeObserver(fitHeight); resize.observe(shell.value); fitHeight(); } });
onBeforeUnmount(() => { window.removeEventListener('keydown', escape); window.removeEventListener('resize', fitHeight); window.removeEventListener('scroll', fitHeight); resize?.disconnect(); });
</script>
<template>
  <aside v-if="inline" ref="shell" class="obs-inline-drawer panel" :style="{ maxHeight: height }" :aria-label="title + '详情'">
    <header><div><h2>{{ title }}</h2><small>{{ subtitle }}</small></div><button class="icon-button" aria-label="关闭服务详情" @click="emit('close')"><X :size="17" /></button></header>
    <div class="obs-inline-drawer-body"><slot /></div><footer><slot name="footer" /></footer>
  </aside>
  <DetailPanel v-else :title="title" :subtitle="subtitle" @close="emit('close')"><slot /><template #footer><slot name="footer" /></template></DetailPanel>
</template>
