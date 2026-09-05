<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppSidebar from '@/components/AppSidebar.vue';
import GlobalTopbar from '@/components/GlobalTopbar.vue';
import { useAuthStore } from '@/stores/auth';
const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const mobileOpen = ref(false);
const mobileQuery = window.matchMedia('(max-width: 900px)');
function updateMobile() { if (!mobileQuery.matches) mobileOpen.value = false; }
onMounted(() => mobileQuery.addEventListener('change', updateMobile));
onBeforeUnmount(() => mobileQuery.removeEventListener('change', updateMobile));
const contextStorageKey = 'opsagent-context-collapsed';
const collapsed = ref(localStorage.getItem(contextStorageKey) === 'true');
const layoutVariant = computed(() => String(route.meta.layoutVariant || 'standard'));
const contentClass = computed(() => ({
  'content-notifications': layoutVariant.value === 'feed',
  'content-workspace': layoutVariant.value === 'focus',
  'content-detail': layoutVariant.value === 'detail',
  ['layout-' + layoutVariant.value]: true,
}));
watch(() => route.fullPath, () => { mobileOpen.value = false; });
const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || 'O');
const username = computed(() => auth.user?.displayName || auth.user?.username || 'OpsAgent 用户');
const roleLabel = computed(() => auth.isAdmin ? '管理员' : auth.isOps ? '运维人员' : '用户');
function toggleContext() { collapsed.value = !collapsed.value; localStorage.setItem(contextStorageKey, String(collapsed.value)); }
function logout() { auth.logout(); router.push('/login'); }
</script>
<template>
  <div class="app-shell" :class="{ 'navigation-collapsed': collapsed }">
    <AppSidebar :collapsed="collapsed" :mobile-open="mobileOpen" :is-admin="auth.isAdmin" :initials="initials" :username="username" :role-label="roleLabel" @toggle="toggleContext" @close="mobileOpen = false" @logout="logout" />
    <button v-if="mobileOpen" class="navigation-mask" aria-label="关闭导航" @click="mobileOpen = false" />
    <main class="main-area" :inert="mobileOpen || undefined">
      <GlobalTopbar :is-admin="auth.isAdmin" :mobile-open="mobileOpen" @menu="mobileOpen = true" />
      <div class="page-content" :class="contentClass"><RouterView v-slot="{ Component }"><Transition name="page" mode="out-in"><component :is="Component" :key="route.path" /></Transition></RouterView></div>
    </main>
  </div>
</template>
