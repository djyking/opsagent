<script setup lang="ts">
defineProps<{ title?: string }>();
</script>

<template>
  <aside class="oa-inline-notice">
    <strong v-if="title">{{ title }}</strong>
    <span><slot /></span>
  </aside>
</template>
