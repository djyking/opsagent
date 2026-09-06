<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { Network, List, FileCog, Gauge, ScanLine } from '@lucide/vue';
import PageHeader from '@/components/PageHeader.vue';
import '@/styles/pages/observability.css';
defineProps<{ title?: string; description?: string }>();
const route = useRoute();
const tabs = [{ path: 'topology', label: '拓扑总览', icon: Network }, { path: 'catalog', label: '服务目录', icon: List }, { path: 'config', label: '配置中心', icon: FileCog }, { path: 'traffic', label: '流量治理', icon: Gauge }, { path: 'inspections', label: '持续巡检', icon: ScanLine }];
const context = computed(() => Object.fromEntries(['ciCode', 'environment', 'timeRange'].filter(key => route.query[key]).map(key => [key, route.query[key]])));
</script>
<template>
  <div class="stack-page observability-workspace">
    <PageHeader :icon="Network" :title="title || '服务与观测'" :description="description || '从服务运行状态出发，连接异常、配置、处置与恢复验证。'"><template #actions><slot name="actions" /></template></PageHeader>
    <nav class="obs-tabs" aria-label="服务与观测模块"><RouterLink v-for="tab in tabs" :key="tab.path" :to="{ path: `/observability/${tab.path}`, query: context }" :class="{ active: route.path === `/observability/${tab.path}` }" :aria-current="route.path === `/observability/${tab.path}` ? 'page' : undefined"><component :is="tab.icon" :size="17" />{{ tab.label }}</RouterLink></nav>
    <slot />
  </div>
</template>
