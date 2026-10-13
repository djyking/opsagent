<script setup lang="ts">
defineProps<{ identifier: string; title: string; description?: string }>();
</script>

<template>
  <section class="oa-detail-header">
    <div class="oa-detail-heading">
      <div v-if="$slots.back" class="oa-detail-back"><slot name="back" /></div>
      <div class="oa-detail-identity"><code>{{ identifier }}</code><div v-if="$slots.badges" class="oa-detail-badges"><slot name="badges" /></div></div>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
      <div v-if="$slots.meta" class="oa-detail-meta"><slot name="meta" /></div>
    </div>
    <div v-if="$slots.actions" class="oa-detail-actions"><slot name="actions" /></div>
    <div v-if="$slots.tabs" class="oa-detail-tabs"><slot name="tabs" /></div>
  </section>
</template>