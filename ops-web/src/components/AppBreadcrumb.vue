<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { ChevronRight } from '@lucide/vue';
import { navigationFor } from '@/data/navigation';
import { parentLocation } from '@/utils/route-navigation';
const route = useRoute();
const page = computed(() => navigationFor(route));
const parent = computed(() => parentLocation(route));
</script>
<template>
  <nav class="app-breadcrumb" aria-label="当前位置">
    <span>{{ page.group }}</span><ChevronRight :size="13" aria-hidden="true" />
    <template v-if="parent"><RouterLink :to="parent">{{ route.meta.parentTitle }}</RouterLink><ChevronRight :size="13" aria-hidden="true" /></template>
    <strong aria-current="page">{{ page.label }}</strong>
  </nav>
</template>
<style scoped>
.app-breadcrumb { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; color: var(--oa-text-muted); font-size: var(--oa-font-size-sm); }
.app-breadcrumb a { color: var(--oa-text-secondary); text-decoration: none; }
.app-breadcrumb a:hover { color: var(--oa-primary); }
.app-breadcrumb a:focus-visible { outline: 2px solid var(--oa-primary); outline-offset: 4px; }
.app-breadcrumb strong { color: var(--oa-text-primary); font-weight: 500; }
@media (max-width: 640px) { .app-breadcrumb > span, .app-breadcrumb > svg:first-of-type { display: none; } .app-breadcrumb { font-size: 12px; gap: 4px; } }
</style>
