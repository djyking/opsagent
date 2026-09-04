<script setup lang="ts">
import {
  Activity,
  Bell,
  Bot,
  LayoutDashboard,
  LogOut,
  Monitor,
  Settings,
  TicketCheck,
} from "@lucide/vue";
import type { Component } from "vue";

defineProps<{
  activeDomain: string;
  isAdmin: boolean;
  initials: string;
}>();

const emit = defineEmits<{
  select: [domain: string];
  logout: [];
}>();

const primary: Array<{ domain: string; label: string; icon: Component }> = [
  { domain: "overview", label: "总览", icon: LayoutDashboard },
  { domain: "operations", label: "运维", icon: TicketCheck },
  { domain: "observability", label: "可观测", icon: Monitor },
  { domain: "ai", label: "AI", icon: Bot },
];
</script>

<template>
  <aside class="app-rail" aria-label="产品域导航">
    <button class="rail-logo" title="OpsAgent" @click="emit('select', 'overview')">
      <Activity :size="21" />
    </button>
    <nav>
      <button
        v-for="item in primary"
        :key="item.domain"
        :class="{ active: activeDomain === item.domain }"
        :title="item.label"
        @click="emit('select', item.domain)"
      >
        <component :is="item.icon" :size="20" />
        <span>{{ item.label }}</span>
      </button>
    </nav>
    <div class="rail-bottom">
      <button
        v-if="isAdmin"
        :class="{ active: activeDomain === 'notifications' }"
        title="通知"
        @click="emit('select', 'notifications')"
      >
        <Bell :size="20" /><span>通知</span>
      </button>
      <button
        v-if="isAdmin"
        :class="{ active: activeDomain === 'system' }"
        title="系统"
        @click="emit('select', 'system')"
      >
        <Settings :size="20" /><span>系统</span>
      </button>
      <button class="rail-avatar" title="退出登录" @click="emit('logout')">
        <span>{{ initials }}</span><LogOut :size="15" />
      </button>
    </div>
  </aside>
</template>
