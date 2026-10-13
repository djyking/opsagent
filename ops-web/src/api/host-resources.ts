import { request } from './http';

export interface HostResourceMetric {
  key: string; label: string; dimension: string; unit: string; value: number | null;
  sampledAt: string | null; status: 'OBSERVED' | 'STALE' | 'MISSING';
  points: { at: string; value: number }[];
}
export interface HostResource {
  ciCode: string; ciName: string; environment: string; job: string;
  status: 'READY' | 'PARTIAL' | 'UNKNOWN'; observedAt: string | null; source: string;
  services: { ciCode: string; ciName: string; environment: string }[];
  metrics: HostResourceMetric[];
}
export interface HostResourceSnapshot {
  capturedAt: string; scope: 'WINDOWS_HOST' | 'LINUX_HOST' | 'HOST_RESOURCE'; message: string; hosts: HostResource[];
}
export const hostResourcesApi = {
  read: (ciCode?: string, windowMinutes = 60) => request<HostResourceSnapshot>({
    url: '/api/platform/operations/host-resources', params: { ciCode: ciCode || undefined, windowMinutes },
  }),
};
