<script setup lang="ts">
import { X } from '@lucide/vue'
defineProps<{ title: string; wide?: boolean }>()
defineEmits<{ close: [] }>()
</script>
<template>
  <Teleport to="body">
    <div class="modal-backdrop" @click.self="$emit('close')">
      <section class="modal" :class="{ 'modal-wide': wide }" role="dialog" aria-modal="true">
        <header><div><span class="eyebrow">OPSAGENT</span><h2>{{ title }}</h2></div><button class="icon-button" aria-label="关闭" @click="$emit('close')"><X :size="20" /></button></header>
        <div class="modal-body"><slot /></div>
        <footer v-if="$slots.footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>
