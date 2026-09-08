import { request } from './http';
import type { PageResponse, Ticket } from '@/types/api';
export interface QueueTicket extends Ticket {
  currentStage?: string;
  assigneeName?: string;
  sla?: { applicable: boolean; responseDeadline?: string; resolutionDeadline?: string; responseStatus?: string; resolutionStatus?: string; paused?: boolean; breached?: boolean; policyName?: string };
}
export interface QueueSummary { counts: { total: number; open: number; handling: number; verifying: number; readyToClose: number; closed: number; archived: number }; assignees: { id: number; name: string }[]; services: string[]; checkedAt: string }
export const eventQueueApi = {
  page: (params: Record<string, unknown>) => request<PageResponse<QueueTicket>>({ url: '/api/tickets/queue', params }),
  summary: (params: Record<string, unknown>) => request<QueueSummary>({ url: '/api/tickets/queue/summary', params }),
};
