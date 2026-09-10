<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppSidebar from '@/components/AppSidebar.vue';
import GlobalTopbar from '@/components/GlobalTopbar.vue';
import GlobalApprovalInbox from '@/components/automation/GlobalApprovalInbox.vue';
import AiAssistantOrb from '@/components/ai/AiAssistantOrb.vue';
import AiAssistantDock from '@/components/ai/AiAssistantDock.vue';
import { ArrowLeft } from '@lucide/vue';
import { useAiAssistantStore } from '@/stores/ai-assistant';
import { contextFromRoute } from '@/utils/ai-context';
import { parentLocation } from '@/utils/route-navigation';
import { useApprovalInboxStore } from '@/stores/approval-inbox';
import { useAuthStore } from '@/stores/auth';
import { eventNavigation, knowledgeNavigation, managementNavigation } from '@/data/navigation';
import BaseModal from '@/components/BaseModal.vue';
import { authApi } from '@/api/modules';
const auth = useAuthStore();
const approvalInbox = useApprovalInboxStore();
const assistant = useAiAssistantStore();
const router = useRouter();
const route = useRoute();
const pageAvailable = computed(() => route.name !== 'ticket-detail' || !!auth.user);
const pageKey = computed(() => route.name === 'ticket-detail'
  ? JSON.stringify([route.path, auth.identity, auth.user?.userId]) : route.path);
const mobileOpen = ref(false);
const mobileQuery = window.matchMedia('(max-width: 900px)');
function updateMobile() { if (!mobileQuery.matches) mobileOpen.value = false; }
onMounted(() => { mobileQuery.addEventListener('change', updateMobile); approvalInbox.start(); });
onBeforeUnmount(() => { mobileQuery.removeEventListener('change', updateMobile); approvalInbox.stop(); });
const contextStorageKey = 'opsagent-context-collapsed';
const collapsed = ref(localStorage.getItem(contextStorageKey) === 'true');
const layoutVariant = computed(() => String(route.meta.layoutVariant || 'standard'));
const secondaryNavigation = computed(() => route.meta.navKey === 'events' && route.path !== '/tickets' && route.name !== 'ticket-detail'
  ? eventNavigation : route.meta.navKey === 'knowledge' && route.path !== '/knowledge'
    ? knowledgeNavigation.filter(item => !item.admin || auth.isAdmin)
    : managementNavigation.some(item => item.to === route.path) && auth.isAdmin && !auth.isDemo ? managementNavigation : []);
