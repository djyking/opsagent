<script setup lang="ts">
const props = defineProps<{ value: string }>();

const labels: Record<string, string> = {
  LOW: "低",
  MEDIUM: "中",
  HIGH: "高",
  URGENT: "紧急",
};
</script>

<template>
  <span class="priority-indicator" :class="`priority-${props.value.toLowerCase()}`">
    <i />{{ labels[props.value] || props.value }}
  </span>
</template>
