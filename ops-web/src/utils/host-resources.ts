import type { HostResource, HostResourceMetric } from '@/api/host-resources';

export function hostMetricAvailable(metric?: HostResourceMetric, now = Date.now()) {
  const at = Date.parse(metric?.sampledAt || '');
  return metric?.status === 'OBSERVED' && typeof metric.value === 'number' && Number.isFinite(metric.value)
    && Number.isFinite(at) && now - at <= 90_000 && at - now <= 5_000;
}
export function hostMetricValue(metric?: HostResourceMetric, now = Date.now()) {
  if (!hostMetricAvailable(metric, now)) return '—';
  const value = metric!.value!;
  if (metric!.unit === 'percent') return `${value.toFixed(1)}%`;
  if (metric!.unit === 'boolean') return value === 1 ? '已连接' : '未连接';
  if (['bytes', 'bytes/s', 'bits/s'].includes(metric!.unit)) {
    const bit = metric!.unit === 'bits/s';
    const base = bit ? 1000 : 1024;
    const units = bit ? ['bit/s', 'Kbit/s', 'Mbit/s', 'Gbit/s'] : ['B', 'KB', 'MB', 'GB', 'TB'];
    const index = value > 0 ? Math.max(0, Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(base)))) : 0;
    return `${(value / base ** index).toFixed(index ? 1 : 0)} ${units[index]}${metric!.unit === 'bytes/s' ? '/s' : ''}`;
  }
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}
export function hostMetricState(metric?: HostResourceMetric, now = Date.now()) {
  return hostMetricAvailable(metric, now) ? '实时采样' : metric?.status === 'STALE'
    || metric?.status === 'OBSERVED' && !!metric.sampledAt ? '样本已过期' : '尚未取得样本';
}
export function hostSummaryMetrics(host: HostResource, now = Date.now()) {
  const byKey = (key: string) => host.metrics.filter(metric => metric.key === key);
  const disks = byKey('diskUsage');
  const disk = [...disks].filter(metric => hostMetricAvailable(metric, now)).sort((a, b) => b.value! - a.value!)[0] || disks[0];
  const network = byKey('networkReceiveRate')[0] || byKey('networkTransmitRate')[0];
  return [
    { key: 'cpu', label: '主机 CPU', metric: byKey('cpuUsage')[0] },
    { key: 'memory', label: '物理内存', metric: byKey('physicalMemoryUsage')[0] },
    { key: 'disk', label: `磁盘使用${disk?.dimension ? ` · ${disk.dimension}` : ''}`, metric: disk },
    { key: 'network', label: `网络${network?.dimension ? ` · ${network.dimension}` : ''}`, metric: byKey('networkReceiveRate').find(metric => metric.dimension === network?.dimension),
      transmit: byKey('networkTransmitRate').find(metric => metric.dimension === network?.dimension) },
  ];
}
