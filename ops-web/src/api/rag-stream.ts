import type { AiReference } from "@/types/api";

export interface RagStreamResult {
  answer: string;
  references: AiReference[];
  provider: string;
  model: string;
  latencyMs: number;
}

export interface RagStreamHandlers {
  onToken?: (delta: string) => void | Promise<void>;
  onSources?: (references: AiReference[]) => void;
  onStatus?: (message: string) => void;
}

interface StreamPayload {
  delta?: string;
  answer?: string;
  references?: Record<string, unknown>[];
  provider?: string;
  model?: string;
  latencyMs?: number;
  message?: string;
  phase?: string;
}

const FIRST_TOKEN_TIMEOUT_MS = 30_000;

export async function streamRagAnswer(
  data: { question: string; topK?: number; documentId?: number },
  handlers: RagStreamHandlers = {},
): Promise<RagStreamResult> {
  const controller = new AbortController();
  let receivedToken = false;
  let timeoutTriggered = false;
  const firstTokenTimer = window.setTimeout(() => {
    timeoutTriggered = true;
    controller.abort();
  }, FIRST_TOKEN_TIMEOUT_MS);

  try {
    const baseUrl = String(import.meta.env.VITE_API_BASE_URL || "").replace(
      /\/$/,
      "",
    );
    const token = localStorage.getItem("opsagent_token");
    const response = await fetch(`${baseUrl}/api/rag/stream`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({
        question: data.question,
        topK: data.topK || 5,
        documentId: data.documentId,
      }),
      signal: controller.signal,
    });
    if (!response.ok || !response.body) {
      const body = (await response.json().catch(() => null)) as {
        message?: string;
      } | null;
      throw new Error(body?.message || `问答请求失败（HTTP ${response.status}）`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let result: RagStreamResult | undefined;
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const event = parseEvent(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
        if (!event) continue;
        const payload = event.payload;
        if (event.name === "status") {
          handlers.onStatus?.(
            payload.phase === "generating"
              ? "知识检索已完成，正在等待模型返回首段内容（最长 30 秒）"
              : "正在处理请求",
          );
        } else if (event.name === "token") {
          if (!receivedToken) {
            receivedToken = true;
            window.clearTimeout(firstTokenTimer);
            handlers.onStatus?.("模型正在流式生成回答");
          }
          await handlers.onToken?.(String(payload.delta || ""));
        } else if (event.name === "sources") {
          handlers.onSources?.(normalizeReferences(payload.references));
        } else if (event.name === "done") {
          result = {
            answer: String(payload.answer || ""),
            references: normalizeReferences(payload.references),
            provider: String(payload.provider || "unknown"),
            model: String(payload.model || "unknown"),
            latencyMs: Number(payload.latencyMs || 0),
          };
        } else if (event.name === "error") {
          throw new Error(String(payload.message || "流式问答失败"));
        }
      }
      if (done) break;
    }
    if (!result) throw new Error("流式问答连接提前结束，请重试");
    return result;
  } catch (cause) {
    if (timeoutTriggered) {
      throw new Error("30 秒内未收到模型回答，请稍后重试或联系管理员检查 AI 服务状态");
    }
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw new Error("问答请求已取消");
    }
    throw cause;
  } finally {
    window.clearTimeout(firstTokenTimer);
  }
}

function parseEvent(block: string) {
  let name = "message";
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (line.startsWith("event:")) name = line.slice(6).trim();
    if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
  }
  if (!data.length) return undefined;
  return {
    name,
    payload: JSON.parse(data.join("\n")) as StreamPayload,
  };
}

function normalizeReferences(rows?: Record<string, unknown>[]): AiReference[] {
  return (rows || []).map((row) => ({
    chunkId: Number(row.chunkId),
    documentId: Number(row.documentId),
    chunkIndex: Number(row.chunkIndex),
    documentName: String(row.documentName || `文档 #${row.documentId}`),
    pageNumber: row.page == null ? undefined : Number(row.page),
    relevanceScore: Number(row.score || 0),
  }));
}
