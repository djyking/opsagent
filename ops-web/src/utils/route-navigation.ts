import type { RouteLocationNormalizedLoaded, RouteMeta } from 'vue-router';

export const modules = {
  dashboard: { title: '运行总览', group: '核心工作', path: '/dashboard', routeName: 'dashboard' },
  events: { title: '事件处置', group: '核心工作', path: '/tickets', routeName: 'tickets' },
  automation: { title: '自动化中心', group: '核心工作', path: '/automation', routeName: 'automation' },
  observability: { title: '服务与观测', group: '支撑能力', path: '/observability/topology', routeName: 'observability-topology' },
  knowledge: { title: '知识与经验', group: '支撑能力', path: '/knowledge', routeName: 'knowledge' },
  assistant: { title: 'AI 助手', group: '全局工具', path: '/rag/chat', routeName: 'rag-chat' },
  management: { title: '系统管理', group: '系统管理', path: '/admin', routeName: 'admin' },
} as const;
export type NavKey = keyof typeof modules;
declare module 'vue-router' {
  interface RouteMeta {
    title?: string; navKey?: NavKey; navGroup?: string; parentModule?: NavKey;
    parentRouteName?: string; parentTitle?: string; parentPath?: string;
  }
}
export function pageMeta(navKey: NavKey, title: string, layoutVariant: string, child = false): RouteMeta {
  const module = modules[navKey];
  return { title, navKey, navGroup: module.group, parentModule: navKey, layoutVariant,
    ...(child ? { parentRouteName: module.routeName, parentTitle: module.title, parentPath: module.path } : {}) };
}
type RouteLike = Pick<RouteLocationNormalizedLoaded, 'path' | 'meta' | 'query'>;
export function parentLocation(route: RouteLike) {
  if (!route.meta.parentRouteName || route.meta.parentPath === route.path) return undefined;
  const query: Record<string, string> = {};
  // Retain only service scope; child filters and actions must not leak to the parent page.
  for (const key of ['ciCode', 'environment', 'timeRange']) {
    const value = route.query[key]; if (typeof value === 'string') query[key] = value;
  }
  return { name: route.meta.parentRouteName, query };
}
export function legacyObservabilityLocation(query: Record<string, unknown>, hash = '') {
  const tab = typeof query.tab === 'string' ? query.tab : '';
  const destination = tab === 'workflows' || tab === 'inspection' ? 'inspections'
    : tab === 'governance' ? 'traffic' : tab === 'catalog' ? 'catalog' : 'topology';
  const { tab: _tab, ...preserved } = query;
  return { path: `/observability/${destination}`, query: preserved as RouteLocationNormalizedLoaded['query'], hash };
}
