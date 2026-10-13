<script setup lang="ts">
defineProps<{ text?: string; compact?: boolean }>();
</script>

<template>
  <div class="oa-loading-state" :class="{ compact }"><i /><span>{{ text || "正在加载…" }}</span></div>
</template>
