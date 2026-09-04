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
  username: string;
  roleLabel: string;
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
      <Activity :size="16" :stroke-width="1.75" />
    </button>
    <nav>
      <button
        v-for="item in primary"
        :key="item.domain"
        :class="{ active: activeDomain === item.domain }"
        :title="item.label"
        @click="emit('select', item.domain)"
      >
        <component :is="item.icon" :size="16" :stroke-width="1.75" />
      </button>
    </nav>
    <div class="rail-bottom">
      <button
        v-if="isAdmin"
        :class="{ active: activeDomain === 'notifications' }"
        title="通知"
        @click="emit('select', 'notifications')"
      >
        <Bell :size="16" :stroke-width="1.75" />
      </button>
      <button
        v-if="isAdmin"
        :class="{ active: activeDomain === 'system' }"
        title="系统"
        @click="emit('select', 'system')"
      >
        <Settings :size="16" :stroke-width="1.75" />
      </button>
      <details class="rail-user-menu">
        <summary class="rail-avatar" title="账号菜单" aria-label="打开账号菜单">{{ initials }}</summary>
        <div class="rail-user-popover">
          <strong>{{ username }}</strong><small>{{ roleLabel }}</small>
          <button @click="emit('logout')"><LogOut :size="16" :stroke-width="1.75" />退出登录</button>
        </div>
      </details>
    </div>
  </aside>
</template>
