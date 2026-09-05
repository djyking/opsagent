<script setup lang="ts">
import { navigationError, retryNavigation } from "@/utils/navigation-recovery";
import ToastHost from "@/components/feedback/ToastHost.vue";
</script>

<template>
  <RouterView />
  <ToastHost />
  <div v-if="navigationError" class="navigation-error" role="alert">
    <span>{{ navigationError.message }}</span>
    <button class="button secondary small" @click="retryNavigation">重新打开页面</button>
  </div>
</template>
