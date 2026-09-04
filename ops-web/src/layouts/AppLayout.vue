<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Bell,
  BookCheck,
  BookOpen,
  Bot,
  CalendarClock,
  DatabaseZap,
  Gauge,
  LayoutDashboard,
  Network,
  ShieldCheck,
  Siren,
  TicketCheck,
  TimerReset,
} from "@lucide/vue";
import AppRail from "@/components/AppRail.vue";
import ContextSidebar from "@/components/ContextSidebar.vue";
import GlobalTopbar from "@/components/GlobalTopbar.vue";
import { useAuthStore } from "@/stores/auth";

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const mobileOpen = ref(false);
const collapsed = ref(localStorage.getItem("opsagent-context-collapsed") === "true");

const pageTitles: Record<string, string> = {
  dashboard: "运行总览",
  tickets: "工单中心",
  "ticket-detail": "工单详情",
  knowledge: "知识库",
  "rag-chat": "智能问答",
  monitor: "系统监控",
  cmdb: "服务目录与拓扑",
  oncall: "值班排班",
  sla: "SLA 看板",
  alerts: "活动告警",
  "knowledge-review": "知识审核",
  "knowledge-index-admin": "知识索引管理",
  notifications: "通知中心",
  admin: "操作审计",
};

const activeDomain = computed(() => {
  const name = String(route.name || "");
  if (name === "dashboard") return "overview";
  if (["tickets", "ticket-detail", "cmdb", "oncall", "sla", "alerts"].includes(name)) {
    return "operations";
  }
  if (name === "monitor") return "observability";
  if (["knowledge", "rag-chat", "knowledge-review", "knowledge-index-admin"].includes(name)) {
    return "ai";
  }
  if (name === "notifications") return "notifications";
  return "system";
});

const domainConfig = computed(() => {
  const configs = {
    overview: {
      title: "总览",
      items: [{ to: "/dashboard", label: "运行总览", icon: LayoutDashboard }],
    },
    operations: {
      title: "运维",
      items: [
        { to: "/tickets", label: "工单中心", icon: TicketCheck },
        ...(auth.isAdmin ? [{ to: "/itsm/alerts", label: "活动告警", icon: Siren }] : []),
        { to: "/itsm/sla", label: "SLA 看板", icon: TimerReset },
        { to: "/itsm/oncall", label: "值班排班", icon: CalendarClock },
        { to: "/itsm/cmdb", label: "服务目录", icon: Network },
      ],
    },
    observability: {
      title: "可观测",
      items: [{ to: "/system/monitor", label: "系统监控", icon: Gauge }],
    },
    ai: {
      title: "AI 智能",
      items: [
        { to: "/rag/chat", label: "智能问答", icon: Bot },
        { to: "/knowledge", label: "知识库", icon: BookOpen },
        ...(auth.isAdmin
          ? [
              { to: "/knowledge/review", label: "知识审核", icon: BookCheck },
              { to: "/knowledge/index-admin", label: "索引管理", icon: DatabaseZap },
            ]
          : []),
      ],
    },
    notifications: {
      title: "通知",
      items: [{ to: "/notifications", label: "通知中心", icon: Bell }],
    },
    system: {
      title: "系统",
      items: [{ to: "/admin", label: "操作审计", icon: ShieldCheck }],
    },
  };
  return configs[activeDomain.value as keyof typeof configs] || configs.overview;
});

const pageTitle = computed(() => pageTitles[String(route.name)] || "OpsAgent");
const initials = computed(
  () => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || "O",
);

function selectDomain(domain: string) {
  const targets: Record<string, string> = {
    overview: "/dashboard",
    operations: "/tickets",
    observability: "/system/monitor",
    ai: "/rag/chat",
    notifications: "/notifications",
    system: "/admin",
  };
  mobileOpen.value = false;
  router.push(targets[domain] || "/dashboard");
}

function toggleContext() {
  collapsed.value = !collapsed.value;
  localStorage.setItem("opsagent-context-collapsed", String(collapsed.value));
}

function logout() {
  auth.logout();
  router.push("/login");
}
</script>

<template>
  <div class="app-shell" :class="{ 'context-is-collapsed': collapsed }">
    <AppRail
      :active-domain="activeDomain"
      :is-admin="auth.isAdmin"
      :initials="initials"
      @select="selectDomain"
      @logout="logout"
    />
    <ContextSidebar
      :title="domainConfig.title"
      :items="domainConfig.items"
      :collapsed="collapsed"
      :mobile-open="mobileOpen"
      :is-admin="auth.isAdmin"
      @toggle="toggleContext"
      @close="mobileOpen = false"
    />
    <button
      v-if="mobileOpen"
      class="context-mask"
      aria-label="关闭导航"
      @click="mobileOpen = false"
    />
    <main class="main-area">
      <GlobalTopbar
        :domain-title="domainConfig.title"
        :page-title="pageTitle"
        :is-admin="auth.isAdmin"
        @menu="mobileOpen = true"
      />
      <div class="page-content"><RouterView /></div>
    </main>
  </div>
</template>
