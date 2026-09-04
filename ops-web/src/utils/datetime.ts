const dateTimeFormatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric", month: "2-digit", day: "2-digit",
  hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
});

const shortFormatter = new Intl.DateTimeFormat("zh-CN", {
  month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false,
});

function normalize(value: string | number | Date) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

export function formatDateTime(value: string | number | Date) {
  const date = normalize(value);
  return date ? dateTimeFormatter.format(date).replaceAll("/", "-") : "—";
}

export function formatShortDateTime(value: string | number | Date) {
  const date = normalize(value);
  return date ? shortFormatter.format(date).replaceAll("/", "-") : "—";
}

export function formatRelativeTime(value: string | number | Date, now = Date.now()) {
  const date = normalize(value);
  if (!date) return "—";
  const seconds = Math.round((date.getTime() - now) / 1000);
  const absolute = Math.abs(seconds);
  if (absolute < 60) return seconds <= 0 ? "刚刚" : "片刻后";
  const minutes = Math.round(seconds / 60);
  if (absolute < 3600) return minutes < 0 ? `${Math.abs(minutes)} 分钟前` : `${minutes} 分钟后`;
  const hours = Math.round(minutes / 60);
  if (absolute < 86400) return hours < 0 ? `${Math.abs(hours)} 小时前` : `${hours} 小时后`;
  const days = Math.round(hours / 24);
  if (absolute < 604800) return days < 0 ? `${Math.abs(days)} 天前` : `${days} 天后`;
  return formatShortDateTime(date);
}

export function formatDuration(milliseconds: number) {
  const minutes = Math.max(0, Math.floor(Math.abs(milliseconds) / 60000));
  const hours = Math.floor(minutes / 60);
  return `${hours ? `${hours} 小时 ` : ""}${minutes % 60} 分钟`;
}
