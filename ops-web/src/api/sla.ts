import { request } from "@/api/http";
import type { PageResponse } from "@/types/api";

export type SlaViewFilter = "all" | "risk" | "breached";
export interface SlaRow {
  id: number;
  ticketId: number;
  ticketNo: string;
  title: string;
  priority: string;
  status: string;
  affectedCiCode: string | null;
  responseDeadline: string;
  resolutionDeadline: string;
  responseStatus: string;
  resolutionStatus: string;
  escalationLevel: number;
}
export interface SlaSummary {
  counts: {
    total: number;
    running: number;
    risk: number;
    dashboardRisk: number;
    breached: number;
    completed: number;
  };
  services: string[];
  checkedAt: string;
}
export interface SlaPageQuery {
  pageNum: number;
  pageSize: number;
  view: SlaViewFilter;
  priority?: string;
  service?: string;
  keyword?: string;
}
export const slaApi = {
  page: (params: SlaPageQuery) => request<PageResponse<SlaRow>>({ url: "/api/tickets/sla/page", params }),
  summary: () => request<SlaSummary>({ url: "/api/tickets/sla/summary" }),
};
