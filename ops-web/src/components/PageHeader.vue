<script setup lang="ts">
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<template>
  <header class="oa-page-header">
    <div>
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
      <div v-if="$slots.meta" class="oa-page-meta"><slot name="meta" /></div>
    </div>
    <div v-if="$slots.actions" class="oa-page-actions">
      <slot name="actions" />
    </div>
    <div v-if="$slots.tabs" class="oa-page-tabs"><slot name="tabs" /></div>
  </header>
</template>
