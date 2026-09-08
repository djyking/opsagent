import { Bot, BrainCircuit, BookOpen, Globe, ShieldCheck, TicketCheck, BellRing, Package } from '@lucide/vue';
import { ciType } from '@/components/cmdb/topology';
import type { ServiceNode } from '@/api/observability';
export function serviceIcon(node: Pick<ServiceNode, 'ciCode' | 'ciType'>) {
  const code = node.ciCode.toLowerCase();
  if (/gateway/.test(code)) return Globe;
  if (/auth-service/.test(code)) return ShieldCheck;
  if (/ticket-service/.test(code)) return TicketCheck;
  if (/knowledge-service/.test(code)) return BookOpen;
  if (/rag-service/.test(code)) return BrainCircuit;
  if (/agent-service/.test(code)) return Bot;
  if (/notification-service/.test(code)) return BellRing;
  if (/order-service/.test(code)) return Package;
  return ciType(node.ciType).icon;
}
