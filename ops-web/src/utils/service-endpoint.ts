/** Classify deployment addresses without presenting a visitor's localhost as a service URL. */
export function displayEndpoint(value: unknown): string {
  const raw = String(value || '').trim();
  if (!raw) return '未登记访问地址';
  try {
    const url = new URL(raw);
    if (!['http:', 'https:'].includes(url.protocol)) return '内部连接地址';
    const host = url.hostname.toLowerCase();
    if (host === 'localhost' || host === '[::1]' || host.startsWith('127.')) return '仅内网访问 · 本地登记地址';
    if (!host.includes('.') || /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host)) return '仅部署内网访问';
    return url.origin + url.pathname;
  } catch { return '内部连接地址'; }
}
