import type { RunUsage } from '@/api/automation';

export function tokenAmount(value: unknown) {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? value.toLocaleString('zh-CN') : '未知';
}

export function modelUsageSummary(usage?: RunUsage) {
  if (!usage || usage.model.availability !== 'AVAILABLE') return '模型用量暂不可用';
  const model = usage.model;
  const known = `${tokenAmount(model.knownTotalTokens)} Token 已知`;
  if (model.unknownCountIsLowerBound && model.unknownUsageAttempts === 0)
    return `${known} · 未知次数未完整记录`;
  if (model.unknownCountIsLowerBound || (model.unknownUsageAttempts ?? 0) > 0) {
    const count = tokenAmount(model.unknownUsageAttempts);
    return `${known} · ${model.unknownCountIsLowerBound ? '至少 ' : ''}${count} 次未知`;
  }
  if ((model.pendingCalls ?? 0) > 0) return `${known} · ${model.pendingCalls} 次待回执`;
  return model.coverage === 'PARTIAL' ? `${known} · 回执不完整` : known;
}
