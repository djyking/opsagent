import { request } from './http';

export interface PublicCase {
  id: string; title: string; scenarioCode: string; targetCode: string; environment: string;
  recordedAt: string; summary: string; result: string; confirmation: string; source: string;
  evidence: string[]; steps: string[]; limitation: string;
}
export const publicCasesApi = {
  list: () => request<PublicCase[]>({ url: '/api/automation/public-cases' }),
};
