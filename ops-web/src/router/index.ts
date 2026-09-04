import { createRouter, createWebHistory } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/LoginView.vue"),
      meta: { public: true, layoutVariant: "auth", contextNavMode: "none" },
    },
    {
      path: "/register",
      name: "register",
      component: () => import("@/views/RegisterView.vue"),
      meta: { public: true, layoutVariant: "auth", contextNavMode: "none" },
    },
    {
      path: "/",
      component: () => import("@/layouts/AppLayout.vue"),
      children: [
        { path: "", redirect: "/dashboard" },
        {
          path: "dashboard",
          name: "dashboard",
          component: () => import("@/views/DashboardView.vue"),
          meta: { layoutVariant: "standard", contextNavMode: "none" },
        },
        {
          path: "tickets",
          name: "tickets",
          component: () => import("@/views/TicketListView.vue"),
          meta: { layoutVariant: "data", contextNavMode: "visible" },
        },
        {
          path: "tickets/:id",
          name: "ticket-detail",
          component: () => import("@/views/TicketDetailView.vue"),
          meta: { layoutVariant: "detail", contextNavMode: "collapsed-by-default" },
        },
        {
          path: "knowledge",
          name: "knowledge",
          component: () => import("@/views/KnowledgeView.vue"),
          meta: { layoutVariant: "focus", contextNavMode: "visible" },
        },
        {
          path: "rag/chat",
          name: "rag-chat",
          component: () => import("@/views/RagWorkspaceView.vue"),
          meta: { layoutVariant: "focus", contextNavMode: "collapsed-by-default" },
        },
        {
          path: "system/monitor",
          name: "monitor",
          component: () => import("@/views/MonitorView.vue"),
          meta: { layoutVariant: "data", contextNavMode: "none" },
        },
        {
          path: "itsm/cmdb",
          name: "cmdb",
          component: () => import("@/views/CmdbView.vue"),
          meta: { layoutVariant: "focus", contextNavMode: "collapsed-by-default" },
        },
        {
          path: "itsm/oncall",
          name: "oncall",
          component: () => import("@/views/OnCallView.vue"),
          meta: { layoutVariant: "standard", contextNavMode: "visible" },
        },
        {
          path: "itsm/sla",
          name: "sla",
          component: () => import("@/views/SlaView.vue"),
          meta: { layoutVariant: "data", contextNavMode: "visible" },
        },
        {
          path: "itsm/alerts",
          name: "alerts",
          component: () => import("@/views/AlertView.vue"),
          meta: { admin: true, layoutVariant: "data", contextNavMode: "visible" },
        },
        {
          path: "knowledge/review",
          name: "knowledge-review",
          component: () => import("@/views/KnowledgeReviewView.vue"),
          meta: { admin: true, layoutVariant: "data", contextNavMode: "visible" },
        },
        {
          path: "knowledge/index-admin",
          name: "knowledge-index-admin",
          component: () => import("@/views/KnowledgeIndexAdminView.vue"),
          meta: { admin: true, layoutVariant: "standard", contextNavMode: "visible" },
        },
        {
          path: "notifications",
          name: "notifications",
          component: () => import("@/views/NotificationsView.vue"),
          meta: { admin: true, layoutVariant: "feed", contextNavMode: "none" },
        },
        ...(import.meta.env.DEV
          ? [{ path: "dev/ui-foundation", name: "ui-foundation", component: () => import("@/views/UiFoundationView.vue"), meta: { layoutVariant: "standard", contextNavMode: "none" } }]
          : []),
        {
          path: "admin",
          name: "admin",
          component: () => import("@/views/AuditAdminView.vue"),
          meta: { admin: true, layoutVariant: "data", contextNavMode: "none" },
        },
      ],
    },
    { path: "/:pathMatch(.*)*", redirect: "/dashboard" },
  ],
});

router.beforeEach(async (to) => {
  const auth = useAuthStore();
  if (to.meta.public) return auth.isAuthenticated ? "/dashboard" : true;
  if (!auth.isAuthenticated)
    return { name: "login", query: { redirect: to.fullPath } };
  if (!auth.user) {
    try {
      await auth.fetchMe();
    } catch {
      auth.logout();
      return { name: "login" };
    }
  }
  if (to.meta.admin && !auth.isAdmin) return "/dashboard";
  return true;
});

export default router;
