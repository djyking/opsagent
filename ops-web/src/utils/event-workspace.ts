import type { EventWorkspace } from '@/api/event-workspace';

export const eventRunLabels: Record<string, string> = { QUEUED: '准备执行', RUNNING: '执行中', WAITING_APPROVAL: '等待审批',
  WAITING_INPUT: '等待补充信息', PAUSED: '已暂停', COMPLETED: '流程结束', NEEDS_ATTENTION: '需要人工处理',
  CANCELLED: '已取消', EXPIRED: '已到期', REJECTED: '审批拒绝', BUDGET_EXCEEDED: '预算已达上限' };
export function eventRecordAuthor(record: { recordType: string; createBy: number; evidence?: string }) {
  if (record.recordType === 'EVENT_RESULT' && record.createBy === 0 && record.evidence) {
    try {
      const evidence: unknown = JSON.parse(record.evidence);
      if (evidence && typeof evidence === 'object' && !Array.isArray(evidence)
        && 'source' in evidence && evidence.source === 'AI_MACHINE_RESULT') return 'AI 自动登记';
    } catch { /* Other system records retain their original attribution. */ }
  }
  return `用户 #${record.createBy}`;
}
export function recoveryLabel(source: string) {
  return ({ AGENT_TOOL: 'Agent 工具执行', MANUAL: '人工恢复', TTL_GUARD: '到期保护恢复', NOT_APPLIED: '故障未生效' } as Record<string, string>)[source] || '尚无明确恢复来源';
}
export function observationFresh(data?: EventWorkspace, now = Date.now()) {
  if (!data) return false;
  const observed = Date.parse(data.verification.observedAt || '');
  const generated = Date.parse(data.generatedAt);
  return Number.isFinite(observed) && Number.isFinite(generated) && now - observed < 20000
    && observed - now <= 2000 && now - generated < 20000 && generated - now <= 2000;
}
export function definitelyRejected(cause: unknown) {
  const value = cause as { status?: number; code?: number } | null;
  return !!value && ([400, 401, 403, 404, 422].includes(value.status || 0)
    || [40000, 40100, 40300, 40400].includes(value.code || 0));
}
export function currentlyVerified(data?: EventWorkspace, now = Date.now()) {
  const v = data?.verification;
  return observationFresh(data, now) && !!v && v.status === 'RECOVERED' && v.scope === 'CURRENT' && v.incidentMatched === true
    && v.businessHealthy === true && v.alertResolved === true && v.consecutiveSuccesses >= 3;
}
export function eventRetrospective(data: EventWorkspace, title: string) {
  const lines = (items: string[], empty: string) => items.length ? items.map(item => `- ${item}`).join('\n') : `- ${empty}`;
  return `# ${title} · 事件复盘草稿

> 待人工核对与审核。本文由已保存的事件资料整理，不代表新的根因确认。

事件编号：${data.ticketId}
目标服务：${data.targetCode || '未关联'}
生成时间：${data.generatedAt}

## 症状与事实
${lines(data.facts.map(f => `${f.label}：${f.value}（来源：${f.source}；时间：${f.observedAt || '未提供'}；范围：${f.scope}）`), '暂无可读取事实')}

## 诊断判断
${data.diagnosis.summary || '尚无已记录的诊断结论'}
${lines(data.diagnosis.candidateCauses, '候选原因尚未确认')}

## 相关变更
${lines(data.changes.map(c => `${c.summary}（${c.observedAt || '时间未提供'}，${c.status}）`), '暂无可读取的相关变更')}
变更时间接近故障不等于已证明因果关系。

## 处置运行
${lines(data.runs.map(r => `${r.id}：${eventRunLabels[r.status] || r.status}；${r.message || '详见对应执行记录'}`), '暂无当前账号可读取的运行')}

## 恢复验证
- 判定：${data.verification.label}
- 证据范围：${data.verification.scope === 'CURRENT' ? '当前事件观测' : data.verification.scope === 'HISTORICAL' ? '历史恢复记录，不代表当前健康' : '尚无验证证据'}
- 恢复来源：${recoveryLabel(data.verification.source)}
- 验证时间：${data.verification.observedAt || '未提供'}

## 证据缺口与适用条件
${lines([...new Set([...data.gaps.map(g => g.message), ...data.diagnosis.evidenceGaps])], '请补充适用版本、环境差异和人工核对意见')}
`;
}
