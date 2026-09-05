<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Activity, ChevronLeft, ChevronRight, LogOut, X } from '@lucide/vue';
import { navigationGroups } from '@/data/navigation';
const props = defineProps<{ collapsed: boolean; mobileOpen: boolean; isAdmin: boolean; initials: string; username: string; roleLabel: string }>();
const emit = defineEmits<{ toggle: []; close: []; logout: [] }>();
const route = useRoute();
const root = ref<HTMLElement>();
const groups = computed(() => navigationGroups.map(group => ({ ...group, items: group.items.filter(item => !item.admin || props.isAdmin) })).filter(group => group.items.length));
const active = (to: string) => route.path === to || (to === '/tickets' && route.name === 'ticket-detail');
let previousFocus: HTMLElement | null = null;
watch(() => props.mobileOpen, async open => {
  if (open) {
    previousFocus = document.querySelector<HTMLElement>('.topbar-menu');
    await nextTick();
    requestAnimationFrame(() => { if (props.mobileOpen) root.value?.querySelector<HTMLButtonElement>('.sidebar-mobile-close')?.focus(); });
  }
  else { await nextTick(); if (previousFocus?.isConnected) previousFocus.focus(); previousFocus = null; }
});
function keyboard(event: KeyboardEvent) {
  if (!props.mobileOpen) return;
  if (event.key === 'Escape') { event.preventDefault(); emit('close'); }
  if (event.key !== 'Tab') return;
  const items = [...root.value!.querySelectorAll<HTMLElement>('a[href],button:not(:disabled),summary')].filter(el => el.getClientRects().length);
  const first = items[0], last = items.at(-1);
  if (!root.value?.contains(document.activeElement)) { event.preventDefault(); first?.focus(); }
  else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
}
onMounted(() => document.addEventListener('keydown', keyboard));
onBeforeUnmount(() => { document.removeEventListener('keydown', keyboard); if (previousFocus?.isConnected) previousFocus.focus(); });
</script>
<template>
  <aside id="app-navigation" ref="root" class="app-sidebar" :class="{ collapsed, 'mobile-open': mobileOpen }" :role="mobileOpen ? 'dialog' : undefined" :aria-modal="mobileOpen || undefined" aria-label="主导航">
    <header class="sidebar-brand"><RouterLink to="/dashboard" aria-label="OpsAgent 运行总览" @click="emit('close')"><span class="sidebar-logo"><Activity :size="25" :stroke-width="1.7" /></span><span class="sidebar-brand-copy"><strong>OpsAgent</strong><small>智能运维工作台</small></span></RouterLink><button class="icon-button sidebar-mobile-close" aria-label="关闭导航" @click="emit('close')"><X :size="18" /></button></header>
    <nav class="sidebar-groups"><section v-for="group in groups" :key="group.label" class="sidebar-group" :aria-label="group.label"><h2>{{ group.label }}</h2><RouterLink v-for="item in group.items" :key="item.to" :to="item.to" :class="{ active: active(item.to) }" :aria-current="active(item.to) ? 'page' : undefined" :title="collapsed ? item.label : undefined" :aria-label="item.label" @click="emit('close')"><component :is="item.icon" :size="18" :stroke-width="1.7" /><span>{{ item.label }}</span><i v-if="active(item.to)" aria-hidden="true" /></RouterLink></section></nav>
    <footer class="sidebar-footer"><div class="sidebar-account"><span class="sidebar-avatar">{{ initials }}</span><span class="sidebar-account-copy"><strong>{{ username }}</strong><small>{{ roleLabel }}</small></span><button class="icon-button sidebar-logout" title="退出登录" aria-label="退出登录" @click="emit('logout')"><LogOut :size="17" /></button></div><button class="sidebar-collapse" :aria-expanded="!collapsed" :aria-label="collapsed ? '展开导航' : '收起导航'" @click="emit('toggle')"><ChevronRight v-if="collapsed" :size="16" /><ChevronLeft v-else :size="16" /><span>收起导航</span></button></footer>
  </aside>
</template>
