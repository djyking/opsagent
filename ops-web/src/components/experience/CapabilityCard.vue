<script setup lang="ts">
import { ArrowUpRight, BookCheck, BookOpen, Bot, DatabaseZap } from "@lucide/vue";
import { ref } from "vue";
import type { capabilities } from "@/data/experience";
defineProps<{ capability: typeof capabilities[number] }>();
const icons = { rag: Bot, knowledge: BookOpen, review: BookCheck, index: DatabaseZap };
const expanded = ref(false);
</script>
<template><article class="capability-card" :class="`accent-${capability.tone}`" data-motion><header><span class="capability-icon"><component :is="icons[capability.key as keyof typeof icons]" :size="24" /></span><span class="capability-permission">{{ capability.admin ? '管理员' : '可用' }}</span></header><h3>{{ capability.label }}</h3><p>{{ capability.description }}</p><div class="experience-tags"><span v-for="tag in capability.tags" :key="tag">{{ tag }}</span></div><footer><button class="text-button" :aria-expanded="expanded" @click="expanded = !expanded">{{ expanded ? '收起说明' : '查看说明' }}</button><RouterLink :to="capability.to">立即使用 <ArrowUpRight :size="15" /></RouterLink></footer><p v-if="expanded" class="capability-detail" data-motion="tab">{{ capability.detail }}</p></article></template>
