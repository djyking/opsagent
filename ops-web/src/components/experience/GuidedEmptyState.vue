<script setup lang="ts">
import { ArrowRight, BookOpen, CircleCheck, CalendarClock, DatabaseZap } from "@lucide/vue";
defineProps<{ title: string; description: string; action?: string; kind?: 'knowledge' | 'alerts' | 'oncall' | 'index'; steps?: string[] }>();
defineEmits<{ action: [] }>();
const icons = { knowledge: BookOpen, alerts: CircleCheck, oncall: CalendarClock, index: DatabaseZap };
</script>
<template><div class="guided-empty" data-motion><span class="guided-empty-icon"><component :is="icons[kind || 'knowledge']" :size="26" /></span><h3>{{ title }}</h3><p>{{ description }}</p><ol v-if="steps?.length"><li v-for="step in steps" :key="step">{{ step }}</li></ol><button v-if="action" class="button secondary" @click="$emit('action')">{{ action }}<ArrowRight :size="15" /></button><slot /></div></template>
