<script setup lang="ts">
import { Bot, ChevronDown, ChevronRight, FileUp, Menu, Plus, Search, Settings2, ShieldCheck, TicketCheck } from "@lucide/vue";
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { managementNavigation, navigationFor } from '@/data/navigation';
import { useAuthStore } from '@/stores/auth';
import { useApprovalInboxStore } from '@/stores/approval-inbox';

defineProps<{ isAdmin: boolean; mobileOpen?: boolean }>();
const emit = defineEmits<{ menu: [] }>();
const router = useRouter();
const route = useRoute();
const auth = useAuthStore();
const approvalInbox = useApprovalInboxStore();
const page = computed(() => navigationFor(route.path));
const createMenu = ref<HTMLDetailsElement>();
const managementMenu = ref<HTMLDetailsElement>();
watch(() => route.fullPath, () => { if (createMenu.value) createMenu.value.open = false; if (managementMenu.value) managementMenu.value.open = false; });
const keyword = ref("");
function searchTicket() { const value = keyword.value.trim(); if (value) router.push({ path: "/tickets", query: { keyword: value } }); }
</script>

<template>
  <header class="global-topbar">
    <button class="icon-button topbar-menu" title="打开导航" aria-controls="app-navigation" :aria-expanded="mobileOpen" @click="emit('menu')"><Menu :size="18" :stroke-width="1.75" /></button>
    <nav class="topbar-breadcrumb" aria-label="当前位置"><span>{{ page.group }}</span><ChevronRight :size="13" /><strong>{{ route.name === 'ticket-detail' ? '事件详情' : page.label }}</strong></nav>
    <div class="topbar-spacer" />
    <form class="topbar-search" role="search" @submit.prevent="searchTicket"><Search :size="16" :stroke-width="1.75" /><input v-model="keyword" aria-label="搜索事件" placeholder="搜索事件…" /><kbd>Enter</kbd></form>
    <details ref="createMenu" class="topbar-create-menu"><summary class="button primary topbar-create"><Plus :size="16" :stroke-width="1.75" />创建<ChevronDown :size="16" :stroke-width="1.75" /></summary><div class="topbar-create-popover"><RouterLink v-if="!auth.isDemo" to="/tickets?create=1"><TicketCheck :size="16" /><span><strong>新建事件</strong><small>记录影响、分派责任并跟进处置</small></span></RouterLink><RouterLink to="/rag/chat?new=1"><Bot :size="16" /><span><strong>助手新会话</strong><small>通用问答与知识检索</small></span></RouterLink><RouterLink v-if="!auth.isDemo" to="/knowledge"><FileUp :size="16" /><span><strong>上传知识文档</strong><small>进入知识库并选择目标库</small></span></RouterLink></div></details>
    <RouterLink class="button secondary topbar-assistant" to="/rag/chat" aria-label="AI 助手，通用问答与知识检索" title="AI 助手 · 通用问答与知识检索"><Bot :size="17" /><span>AI 助手</span></RouterLink>
    <button type="button" class="topbar-approval" :class="{ pending: approvalInbox.count > 0 }" aria-haspopup="dialog" :aria-expanded="approvalInbox.open" :aria-label="approvalInbox.error ? '待审批数据未能更新，请打开重试' : `待处理审批 ${approvalInbox.count} 项`" :title="approvalInbox.error || '从任意页面处理待审批动作'" @click="approvalInbox.show"><ShieldCheck :size="17" :stroke-width="1.75" /><span class="topbar-approval-label">待审批</span><span class="topbar-approval-count" aria-live="polite" aria-atomic="true">{{ approvalInbox.error ? '!' : approvalInbox.loading && !approvalInbox.items.length ? '…' : approvalInbox.count }}</span></button>
    <details v-if="isAdmin && !auth.isDemo" ref="managementMenu" class="topbar-management"><summary class="button secondary" aria-label="系统管理入口" title="系统管理"><Settings2 :size="17" /><span>管理</span><ChevronDown :size="14" /></summary><nav class="topbar-create-popover" aria-label="系统管理"><RouterLink v-for="item in managementNavigation" :key="item.to" :to="item.to"><component :is="item.icon" :size="17" /><span>{{ item.label }}</span></RouterLink></nav></details>
  </header>
</template>
<style scoped>
.topbar-approval { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: var(--oa-control-default); flex: none; padding: 0 10px; border: 1px solid var(--oa-border-default); border-radius: var(--oa-radius-control); background: var(--oa-bg-surface); color: var(--oa-text-secondary); font: inherit; font-size: var(--oa-font-size-sm); cursor: pointer; white-space: nowrap; }
.topbar-approval.pending { border-color: #e7cdab; background: var(--oa-warning-soft); color: var(--oa-warning); }
.topbar-approval-count { display: inline-grid; place-items: center; min-width: 20px; min-height: 20px; padding: 0 4px; border-radius: 6px; background: var(--oa-bg-subtle); font-size: var(--oa-font-size-xs); font-variant-numeric: tabular-nums; }
.topbar-approval.pending .topbar-approval-count { background: var(--oa-warning); color: white; }
.topbar-approval:focus-visible { outline: 2px solid var(--oa-primary); outline-offset: 2px; }
.topbar-management { position: relative; }
.topbar-management > summary { list-style: none; }
.topbar-management > summary::-webkit-details-marker { display: none; }
.topbar-management nav { width: 210px; }
@media (max-width: 640px) { .topbar-approval-label, .topbar-assistant > span, .topbar-management > summary > span, .topbar-management > summary > svg:last-child { display: none; } .topbar-approval { padding-inline: 8px; gap: 5px; } .topbar-assistant, .topbar-management > summary { padding-inline: 8px; } }
</style>
