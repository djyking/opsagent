<script setup lang="ts">
import { Check, LoaderCircle } from "@lucide/vue";
defineProps<{ loading?: boolean; disabled?: boolean; success?: boolean; loadingText?: string; successText?: string }>();
</script>
<template>
  <button class="button" data-motion :disabled="loading || disabled" :aria-busy="loading || undefined">
    <LoaderCircle v-if="loading" class="motion-spin" :size="16" /><Check v-else-if="success" :size="16" />
    <template v-if="loading">{{ loadingText || '正在处理…' }}</template><template v-else-if="success">{{ successText || '已完成' }}</template><slot v-else />
  </button>
</template>
