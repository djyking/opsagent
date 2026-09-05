<script setup lang="ts">
import { computed, type Component } from 'vue';
import { useRoute } from 'vue-router';
import { navigationFor } from '@/data/navigation';
defineProps<{
  title: string;
  description?: string;
  icon?: Component;
  eyebrow?: string;
}>();
const route = useRoute();
const page = computed(() => navigationFor(route.path));
</script>

<template>
  <header class="oa-page-header">
    <div class="oa-page-heading">
      <span class="oa-page-symbol" aria-hidden="true"><component :is="icon || page.icon" :size="24" :stroke-width="1.7" /></span>
      <div>
      <span v-if="eyebrow" class="oa-page-eyebrow">{{ eyebrow }}</span>
      <h2>{{ title }}</h2>
      <p v-if="description">{{ description }}</p>
      <div v-if="$slots.meta" class="oa-page-meta"><slot name="meta" /></div>
      </div>
    </div>
    <div v-if="$slots.actions" class="oa-page-actions">
      <slot name="actions" />
    </div>
    <div v-if="$slots.tabs" class="oa-page-tabs"><slot name="tabs" /></div>
  </header>
</template>
