<script setup lang="ts">
import { AlertCircle, X } from "@lucide/vue";
defineProps<{ message: string; dismissible?: boolean }>();
defineEmits<{ dismiss: [] }>();
</script>

<template>
  <div class="oa-inline-error" role="alert">
    <AlertCircle :size="16" /><span>{{ message }}</span>
    <button v-if="dismissible" type="button" title="关闭" @click="$emit('dismiss')"><X :size="14" /></button>
  </div>
</template>
