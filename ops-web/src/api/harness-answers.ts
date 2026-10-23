import { request } from "./http";
import { readSession } from "./session";

interface PendingAnswer {
  owner: "LEGACY" | "HARNESS";
  routeId: string;
  status: string;
  statusUrl: string;
}
interface Attempt { key: string; routeId?: string }

/**
 * Keep one key across network errors and reloads. A retry reads the same execution instead of
 * silently creating a second model call. Only opaque IDs and a request hash enter sessionStorage.
 */
export async function routedRagAnswer<T extends { answer: string }>(data: object): Promise<T> {
  const identity = readSession()?.identity;
  if (!identity) throw new Error("请先登录");
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest(
    "SHA-256", new TextEncoder().encode(JSON.stringify([identity, data])),
  ))).map(value => value.toString(16).padStart(2, "0")).join("");
  const storageKey = `opsagent_harness_answer:${digest}`;
  let attempt: Attempt;
  try { attempt = JSON.parse(sessionStorage.getItem(storageKey) || "null") as Attempt; }
  catch { attempt = { key: crypto.randomUUID() }; }
  if (!attempt?.key) attempt = { key: crypto.randomUUID() };
  sessionStorage.setItem(storageKey, JSON.stringify(attempt));
  let result = attempt.routeId
    ? await request<T | PendingAnswer>({
      url: `/api/rag/harness/answers/${encodeURIComponent(attempt.routeId)}`,
      expectedIdentity: identity,
    })
    : await request<T | PendingAnswer>({
      method: "POST", url: "/api/rag/ask", data, expectedIdentity: identity,
      headers: { "Idempotency-Key": attempt.key },
    });
  for (let poll = 0; poll < 300; poll++) {
    if ("answer" in result && typeof result.answer === "string") {
      sessionStorage.removeItem(storageKey);
      return result as T;
    }
    const pending = result as PendingAnswer;
    if (!/^[a-f0-9-]{36}$/.test(pending.routeId)
      || pending.statusUrl !== `/api/rag/harness/answers/${pending.routeId}`)
      throw new Error("问答服务返回了无效的执行记录");
    attempt.routeId = pending.routeId;
    sessionStorage.setItem(storageKey, JSON.stringify(attempt));
    if (["UNKNOWN", "NEEDS_ATTENTION", "WAITING_APPROVAL", "WAITING_INPUT", "PAUSED",
      "FAILED", "CANCELLED", "EXPIRED", "REJECTED", "BUDGET_EXCEEDED"].includes(pending.status)) {
      throw new Error(`问答状态为 ${pending.status}，执行归属 ${pending.owner}，记录 ${pending.routeId}。`
        + "本次请求已保留，重试只读取同一执行记录；需要人工处理时请在中台查看。");
    }
    await new Promise(resolve => setTimeout(resolve, 1000));
    result = await request<T | PendingAnswer>({
      url: pending.statusUrl, expectedIdentity: identity,
    });
  }
  throw new Error(`问答仍在执行，记录 ${attempt.routeId}。稍后重试将继续读取本次结果。`);
}
