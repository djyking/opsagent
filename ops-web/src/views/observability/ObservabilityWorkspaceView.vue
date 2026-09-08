<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { Network, FileCog, Gauge, ScanLine } from '@lucide/vue';
import PageHeader from '@/components/PageHeader.vue';
import '@/styles/pages/observability.css';
defineProps<{ title?: string; description?: string }>();
const route = useRoute();
const tabs = [{ path: 'topology', label: '服务视图', icon: Network }, { path: 'config', label: '配置中心', icon: FileCog }, { path: 'traffic', label: '流量治理', icon: Gauge }, { path: 'inspections', label: '持续巡检', icon: ScanLine }];
function active(path: string) { return route.path === `/observability/${path}` || path === 'topology' && route.path === '/observability/catalog'; }
const context = computed(() => Object.fromEntries(['ciCode', 'environment', 'timeRange'].filter(key => route.query[key]).map(key => [key, route.query[key]])));
</script>
<template>
  <div class="stack-page observability-workspace">
    <PageHeader :icon="Network" :title="title || '服务与观测'"><template #actions><slot name="actions" /></template><template #tabs><nav class="obs-tabs" aria-label="服务与观测模块"><RouterLink v-for="tab in tabs" :key="tab.path" :to="{ path: `/observability/${tab.path}`, query: context }" :class="{ active: active(tab.path) }" :aria-current="active(tab.path) ? 'page' : undefined">{{ tab.label }}</RouterLink></nav></template></PageHeader>
    <slot />
  </div>
</template>
