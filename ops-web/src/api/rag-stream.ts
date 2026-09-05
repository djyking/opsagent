import type { AiReference } from "@/types/api";

export interface RagStreamResult {
  answer: string;
  references: AiReference[];
  provider: string;
  model: string;
  latencyMs: number;
  metadata?: { degraded: boolean; degradedReason?: string | null; generationComplete?: boolean; finishReason?: string; continuationCount?: number };
}

export function ragCompletionLabel(result: RagStreamResult): string {
  if (result.metadata?.generationComplete === false) return "回答未完成";
  if (result.provider === "cmdb" && result.metadata?.degradedReason === "CMDB_UNAVAILABLE") return "目录暂不可用";
  if (result.provider === "cmdb") return "查询完成";
  if (result.provider === "disabled") return "仅返回知识检索结果";
  if (result.provider === "none") return "知识依据不足";
  return "回答完成";
}

export function ragIncompleteMessage(result: RagStreamResult): string {
  if (result.metadata?.generationComplete !== false) return "";
  if (result.metadata.finishReason === "length") return "本次回答已达到输出上限，以下内容尚未完整生成。可以继续追问缺少的部分。";
  if (result.metadata.finishReason === "content_filter") return "模型未能完整生成本次回答，请调整问题后重试。";
  return "生成连接中断或未正常结束，已保留收到的内容，请重新提问。";
}

export function ragAnswerLabel(result: RagStreamResult): string {
  if (result.provider === "cmdb" && result.metadata?.degradedReason === "CMDB_UNAVAILABLE") return "服务目录 · 读取失败";
  if (result.provider === "cmdb") return "服务目录 · 实时读取";
  if (result.provider === "disabled") {
    const reason = result.metadata?.degradedReason;
    if (reason === "LLM_DISABLED") return "仅知识检索 · AI 生成未启用";
    if (reason === "LLM_NOT_CONFIGURED") return "仅知识检索 · AI 模型未配置";
    return "仅知识检索 · AI 模型调用失败";
  }
  if (result.provider === "none") return "知识依据不足";
  return `${result.provider}/${result.model}`;
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
  metadata?: RagStreamResult["metadata"];
}

const RETRIEVAL_TIMEOUT_MS = 90_000;
const GENERATION_FIRST_TOKEN_TIMEOUT_MS = 90_000;

