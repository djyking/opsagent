<script setup lang="ts">
import { CheckCircle2, CircleAlert, Info, X } from "@lucide/vue";
import { useToast } from "@/composables/useToast";
const { messages, dismiss, hold, release } = useToast();
</script>
<template>
  <aside class="toast-host" aria-label="操作反馈">
    <TransitionGroup name="toast" tag="div">
      <div v-for="item in messages" :key="item.id" class="toast-message" :class="item.tone" data-motion :role="item.tone === 'error' ? 'alert' : 'status'" @mouseenter="hold(item.id)" @mouseleave="release(item.id)" @focusin="hold(item.id)" @focusout="release(item.id)">
        <component :is="item.tone === 'error' ? CircleAlert : item.tone === 'success' ? CheckCircle2 : Info" :size="18" />
        <span>{{ item.message }}</span>
        <button v-if="item.action" class="text-button" @click="item.action(); dismiss(item.id)">{{ item.actionLabel }}</button>
        <button class="icon-button" aria-label="关闭提示" @click="dismiss(item.id)"><X :size="14" /></button>
      </div>
    </TransitionGroup>
  </aside>
</template>
