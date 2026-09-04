<script setup lang="ts">
import type { Component } from "vue";
defineProps<{ title: string; description?: string; icon?: Component; compact?: boolean }>();
</script>

<template>
  <div class="oa-empty-state" :class="{ compact }">
    <component :is="icon" v-if="icon" :size="24" :stroke-width="1.75" />
    <strong>{{ title }}</strong>
    <p v-if="description">{{ description }}</p>
    <div v-if="$slots.action"><slot name="action" /></div>
  </div>
</template>
