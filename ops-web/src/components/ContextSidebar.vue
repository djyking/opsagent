<script setup lang="ts">
import { Bell, Bot, ChevronLeft, ChevronRight, LayoutDashboard, Monitor, Settings, TicketCheck, X } from "@lucide/vue";
import type { Component } from "vue";
import { useRoute } from "vue-router";

defineProps<{ title: string; items: Array<{ to: string; label: string; icon: Component }>; collapsed: boolean; mobileOpen: boolean; isAdmin: boolean }>();
const emit = defineEmits<{ toggle: []; close: [] }>();
const route = useRoute();
function isActive(to: string) {
  return route.path === to || (to === "/tickets" && route.name === "ticket-detail");
}
</script>

<template>
  <aside class="context-sidebar" :class="{ collapsed, 'mobile-open': mobileOpen }" aria-label="上下文导航">
    <header><strong>{{ title }}</strong><button class="icon-button context-mobile-close" title="关闭导航" @click="emit('close')"><X :size="16" :stroke-width="1.75" /></button></header>
    <nav class="mobile-domain-nav" aria-label="产品域">
      <RouterLink to="/dashboard" @click="emit('close')"><LayoutDashboard :size="16" :stroke-width="1.75" /><span>总览</span></RouterLink>
      <RouterLink to="/tickets" @click="emit('close')"><TicketCheck :size="16" :stroke-width="1.75" /><span>运维</span></RouterLink>
      <RouterLink to="/system/monitor" @click="emit('close')"><Monitor :size="16" :stroke-width="1.75" /><span>可观测</span></RouterLink>
      <RouterLink to="/ai" @click="emit('close')"><Bot :size="16" :stroke-width="1.75" /><span>AI 智能</span></RouterLink>
      <RouterLink v-if="isAdmin" to="/notifications" @click="emit('close')"><Bell :size="16" :stroke-width="1.75" /><span>通知</span></RouterLink>
      <RouterLink v-if="isAdmin" to="/admin" @click="emit('close')"><Settings :size="16" :stroke-width="1.75" /><span>系统</span></RouterLink>
    </nav>
    <nav aria-label="模块页面"><RouterLink v-for="item in items" :key="item.to" :to="item.to" :title="item.label" :class="{ 'context-link-active': isActive(item.to) }" :aria-current="isActive(item.to) ? 'page' : undefined" @click="emit('close')"><component :is="item.icon" :size="16" :stroke-width="1.75" /><span>{{ item.label }}</span></RouterLink></nav>
    <button class="context-collapse" :aria-expanded="!collapsed" :title="collapsed ? '展开导航' : '收起导航'" @click="emit('toggle')"><ChevronRight v-if="collapsed" :size="16" :stroke-width="1.75" /><ChevronLeft v-else :size="16" :stroke-width="1.75" /><span>{{ collapsed ? "展开" : "收起" }}</span></button>
  </aside>
</template>