const parent = computed(() => parentLocation(route));
const contentClass = computed(() => ({
  'content-notifications': layoutVariant.value === 'feed',
  'content-workspace': layoutVariant.value === 'focus',
  'content-detail': layoutVariant.value === 'detail',
  ['layout-' + layoutVariant.value]: true,
}));
watch(() => route.fullPath, () => { mobileOpen.value = false; });
watch(() => route.fullPath, () => { if (route.path !== '/rag/chat') assistant.setContext(contextFromRoute(route)); }, { immediate: true });
const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || 'O');
const username = computed(() => auth.user?.displayName || auth.user?.username || 'OpsAgent 用户');
const roleLabel = computed(() => auth.isAdmin ? '管理员' : auth.isOps ? '运维人员' : auth.isDemo ? '体验访客' : '用户');
function toggleContext() { collapsed.value = !collapsed.value; localStorage.setItem(contextStorageKey, String(collapsed.value)); }
const leaving = ref<'logout' | 'end' | null>(null);
const ending = ref(false);
const exitError = ref('');
const leaveIdentity = ref<string | null>(null);
watch(leaving, value => { if (value) leaveIdentity.value = auth.identity; }, { flush: 'sync' });
watch(() => auth.identity, () => { leaving.value = null; exitError.value = ''; }, { flush: 'sync' });
function logout() {
  if (auth.isDemo) { leaving.value = 'logout'; return; }
  auth.logout(); void router.push('/login');
}
async function confirmLeave() {
  if (ending.value) return;
  const expectedIdentity = leaveIdentity.value;
  if (!expectedIdentity || auth.identity !== expectedIdentity) { leaving.value = null; return; }
  ending.value = true; exitError.value = '';
  try {
    if (leaving.value === 'end') await authApi.endExperience(expectedIdentity);
    if (auth.identity !== expectedIdentity) return;
    auth.logout(); leaving.value = null; await router.push('/login');
  } catch (cause) { if (auth.identity === expectedIdentity) exitError.value = cause instanceof Error ? cause.message : '操作失败，请重试'; }
  finally { ending.value = false; }
}
</script>
<template>
  <div class="app-shell" :class="{ 'navigation-collapsed': collapsed }">
    <AppSidebar :collapsed="collapsed" :mobile-open="mobileOpen" :is-admin="auth.isAdmin" :initials="initials" :username="username" :role-label="roleLabel" @toggle="toggleContext" @close="mobileOpen = false" @logout="logout" />
    <button v-if="mobileOpen" class="navigation-mask" aria-label="关闭导航" @click="mobileOpen = false" />
    <main class="main-area" :inert="mobileOpen || undefined">
      <GlobalTopbar :is-admin="auth.isAdmin" :mobile-open="mobileOpen" @menu="mobileOpen = true" />
      <div v-if="auth.isDemo" class="visitor-notice">访客体验 · 同浏览器保留 24 小时，每 30 分钟通过验证码续入。可完成本人的隔离演练。
        <button type="button" class="visitor-end" @click="leaving = 'end'">结束本次体验</button>
      </div>
      <div class="page-content" :class="contentClass">
        <RouterLink v-if="parent" class="module-parent-link" :to="parent"><ArrowLeft :size="15" />返回{{ route.meta.parentTitle }}</RouterLink>
        <nav v-if="secondaryNavigation.length" class="module-secondary-navigation" aria-label="模块二级导航"><RouterLink v-for="item in secondaryNavigation" :key="item.to" :to="item.to" :aria-current="route.path === item.to ? 'page' : undefined"><component :is="item.icon" :size="16" />{{ item.label }}</RouterLink></nav>
        <p v-if="route.path === '/rag/chat'" class="assistant-scope-note"><strong>AI 助手</strong><span>用于通用问答与知识检索；具体事件的诊断、审批和执行记录保存在事件工作区。</span><RouterLink to="/tickets">进入事件处置</RouterLink></p>
        <RouterView v-slot="{ Component }"><component :is="Component" v-if="pageAvailable" :key="pageKey" /></RouterView>
      </div>
    </main>
    <GlobalApprovalInbox />
    <BaseModal v-if="leaving" :title="leaving === 'end' ? '结束本次体验' : '暂时离开工作台'" @close="!ending && (leaving = null)">
      <p v-if="leaving === 'logout'">退出不会停止已发起的演练，也不会自动恢复故障。同一浏览器在体验期内通过验证码重新进入，可继续查看本人的记录。</p>
      <p v-else>结束后会撤销本次体验的操作权限，重新进入将创建新身份，无法继续操作原记录。正在进行的故障仍需恢复；到期保护恢复独立执行，管理员可以接管。</p>
      <p v-if="approvalInbox.count">你还有 {{ approvalInbox.count }} 项待审批，请先处理或稍后回来继续。</p>
      <RouterLink to="/automation?tab=experience" @click="leaving = null">先查看我的演练与故障状态</RouterLink>
      <p v-if="exitError" role="alert">{{ exitError }}</p>
      <template #footer><button class="button" :disabled="ending" @click="leaving = null">继续体验</button><button class="button primary" :disabled="ending" @click="confirmLeave">{{ ending ? '正在处理…' : leaving === 'end' ? '确认结束体验' : '保留体验并退出' }}</button></template>
    </BaseModal>
    <template v-if="!mobileOpen && !approvalInbox.open && route.path !== '/observability/wallboard'"><AiAssistantOrb v-if="route.path !== '/rag/chat'" /><AiAssistantDock /></template>
  </div>
</template>
<style scoped>
.module-parent-link { display: inline-flex; align-items: center; gap: 7px; margin-bottom: 14px; color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.module-parent-link:hover { color: var(--oa-primary); }
.module-parent-link:focus-visible { outline: 2px solid var(--oa-primary); outline-offset: 4px; }
.page-content { padding-bottom: 92px; }
.module-secondary-navigation { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; padding: 8px; border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); background: var(--oa-bg-surface); }
.module-secondary-navigation a { display: inline-flex; align-items: center; gap: 8px; min-height: 36px; padding: 6px 12px; border-radius: var(--oa-radius-control); color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.module-secondary-navigation a[aria-current='page'] { color: var(--oa-primary); background: var(--oa-primary-soft); font-weight: 500; }
.assistant-scope-note { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; margin: 0 0 18px; padding: 14px 18px; border: 1px solid var(--oa-border-subtle); border-radius: var(--oa-radius-panel); background: var(--oa-bg-surface); color: var(--oa-text-secondary); font-size: var(--oa-font-size-sm); }
.assistant-scope-note strong, .assistant-scope-note a { color: var(--oa-primary); }
.assistant-scope-note a { margin-left: auto; white-space: nowrap; }
.visitor-end { margin-left: 12px; padding: 0; border: 0; background: transparent; color: var(--oa-primary); cursor: pointer; text-decoration: underline; }
</style>