export async function streamRagAnswer(
  data: { question: string; topK?: number; documentId?: number; ticketId?: number; conversationId?: string },
  handlers: RagStreamHandlers = {},
  signal?: AbortSignal,
): Promise<RagStreamResult> {
  const controller = new AbortController();
  let receivedToken = false;
  let retrievalOnly = false;
  let generationStarted = false;
  let timeoutStage: "retrieval" | "generation" | undefined;
  let firstContentTimer: number | undefined;
  const clearFirstContentTimer = () => {
    if (firstContentTimer !== undefined) window.clearTimeout(firstContentTimer);
    firstContentTimer = undefined;
  };
  const waitForFirstContent = (stage: "retrieval" | "generation") => {
    clearFirstContentTimer();
    firstContentTimer = window.setTimeout(() => {
      firstContentTimer = undefined;
      if (controller.signal.aborted) return;
      timeoutStage = stage;
      controller.abort();
    }, stage === "retrieval" ? RETRIEVAL_TIMEOUT_MS : GENERATION_FIRST_TOKEN_TIMEOUT_MS);
  };
  const abort = () => {
    clearFirstContentTimer();
    controller.abort();
  };
  signal?.addEventListener("abort", abort, { once: true });
  if (signal?.aborted) controller.abort();
  else waitForFirstContent("retrieval");

  try {
    const baseUrl = String(import.meta.env.VITE_API_BASE_URL || "").replace(
      /\/$/,
      "",
    );
    const token = localStorage.getItem("opsagent_token");
    const endpoint = data.conversationId ? `/api/rag/conversations/${encodeURIComponent(data.conversationId)}/stream` : "/api/rag/stream";
    const response = await fetch(`${baseUrl}${endpoint}`, {
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
        ticketId: data.ticketId,
      }),
      signal: controller.signal,
    });
    if (!response.ok || !response.body || !response.headers.get("Content-Type")?.includes("text/event-stream")) {
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
      buffer += decoder.decode(value, { stream: !done });
      buffer = buffer.replace(/\r\n/g, "\n");
      let boundary = buffer.indexOf("\n\n");
      while (boundary >= 0) {
        const event = parseEvent(buffer.slice(0, boundary));
        buffer = buffer.slice(boundary + 2);
        boundary = buffer.indexOf("\n\n");
        if (!event) continue;
        const payload = event.payload;
        if (event.name === "status") {
          retrievalOnly = payload.phase === "retrieval-only" || payload.phase === "cmdb";
          if (payload.phase === "generating" && !generationStarted && !receivedToken && !controller.signal.aborted) {
            generationStarted = true;
            waitForFirstContent("generation");
          }
          handlers.onStatus?.(
            payload.phase === "cmdb" ? "正在读取服务目录" : payload.phase === "generating"
              ? "知识检索已完成，正在等待模型返回首段内容"
              : retrievalOnly ? "正在返回参考资料" : "正在处理请求",
          );
        } else if (event.name === "token") {
          if (!receivedToken) {
            receivedToken = true;
            clearFirstContentTimer();
            handlers.onStatus?.(retrievalOnly ? "正在返回查询结果" : "正在生成回答");
          }
          await handlers.onToken?.(String(payload.delta || ""));
        } else if (event.name === "sources") {
          handlers.onSources?.(normalizeReferences(payload.references));
        } else if (event.name === "done") {
          clearFirstContentTimer();
          result = {
            answer: String(payload.answer || ""),
            references: normalizeReferences(payload.references),
            provider: String(payload.provider || "unknown"),
            model: String(payload.model || "unknown"),
            latencyMs: Number(payload.latencyMs || 0),
            metadata: payload.metadata,
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
    if (timeoutStage === "retrieval") {
      throw new Error("知识检索或请求准备超过 90 秒，请稍后重试");
    }
    if (timeoutStage === "generation") {
      throw new Error("模型在 90 秒内未返回首段内容，请稍后重试或联系管理员检查 AI 服务状态");
    }
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw new Error("问答请求已取消");
    }
    throw cause;
  } finally {
    clearFirstContentTimer();
    signal?.removeEventListener("abort", abort);
    controller.abort();
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

export function normalizeReferences(rows?: Record<string, unknown>[]): AiReference[] {
  return (rows || []).map((row) => ({
    chunkId: Number(row.chunkId),
    documentId: Number(row.documentId),
    chunkIndex: Number(row.chunkIndex),
    documentName: String(row.documentName || `文档 #${row.documentId}`),
    pageNumber: row.pageStart == null && row.page == null ? undefined : Number(row.pageStart ?? row.page),
    relevanceScore: Number(row.score || 0),
    sourceId: row.sourceId == null ? undefined : String(row.sourceId),
    sourceType: row.sourceType == null ? undefined : String(row.sourceType),
    sourceUrl: row.sourceUrl == null ? undefined : String(row.sourceUrl),
    sourceUpdatedAt: row.sourceUpdatedAt == null ? undefined : String(row.sourceUpdatedAt),
    sourceRetrievedAt: row.sourceRetrievedAt == null ? undefined : String(row.sourceRetrievedAt),
    headingPath: row.headingPath == null ? undefined : String(row.headingPath),
    pageStart: row.pageStart == null ? undefined : Number(row.pageStart),
    pageEnd: row.pageEnd == null ? undefined : Number(row.pageEnd),
    rrfScore: row.rrfScore == null ? undefined : Number(row.rrfScore),
    rerankScore: row.rerankScore == null ? undefined : Number(row.rerankScore),
    retrievalChannels: Array.isArray(row.retrievalChannels) ? row.retrievalChannels.map(String) : [],
    neighbor: Boolean(row.neighbor),
  }));
}
