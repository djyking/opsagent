<script setup lang="ts">
import { Activity, Bell, Bot, ChevronDown, ChevronRight, FileUp, Menu, Plus, Search, TicketCheck } from "@lucide/vue";
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { navigationFor } from '@/data/navigation';

defineProps<{ isAdmin: boolean; mobileOpen?: boolean }>();
const emit = defineEmits<{ menu: [] }>();
const router = useRouter();
const route = useRoute();
const page = computed(() => navigationFor(route.path));
const createMenu = ref<HTMLDetailsElement>();
watch(() => route.fullPath, () => { if (createMenu.value) createMenu.value.open = false; });
const keyword = ref("");
function searchTicket() { const value = keyword.value.trim(); if (value) router.push({ path: "/tickets", query: { keyword: value } }); }
</script>

<template>
  <header class="global-topbar">
    <button class="icon-button topbar-menu" title="打开导航" aria-controls="app-navigation" :aria-expanded="mobileOpen" @click="emit('menu')"><Menu :size="18" :stroke-width="1.75" /></button>
    <nav class="topbar-breadcrumb" aria-label="当前位置"><span>{{ page.group }}</span><ChevronRight :size="13" /><strong>{{ route.name === 'ticket-detail' ? '工单详情' : page.label }}</strong></nav>
    <div class="topbar-spacer" />
    <form class="topbar-search" role="search" @submit.prevent="searchTicket"><Search :size="16" :stroke-width="1.75" /><input v-model="keyword" aria-label="搜索工单" placeholder="搜索工单…" /><kbd>Enter</kbd></form>
    <details ref="createMenu" class="topbar-create-menu"><summary class="button primary topbar-create"><Plus :size="16" :stroke-width="1.75" />创建<ChevronDown :size="16" :stroke-width="1.75" /></summary><div class="topbar-create-popover"><RouterLink to="/tickets?create=1"><TicketCheck :size="16" /><span><strong>新建工单</strong><small>记录并推进运维问题</small></span></RouterLink><RouterLink to="/rag/chat?new=1"><Bot :size="16" /><span><strong>新会话</strong><small>从知识检索开始分析</small></span></RouterLink><RouterLink to="/knowledge"><FileUp :size="16" /><span><strong>上传知识文档</strong><small>进入知识库并选择目标库</small></span></RouterLink></div></details>
    <RouterLink v-if="isAdmin" class="icon-button" to="/notifications" title="通知中心"><Bell :size="16" :stroke-width="1.75" /></RouterLink>
    <RouterLink class="topbar-monitor" to="/system/monitor"><Activity :size="16" />服务监控</RouterLink>
  </header>
</template>
