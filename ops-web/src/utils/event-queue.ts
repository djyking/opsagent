import type { QueueTicket } from '@/api/event-queue';
export function queueStage(ticket: QueueTicket) {
  return ticket.currentStage || ticket.eventStage || (ticket.eventClosed ? 'CLOSED' : 'HANDLING');
}
export function queueState(ticket: QueueTicket) {
  const stage = queueStage(ticket);
  if (stage === 'LEGACY_ARCHIVED') return '历史归档';
  if (stage === 'CLOSED') return '已关闭';
  if (stage === 'READY_TO_CLOSE') return '待关闭';
  if (stage === 'VERIFYING') return '待验证';
  return ticket.status === 'CREATED' ? '待处理' : ticket.status === 'SUSPENDED' ? '已挂起' : '处理中';
}
export function queueStageLabel(ticket: QueueTicket) {
  return ({ HANDLING: ticket.status === 'CREATED' ? '等待接单' : '事件处置', VERIFYING: '恢复验证', READY_TO_CLOSE: '等待关闭', CLOSED: '事件关闭', LEGACY_ARCHIVED: '历史归档' } as Record<string, string>)[queueStage(ticket)] || queueStage(ticket);
}
export function queueSla(ticket: QueueTicket, now = Date.now()) {
  const sla = ticket.sla;
  if (!sla) return { label: 'SLA 尚未取得', detail: '', tone: 'muted' };
  if (!sla.applicable) return { label: '无适用规则', detail: '', tone: 'muted' };
  if (sla.paused) return { label: '计时已暂停', detail: sla.policyName || '', tone: 'muted' };
  if (['COMPLETED', 'MET', 'ACHIEVED'].includes(sla.resolutionStatus || '')) return { label: '已完成计时', detail: sla.policyName || '', tone: 'muted' };
  const responsePending = ['PENDING', 'RUNNING', 'BREACHED'].includes(sla.responseStatus || '');
  const deadline = responsePending ? sla.responseDeadline : sla.resolutionDeadline;
  if (!deadline || !Number.isFinite(new Date(deadline).getTime())) return { label: '时限待核对', detail: sla.policyName || '', tone: 'muted' };
  const minutes = Math.ceil((new Date(deadline).getTime() - now) / 60_000);
  return { label: minutes < 0 ? `超时 ${Math.abs(minutes)} 分钟` : `剩余 ${minutes} 分钟`, detail: `${responsePending ? '响应' : '解决'}时限 ${new Date(deadline).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`, tone: minutes < 0 || sla.breached ? 'danger' : 'primary' };
}
