<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppSidebar from '@/components/AppSidebar.vue';
import GlobalTopbar from '@/components/GlobalTopbar.vue';
import GlobalApprovalInbox from '@/components/automation/GlobalApprovalInbox.vue';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import { useAuthStore } from '@/stores/auth';
import { eventNavigation, managementNavigation } from '@/data/navigation';
const auth = useAuthStore();
const approvalInbox = useApprovalInboxStore();
const router = useRouter();
const route = useRoute();
const mobileOpen = ref(false);
const mobileQuery = window.matchMedia('(max-width: 900px)');
function updateMobile() { if (!mobileQuery.matches) mobileOpen.value = false; }
onMounted(() => { mobileQuery.addEventListener('change', updateMobile); approvalInbox.start(); });
onBeforeUnmount(() => { mobileQuery.removeEventListener('change', updateMobile); approvalInbox.stop(); });
const contextStorageKey = 'opsagent-context-collapsed';
const collapsed = ref(localStorage.getItem(contextStorageKey) === 'true');
const layoutVariant = computed(() => String(route.meta.layoutVariant || 'standard'));
const secondaryNavigation = computed(() => eventNavigation.some(item => item.to !== '/tickets' && item.to === route.path)
  ? eventNavigation : managementNavigation.some(item => item.to === route.path) && auth.isAdmin && !auth.isDemo ? managementNavigation : []);
const contentClass = computed(() => ({
  'content-notifications': layoutVariant.value === 'feed',
  'content-workspace': layoutVariant.value === 'focus',
  'content-detail': layoutVariant.value === 'detail',
  ['layout-' + layoutVariant.value]: true,
}));
watch(() => route.fullPath, () => { mobileOpen.value = false; });
const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || 'O');
const username = computed(() => auth.user?.displayName || auth.user?.username || 'OpsAgent 用户');
const roleLabel = computed(() => auth.isAdmin ? '管理员' : auth.isOps ? '运维人员' : auth.isDemo ? '体验访客' : '用户');
function toggleContext() { collapsed.value = !collapsed.value; localStorage.setItem(contextStorageKey, String(collapsed.value)); }
function logout() { auth.logout(); router.push('/login'); }
</script>
<template>
  <div class="app-shell" :class="{ 'navigation-collapsed': collapsed }">
    <AppSidebar :collapsed="collapsed" :mobile-open="mobileOpen" :is-admin="auth.isAdmin" :initials="initials" :username="username" :role-label="roleLabel" @toggle="toggleContext" @close="mobileOpen = false" @logout="logout" />
    <button v-if="mobileOpen" class="navigation-mask" aria-label="关闭导航" @click="mobileOpen = false" />
    <main class="main-area" :inert="mobileOpen || undefined">
      <GlobalTopbar :is-admin="auth.isAdmin" :mobile-open="mobileOpen" @menu="mobileOpen = true" />
      <div v-if="auth.isDemo" class="visitor-notice">访客体验 · 可浏览事件、使用 AI 助手，发起隔离演练并审批本人动作。会话仅属于本次登录，30 分钟后需重新登录。</div>
      <div class="page-content" :class="contentClass">
        <nav v-if="secondaryNavigation.length" class="module-secondary-navigation" aria-label="模块二级导航"><RouterLink v-for="item in secondaryNavigation" :key="item.to" :to="item.to" :aria-current="route.path === item.to ? 'page' : undefined"><component :is="item.icon" :size="16" />{{ item.label }}</RouterLink></nav>
        <p v-if="route.path === '/rag/chat'" class="assistant-scope-note"><strong>AI 助手</strong><span>用于通用问答与知识检索；具体事件的诊断、审批和执行记录保存在事件工作区。</span><RouterLink to="/tickets">进入事件处置</RouterLink></p>
        <RouterView v-slot="{ Component }"><Transition name="page" mode="out-in"><component :is="Component" :key="route.path" /></Transition></RouterView>
      </div>
    </main>
    <GlobalApprovalInbox />
  </div>
</template>
<style scoped>
.module-secondary-navigation { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; padding: 8px; border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); background: var(--oa-bg-surface); }
.module-secondary-navigation a { display: inline-flex; align-items: center; gap: 8px; min-height: 36px; padding: 6px 12px; border-radius: var(--oa-radius-control); color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.module-secondary-navigation a[aria-current='page'] { color: var(--oa-primary); background: var(--oa-primary-soft); font-weight: 500; }
.assistant-scope-note { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; margin: 0 0 18px; padding: 14px 18px; border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); background: var(--oa-bg-surface); color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.assistant-scope-note strong, .assistant-scope-note a { color: var(--oa-primary); }
.assistant-scope-note a { margin-left: auto; white-space: nowrap; }
</style>
