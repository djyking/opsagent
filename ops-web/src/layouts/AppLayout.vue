<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LayoutDashboard, TicketCheck, Bell, ShieldCheck, LogOut, Menu, X, Activity, BookOpen, Bot, Monitor } from '@lucide/vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore(); const router = useRouter(); const route = useRoute(); const open = ref(false)
const title = computed(() => ({ dashboard: '运行总览', tickets: '工单中心', 'ticket-detail': '工单详情', knowledge: '知识库', 'rag-chat': '智能问答', monitor: '系统监控', notifications: '通知中心', admin: '系统管理' }[String(route.name)] || 'OpsAgent'))
const nav = computed(() => [
  { to: '/dashboard', label: '运行总览', icon: LayoutDashboard },
  { to: '/tickets', label: '工单中心', icon: TicketCheck },
  { to: '/knowledge', label: '知识库', icon: BookOpen },
  { to: '/rag/chat', label: '智能问答', icon: Bot },
  { to: '/system/monitor', label: '系统监控', icon: Monitor },
  ...(auth.isAdmin ? [{ to: '/notifications', label: '通知中心', icon: Bell }, { to: '/admin', label: '系统管理', icon: ShieldCheck }] : []),
])
function logout() { auth.logout(); router.push('/login') }
</script>
<template>
  <div class="app-shell">
    <aside class="sidebar" :class="{ open }">
      <div class="brand"><div class="brand-mark"><Activity :size="22" /></div><div><strong>OpsAgent</strong><span>智能运维中枢</span></div><button class="mobile-close icon-button" @click="open=false"><X :size="20" /></button></div>
      <nav><span class="nav-label">工作空间</span><RouterLink v-for="item in nav" :key="item.to" :to="item.to" @click="open=false"><component :is="item.icon" :size="19" /><span>{{ item.label }}</span></RouterLink></nav>
      <div class="sidebar-profile"><div class="avatar">{{ auth.user?.displayName?.slice(0, 1) || auth.user?.username.slice(0, 1) }}</div><div><strong>{{ auth.user?.displayName || auth.user?.username }}</strong><span>{{ auth.isAdmin ? '系统管理员' : auth.isOps ? '运维工程师' : '普通用户' }}</span></div><button class="icon-button" title="退出登录" @click="logout"><LogOut :size="18" /></button></div>
    </aside>
    <div v-if="open" class="sidebar-mask" @click="open=false" />
    <main class="main-area">
      <header class="topbar"><button class="menu-button icon-button" @click="open=true"><Menu :size="21" /></button><div><span class="breadcrumb">OPSAGENT / WORKSPACE</span><h1>{{ title }}</h1></div><div class="system-state"><i />服务运行中</div></header>
      <div class="page-content"><RouterView /></div>
    </main>
  </div>
</template>
