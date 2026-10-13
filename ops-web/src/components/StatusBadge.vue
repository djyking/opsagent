<script setup lang="ts">
import { computed } from "vue";
import { getStatusMeta } from "@/ui/status-map";

const props = defineProps<{ value: string }>();
const meta = computed(() => getStatusMeta(props.value));
</script>
<template>
  <span class="status-badge" :class="`status-tone-${meta.tone}`" :title="meta.rawValue">
    <i />{{ meta.label }}
  </span>
</template>
