import type { Component } from 'vue';
import { Activity, Bell, BookCheck, BookOpen, Bot, CalendarClock, DatabaseZap, LayoutDashboard, MessageSquareText, Network, Palette, ShieldCheck, Siren, TicketCheck, TimerReset } from '@lucide/vue';
export interface NavigationItem { to: string; label: string; icon: Component; admin?: boolean }
export interface NavigationGroup { label: string; items: NavigationItem[] }
export const navigationGroups: NavigationGroup[] = [
  { label: '工作台', items: [
    { to: '/dashboard', label: '运行总览', icon: LayoutDashboard },
    { to: '/system/monitor', label: '系统监控', icon: Activity },
  ] },
  { label: '运维协作', items: [
    { to: '/tickets', label: '工单中心', icon: TicketCheck },
    { to: '/itsm/alerts', label: '活动告警', icon: Siren, admin: true },
    { to: '/itsm/sla', label: 'SLA 看板', icon: TimerReset },
    { to: '/itsm/oncall', label: '值班排班', icon: CalendarClock },
    { to: '/itsm/cmdb', label: '服务目录', icon: Network },
  ] },
  { label: 'AI 与知识', items: [
    { to: '/ai', label: '能力中心', icon: Bot },
    { to: '/rag/chat', label: '智能问答', icon: MessageSquareText },
    { to: '/knowledge', label: '知识库', icon: BookOpen },
    { to: '/knowledge/review', label: '知识审核', icon: BookCheck, admin: true },
    { to: '/knowledge/index-admin', label: '索引管理', icon: DatabaseZap, admin: true },
  ] },
  { label: '系统管理', items: [
    { to: '/notifications', label: '通知中心', icon: Bell, admin: true },
    { to: '/admin', label: '操作审计', icon: ShieldCheck, admin: true },
  ] },
];
export function navigationFor(path: string) {
  const normalized = path.startsWith('/tickets/') ? '/tickets' : path;
  for (const group of navigationGroups) {
    const item = group.items.find(item => item.to === normalized);
    if (item) return { ...item, group: group.label };
  }
  return { to: path, label: 'UI 基础', icon: Palette, group: '设计规范' };
}
