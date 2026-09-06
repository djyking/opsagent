import type { Component } from 'vue';
import { Activity, Bell, BookCheck, BookOpen, CalendarClock, DatabaseZap, GitBranch, LayoutDashboard, MessageSquareText, Palette, Settings2, ShieldCheck, Siren, TicketCheck, TimerReset } from '@lucide/vue';
export interface NavigationItem { to: string; label: string; icon: Component; admin?: boolean }
export interface NavigationGroup { label: string; items: NavigationItem[] }
export const navigationGroups: NavigationGroup[] = [
  { label: '核心工作', items: [
    { to: '/dashboard', label: '运行总览', icon: LayoutDashboard },
    { to: '/tickets', label: '事件处置', icon: TicketCheck },
    { to: '/automation', label: '自动化中心', icon: GitBranch },
  ] },
  { label: '支撑能力', items: [
    { to: '/operations', label: '服务与观测', icon: Activity },
    { to: '/knowledge', label: '知识与经验', icon: BookOpen },
  ] },
];
export const eventNavigation: NavigationItem[] = [
  { to: '/tickets', label: '事件队列', icon: TicketCheck },
  { to: '/itsm/alerts', label: '原始告警', icon: Siren },
  { to: '/itsm/sla', label: 'SLA 看板', icon: TimerReset },
  { to: '/itsm/oncall', label: '值班排班', icon: CalendarClock },
];
export const managementNavigation: NavigationItem[] = [
  { to: '/notifications', label: '通知记录', icon: Bell, admin: true },
  { to: '/admin', label: '操作审计', icon: ShieldCheck, admin: true },
];
const supportingPages = [
  ...eventNavigation.slice(1).map(item => ({ ...item, group: '事件处置', primaryTo: '/tickets' })),
  { to: '/rag/chat', label: 'AI 助手', icon: MessageSquareText, group: '全局工具', primaryTo: '' },
  { to: '/knowledge/review', label: '知识审核', icon: BookCheck, group: '知识与经验', primaryTo: '/knowledge' },
  { to: '/knowledge/index-admin', label: '索引管理', icon: DatabaseZap, group: '知识与经验', primaryTo: '/knowledge' },
  { to: '/configuration', label: '配置中心', icon: Settings2, group: '服务与观测', primaryTo: '/operations' },
  ...managementNavigation.map(item => ({ ...item, group: '系统管理', primaryTo: '' })),
];
export function navigationFor(path: string) {
  const normalized = path.startsWith('/tickets/') ? '/tickets' : path;
  for (const group of navigationGroups) {
    const item = group.items.find(item => item.to === normalized);
    if (item) return { ...item, group: group.label, primaryTo: item.to };
  }
  return supportingPages.find(item => item.to === normalized)
    || { to: path, label: 'UI 基础', icon: Palette, group: '设计规范', primaryTo: '' };
}
