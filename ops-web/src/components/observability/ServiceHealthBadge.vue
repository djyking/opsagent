<script setup lang="ts">
import type { ServiceHealth } from '@/api/observability';
import { healthColors, healthLabels } from '@/utils/observability';
defineProps<{ health?: ServiceHealth; reason?: string }>();
</script>
<template><span class="obs-health-badge" :style="{ '--health-color': healthColors[health || 'UNKNOWN'] }" :title="reason"><i />{{ healthLabels[health || 'UNKNOWN'] }}</span></template>
