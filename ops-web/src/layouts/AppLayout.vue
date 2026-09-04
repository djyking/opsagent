<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Bell, BookCheck, BookOpen, Bot, CalendarClock, DatabaseZap, Gauge, LayoutDashboard, Network, ShieldCheck, Siren, TicketCheck, TimerReset } from "@lucide/vue";
import AppRail from "@/components/AppRail.vue";
import ContextSidebar from "@/components/ContextSidebar.vue";
import GlobalTopbar from "@/components/GlobalTopbar.vue";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const mobileOpen = ref(false);
const collapsed = ref(false);

const activeDomain = computed(() => {
  const name = String(route.name || "");
  if (name === "dashboard" || name === "ui-foundation") return "overview";
  if (["tickets", "ticket-detail", "cmdb", "oncall", "sla", "alerts"].includes(name)) return "operations";
  if (name === "monitor") return "observability";
  if (["knowledge", "rag-chat", "knowledge-review", "knowledge-index-admin"].includes(name)) return "ai";
  if (name === "notifications") return "notifications";
  return "system";
});

const domainConfig = computed(() => {
  const configs = {
    overview: { title: "总览", items: [{ to: "/dashboard", label: "运行总览", icon: LayoutDashboard }] },
    operations: { title: "运维", items: [
      { to: "/tickets", label: "工单中心", icon: TicketCheck },
      ...(auth.isAdmin ? [{ to: "/itsm/alerts", label: "活动告警", icon: Siren }] : []),
      { to: "/itsm/sla", label: "SLA 看板", icon: TimerReset },
      { to: "/itsm/oncall", label: "值班排班", icon: CalendarClock },
      { to: "/itsm/cmdb", label: "服务目录", icon: Network },
    ] },
    observability: { title: "可观测", items: [{ to: "/system/monitor", label: "系统监控", icon: Gauge }] },
    ai: { title: "AI 智能", items: [
      { to: "/rag/chat", label: "智能问答", icon: Bot },
      { to: "/knowledge", label: "知识库", icon: BookOpen },
      ...(auth.isAdmin ? [{ to: "/knowledge/review", label: "知识审核", icon: BookCheck }, { to: "/knowledge/index-admin", label: "索引管理", icon: DatabaseZap }] : []),
    ] },
    notifications: { title: "通知", items: [{ to: "/notifications", label: "通知中心", icon: Bell }] },
    system: { title: "系统", items: [{ to: "/admin", label: "操作审计", icon: ShieldCheck }] },
  };
  return configs[activeDomain.value as keyof typeof configs] || configs.overview;
});

const contextNavMode = computed(() => String(route.meta.contextNavMode || (domainConfig.value.items.length > 1 ? "visible" : "none")));
const layoutVariant = computed(() => String(route.meta.layoutVariant || "standard"));
const showContextSidebar = computed(() => contextNavMode.value !== "none");
const effectiveCollapsed = computed(() => showContextSidebar.value && collapsed.value);
const contextStorageKey = computed(() => "opsagent-context-" + String(route.name || "default"));
const contentClass = computed(() => ({
  "content-centered": !showContextSidebar.value && layoutVariant.value === "standard",
  "content-notifications": layoutVariant.value === "feed",
  "content-monitor": route.name === "monitor",
  "content-workspace": layoutVariant.value === "focus",
  "content-detail": layoutVariant.value === "detail",
  ["layout-" + layoutVariant.value]: true,
}));
watch(
  [() => route.name, contextNavMode],
  () => {
    if (!showContextSidebar.value) {
      collapsed.value = false;
      return;
    }
    const saved = localStorage.getItem(contextStorageKey.value);
    collapsed.value = saved === null ? contextNavMode.value === "collapsed-by-default" : saved === "true";
  },
  { immediate: true },
);
const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || "O");
const username = computed(() => auth.user?.displayName || auth.user?.username || "OpsAgent 用户");
const roleLabel = computed(() => auth.isAdmin ? "管理员" : auth.isOps ? "运维人员" : "用户");

function selectDomain(domain: string) {
  const targets: Record<string, string> = { overview: "/dashboard", operations: "/tickets", observability: "/system/monitor", ai: "/rag/chat", notifications: "/notifications", system: "/admin" };
  mobileOpen.value = false;
  router.push(targets[domain] || "/dashboard");
}
function toggleContext() {
  if (!showContextSidebar.value) return;
  collapsed.value = !collapsed.value;
  localStorage.setItem(contextStorageKey.value, String(collapsed.value));
}
function logout() { auth.logout(); router.push("/login"); }
</script>

<template>
  <div class="app-shell" :class="{ 'has-context': showContextSidebar, 'without-context': !showContextSidebar, 'context-is-collapsed': effectiveCollapsed }">
    <AppRail :active-domain="activeDomain" :is-admin="auth.isAdmin" :initials="initials" :username="username" :role-label="roleLabel" @select="selectDomain" @logout="logout" />
    <ContextSidebar :title="domainConfig.title" :items="domainConfig.items" :collapsed="effectiveCollapsed" :single-entry="!showContextSidebar" :mobile-open="mobileOpen" :is-admin="auth.isAdmin" @toggle="toggleContext" @close="mobileOpen = false" />
    <button v-if="mobileOpen" class="context-mask" aria-label="关闭导航" @click="mobileOpen = false" />
    <main class="main-area">
      <GlobalTopbar :is-admin="auth.isAdmin" @menu="mobileOpen = true" />
      <div class="page-content" :class="contentClass"><RouterView /></div>
    </main>
  </div>
</template>