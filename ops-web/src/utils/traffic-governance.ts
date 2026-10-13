export function trafficNumber(value: number | null | undefined, estimate = false): string {
  if (value == null || !Number.isFinite(value) || value < 0) return '—';
  if (value === 0) return '0';
  const threshold = estimate ? 0.1 : 0.01;
  if (value < threshold) return `<${threshold}`;
  const formatted = value.toLocaleString('zh-CN', { maximumFractionDigits: estimate ? 1 : 2 });
  return estimate ? `约 ${formatted}` : formatted;
}

export function trafficState(state: string): string {
  return ({ AVAILABLE: '采样正常', PARTIAL: '部分可读', NO_SAMPLES: '暂无有效样本', UNAVAILABLE: '暂不可读' } as Record<string, string>)[state] || '待核对';
}
