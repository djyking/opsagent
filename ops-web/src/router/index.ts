import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { legacyObservabilityLocation, pageMeta } from '@/utils/route-navigation';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, layoutVariant: 'auth' } },
    { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue'), meta: { public: true, layoutVariant: 'auth' } },
    { path: '/', component: () => import('@/layouts/AppLayout.vue'), children: [
      { path: '', redirect: '/dashboard' },
      { path: 'events', redirect: to => ({ path: '/tickets', query: to.query, hash: to.hash }) },
      { path: 'events/:id', redirect: to => ({ path: '/tickets/' + to.params.id, query: to.query, hash: to.hash }) },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: pageMeta('dashboard', '运行总览', 'standard') },
      { path: 'tickets', name: 'tickets', component: () => import('@/views/TicketListView.vue'), meta: pageMeta('events', '事件处置', 'data') },
      { path: 'tickets/:id', name: 'ticket-detail', component: () => import('@/views/TicketDetailView.vue'), meta: pageMeta('events', '事件详情', 'detail', true) },
      { path: 'automation', name: 'automation', component: () => import('@/views/AutomationView.vue'), meta: pageMeta('automation', '自动化中心', 'data') },
      { path: 'ai', name: 'ai-center', redirect: to => ({ path: '/rag/chat', query: to.query, hash: to.hash }) },
      { path: 'rag/chat', name: 'rag-chat', component: () => import('@/views/RagWorkspaceView.vue'), meta: pageMeta('assistant', '智能问答', 'focus') },
      { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: pageMeta('knowledge', '知识与经验', 'focus') },
      { path: 'operations', name: 'monitor', redirect: to => legacyObservabilityLocation(to.query, to.hash) },
      { path: 'system/monitor', redirect: to => legacyObservabilityLocation(to.query, to.hash) },
      { path: 'itsm/cmdb', name: 'cmdb', redirect: to => ({ path: '/observability/catalog', query: to.query, hash: to.hash }) },
      { path: 'configuration', name: 'configuration', redirect: to => ({ path: '/observability/config', query: to.query, hash: to.hash }) },
      { path: 'configurations', redirect: to => ({ path: '/observability/config/managed', query: to.query, hash: to.hash }) },
      { path: 'observability', redirect: to => ({ path: '/observability/topology', query: to.query, hash: to.hash }) },
      { path: 'observability/topology', name: 'observability-topology', component: () => import('@/views/observability/TopologyView.vue'), meta: pageMeta('observability', '拓扑总览', 'data', true) },
      { path: 'observability/catalog', name: 'observability-catalog', component: () => import('@/views/observability/ServiceCatalogView.vue'), meta: pageMeta('observability', '服务目录', 'data', true) },
      { path: 'observability/metrics', name: 'observability-metrics', component: () => import('@/views/OperationsView.vue'), props: { metricsOnly: true }, meta: pageMeta('observability', '指标与采集', 'data', true) },
      { path: 'observability/config', name: 'observability-config', component: () => import('@/views/observability/ConfigCenterView.vue'), meta: pageMeta('observability', '配置中心', 'data', true) },
      { path: 'observability/config/managed', name: 'observability-config-managed', component: () => import('@/views/ConfigurationView.vue'), meta: { ...pageMeta('observability', '受控配置变更', 'data', true), parentRouteName: 'observability-config', parentTitle: '配置中心', parentPath: '/observability/config' } },
      { path: 'observability/traffic', name: 'observability-traffic', component: () => import('@/views/observability/TrafficGovernanceView.vue'), meta: pageMeta('observability', '流量治理', 'data', true) },
      { path: 'observability/inspections', name: 'observability-inspections', component: () => import('@/views/observability/InspectionView.vue'), meta: pageMeta('observability', '持续巡检', 'data', true) },
      { path: 'observability/wallboard', name: 'observability-wallboard', component: () => import('@/views/observability/WallboardView.vue'), meta: pageMeta('observability', '可观测大屏', 'data', true) },
      { path: 'itsm/oncall', name: 'oncall', component: () => import('@/views/OnCallView.vue'), meta: pageMeta('events', '值班排班', 'standard', true) },
      { path: 'itsm/sla', name: 'sla', component: () => import('@/views/SlaView.vue'), meta: pageMeta('events', 'SLA 看板', 'data', true) },
      { path: 'itsm/alerts', name: 'alerts', component: () => import('@/views/AlertView.vue'), meta: pageMeta('events', '原始告警', 'data', true) },
      { path: 'knowledge/review', name: 'knowledge-review', component: () => import('@/views/KnowledgeReviewView.vue'), meta: { ...pageMeta('knowledge', '知识审核', 'data', true), admin: true } },
      { path: 'knowledge/index-admin', name: 'knowledge-index-admin', component: () => import('@/views/KnowledgeIndexAdminView.vue'), meta: { ...pageMeta('knowledge', '索引管理', 'standard', true), admin: true } },
      { path: 'notifications', name: 'notifications', component: () => import('@/views/NotificationsView.vue'), meta: { ...pageMeta('management', '通知记录', 'feed', true), admin: true } },
      { path: 'admin', name: 'admin', component: () => import('@/views/AuditAdminView.vue'), meta: { ...pageMeta('management', '操作审计', 'data'), admin: true } },
      ...(import.meta.env.DEV ? [{ path: 'dev/ui-foundation', name: 'ui-foundation', component: () => import('@/views/UiFoundationView.vue'), meta: { ...pageMeta('management', 'UI 基础', 'standard', true), admin: true } }] : []),
    ] },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
  ],
});

router.beforeEach(async to => {
  const auth = useAuthStore();
  if (to.meta.public) return auth.isAuthenticated ? '/dashboard' : true;
  if (!auth.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } };
  if (!auth.user) {
    try { await auth.fetchMe(); }
    catch { auth.logout(); return { name: 'login' }; }
  }
  if (to.meta.admin && !auth.isAdmin) return '/dashboard';
  return true;
});
export default router;
