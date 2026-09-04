<script setup lang="ts">
import { RouterLink } from "vue-router";
import type { Component } from "vue";

export interface MetricStripItem {
  key: string;
  label: string;
  value: string | number;
  meta?: string;
  to?: string;
  tone?: "default" | "success" | "warning" | "danger";
  icon?: Component;
}

defineProps<{ items: MetricStripItem[]; label?: string }>();
</script>

<template>
  <section class="metric-strip" :aria-label="label || '关键指标'">
    <component
      :is="item.to ? RouterLink : 'div'"
      v-for="item in items"
      :key="item.key"
      :to="item.to"
      class="metric-strip-item"
      :class="`tone-${item.tone || 'default'}`"
    >
      <span class="metric-strip-label">
        <component :is="item.icon" v-if="item.icon" :size="16" :stroke-width="1.75" />
        {{ item.label }}
      </span>
      <strong>{{ item.value }}</strong>
      <small v-if="item.meta">{{ item.meta }}</small>
    </component>
  </section>
</template>
