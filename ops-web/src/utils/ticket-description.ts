export interface TicketDescriptionParts {
  text: string;
  alertName: string;
  affectedService: string;
  summary: string;
  detail: string;
  metadata: Record<string, string>;
}

export function parseTicketDescription(value: unknown): TicketDescriptionParts {
  const raw = String(value || "").trim();
  const lines = raw.split(/\r?\n/);
  const metadata: Record<string, string> = {};
  const bodyLines: string[] = [];

  for (const line of lines) {
    const match = line.trim().match(/^标签[：:]\s*(\{.*\})\s*$/);
    if (!match) {
      bodyLines.push(line);
      continue;
    }
    try {
      const parsed = JSON.parse(match[1]) as Record<string, unknown>;
      for (const [key, item] of Object.entries(parsed)) metadata[key] = String(item);
    } catch {
      bodyLines.push(line);
    }
  }

  const body = bodyLines.join("\n").trim();
  const fields: Record<string, string> = {};
  const marker = /(告警名称|受影响服务|摘要|详情)\s*[：:]\s*([\s\S]*?)(?=(?:\s+|\n)(?:告警名称|受影响服务|摘要|详情)\s*[：:]|$)/g;
  let match: RegExpExecArray | null;
  while ((match = marker.exec(body))) fields[match[1]] = match[2].trim();

  const residual = body.replace(marker, "").trim();
  const summary = fields["摘要"] || "";
  const detail = fields["详情"] || residual;
  const text = detail || summary || body || "暂无问题描述";

  return {
    text,
    alertName: fields["告警名称"] || metadata.alertname || "",
    affectedService: fields["受影响服务"] || metadata.service || "",
    summary,
    detail,
    metadata,
  };
}
