import type { LoginResponse } from "@/types/api";

const key = "opsagent_session_v3";
const lockKey = "opsagent_session_refresh_lock";
const idleMs = 2 * 60 * 60 * 1000;
const absoluteMs = 24 * 60 * 60 * 1000;
const renewAheadMs = 2 * 60 * 1000;
const baseUrl = import.meta.env.VITE_API_BASE_URL || "";

export interface SessionSnapshot extends LoginResponse {
  identity: string;
  sessionId: string;
  sessionStartedAt: string;
  sessionExpiresAt: string;
  lastActivityAt: string;
}
export interface SessionNotice {
  kind: "network" | "expired" | "warning";
  message: string;
}
export class SessionError extends Error {
  constructor(message: string, public readonly expired: boolean, public readonly status?: number) {
    super(message);
  }
}

let notice: SessionNotice | undefined;
const listeners = new Set<() => void>();
let flight: Promise<string> | undefined;
let installed = false;
function emit() { listeners.forEach(listener => listener()); }
function setNotice(value: SessionNotice | undefined) { notice = value; emit(); }
export function getSessionNotice() { return notice; }
export function subscribeSession(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; }

export function readSession(): SessionSnapshot | null {
  const encoded = localStorage.getItem(key);
  if (encoded) {
    try {
      const snapshot = JSON.parse(encoded) as SessionSnapshot;
      if (!snapshot.identity) {
        snapshot.identity = snapshot.sessionId;
        localStorage.setItem(key, JSON.stringify(snapshot));
      }
      return snapshot;
    } catch { return null; }
  }
  const accessToken = localStorage.getItem("opsagent_token");
  if (!accessToken) return null;
  // This is only a UI migration hint. The server independently validates every deadline.
  let started = Date.now() - 30 * 60 * 1000;
  try {
    const claim = JSON.parse(atob(accessToken.split(".")[1]!.replace(/-/g, "+").replace(/_/g, "/")));
    if (Number.isFinite(claim.iat)) started = claim.iat * 1000;
  } catch { /* An invalid token is rejected by the server. */ }
  const snapshot: SessionSnapshot = {
    identity: `legacy-${started}`,
    accessToken,
    refreshToken: localStorage.getItem("opsagent_refresh_token") || "",
    tokenType: "Bearer",
    expiresAt: localStorage.getItem("opsagent_token_expire_at") || new Date(started + 30 * 60 * 1000).toISOString(),
    sessionId: `legacy-${started}`,
    sessionStartedAt: new Date(started).toISOString(),
    sessionExpiresAt: new Date(started + absoluteMs).toISOString(),
    lastActivityAt: new Date(started).toISOString(),
  };
  localStorage.setItem(key, JSON.stringify(snapshot));
  return snapshot;
}

function activityKey(session: SessionSnapshot) { return `opsagent_activity:${session.sessionId}`; }
export function lastActivity(session: SessionSnapshot): number {
  return Math.max(Date.parse(session.lastActivityAt), Number(localStorage.getItem(activityKey(session)) || 0));
}
export function sessionDeadline(session: SessionSnapshot): number {
  const absolute = Date.parse(session.sessionExpiresAt);
  return session.refreshToken
    ? Math.min(absolute, lastActivity(session) + idleMs)
    : Math.min(absolute, Date.parse(session.expiresAt));
}

export function saveLogin(result: LoginResponse, continuingIdentity?: string): void {
  const now = new Date().toISOString();
  const snapshot: SessionSnapshot = {
    ...result,
    identity: continuingIdentity || result.sessionId || crypto.randomUUID(),
    sessionId: result.sessionId || `visitor-${result.expiresAt}`,
    sessionStartedAt: result.sessionStartedAt || now,
    sessionExpiresAt: result.sessionExpiresAt || result.expiresAt,
    lastActivityAt: result.lastActivityAt || now,
  };
  localStorage.setItem("opsagent_token", snapshot.accessToken);
  localStorage.setItem("opsagent_refresh_token", snapshot.refreshToken);
  localStorage.setItem("opsagent_token_expire_at", snapshot.expiresAt);
  localStorage.setItem(key, JSON.stringify(snapshot));
  setNotice(undefined);
}

function clearSession() {
  const old = readSession();
  localStorage.removeItem("opsagent_token");
  localStorage.removeItem("opsagent_refresh_token");
  localStorage.removeItem("opsagent_token_expire_at");
  if (old) localStorage.removeItem(activityKey(old));
  localStorage.removeItem(key);
  emit();
}

export function expireSession(message = "登录已到期，请重新登录后继续。") {
  clearSession();
  setNotice({ kind: "expired", message });
  return new SessionError(message, true, 401);
}

export function safeReturnPath(path: unknown): string {
  const value = typeof path === "string" ? path : "";
  return value.startsWith("/") && !value.startsWith("//") && !value.includes("\\")
    && !value.startsWith("/login") && !value.startsWith("/register") ? value : "/dashboard";
}

const pause = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
async function refreshLock<T>(action: () => Promise<T>): Promise<T> {
  if (navigator.locks) return navigator.locks.request("opsagent-session-refresh", action);
  // Older browsers coordinate by a short lease; the backend still atomically rejects reuse.
  const owner = crypto.randomUUID();
  const until = Date.now() + 20_000;
  while (Date.now() < until) {
    let current: { owner: string; expires: number } | undefined;
    try { current = JSON.parse(localStorage.getItem(lockKey) || "null"); } catch { /* stale lease */ }
    if (!current || current.expires < Date.now()) {
      localStorage.setItem(lockKey, JSON.stringify({ owner, expires: Date.now() + 15_000 }));
      await pause(40 + Math.random() * 40);
      if (JSON.parse(localStorage.getItem(lockKey) || "null")?.owner === owner) {
        try { return await action(); }
        finally { if (JSON.parse(localStorage.getItem(lockKey) || "null")?.owner === owner) localStorage.removeItem(lockKey); }
      }
    }
    await pause(100);
  }
  throw new SessionError("另一页面正在更新登录，请稍后重试。", false);
}

function temporaryFailure(message: string, status?: number) {
  setNotice({ kind: "network", message });
  return new SessionError(message, false, status);
}

export async function ensureAccessToken(rejectedToken?: string): Promise<string> {
  const session = readSession();
  if (!session) throw new SessionError("请先登录。", true, 401);
  if (Date.now() >= sessionDeadline(session)) throw expireSession(
    Date.now() >= Date.parse(session.sessionExpiresAt) ? "本次登录已到期，请重新登录。" : "超过 2 小时未操作，请重新登录。",
  );
  const force = rejectedToken === session.accessToken;
  if (!force && Date.parse(session.expiresAt) - Date.now() > renewAheadMs) return session.accessToken;
  if (!session.refreshToken) {
    if (!force && Date.now() < Date.parse(session.expiresAt)) return session.accessToken;
    throw expireSession("访客会话已到期，请重新登录。");
  }
  if (flight) return flight;
  flight = refreshLock(async () => {
    const current = readSession();
    if (!current || current.sessionId !== session.sessionId) {
      throw new SessionError("登录账号已变化，请重新打开当前操作。", !current);
    }
    if (current.accessToken !== session.accessToken) return current.accessToken;
    let response: Response;
    try {
      response = await fetch(`${baseUrl}/api/auth/refresh`, {
        method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken: current.refreshToken, lastActivityAt: new Date(lastActivity(current)).toISOString() }),
        signal: AbortSignal.timeout(10_000),
      });
    } catch {
      throw temporaryFailure("登录续期暂时无法连接，当前页面与输入已保留，请检查网络后重试。");
    }
    const body = await response.json().catch(() => null) as { code?: number; message?: string; data?: LoginResponse } | null;
    if (response.status === 401 || body?.code === 40100) {
      // A competing tab may have already rotated the token and published its result.
      await pause(150);
      const newer = readSession();
      if (newer && newer.sessionId === current.sessionId && newer.refreshToken !== current.refreshToken) return newer.accessToken;
      if (newer && newer.sessionId !== current.sessionId) throw new SessionError("登录账号已变化，请重新打开当前操作。", false);
      throw expireSession(body?.message || "登录已失效，请重新登录后继续。");
    }
    if (!response.ok || body?.code !== 0 || !body.data?.accessToken) {
      throw temporaryFailure(body?.message || "登录服务暂不可用，当前页面与输入已保留，请稍后重试。", response.status);
    }
    const latest = readSession();
    if (!latest || latest.sessionId !== current.sessionId || latest.refreshToken !== current.refreshToken)
      throw new SessionError("登录状态已变化，本次操作已停止。", !latest);
    const activity = lastActivity(current);
    saveLogin(body.data, current.identity);
    const saved = readSession();
    if (saved) localStorage.setItem(activityKey(saved), String(Math.max(activity, lastActivity(saved))));
    return body.data.accessToken;
  }).finally(() => { flight = undefined; });
  return flight;
}

export async function sessionFetch(url: string, init: RequestInit = {}, replay: "read" | "never" = "never"): Promise<Response> {
  const token = await ensureAccessToken();
  const sessionId = readSession()?.sessionId;
  const send = (access: string) => fetch(url, { ...init, headers: { ...Object.fromEntries(new Headers(init.headers)), Authorization: `Bearer ${access}` } });
  const response = await send(token);
  if (!await rejectedAuthentication(response)) return response;
  if (readSession()?.sessionId !== sessionId)
    throw new SessionError("登录账号已变化，本次操作已停止。", !readSession());
  const fresh = await ensureAccessToken(token);
  const method = (init.method || "GET").toUpperCase();
  if (replay === "read" && (method === "GET" || method === "HEAD")) {
    const retried = await send(fresh);
    if (await rejectedAuthentication(retried))
      throw new SessionError("该服务未接受更新后的登录凭据。登录已保留，请检查服务状态后重试。", false, 401);
    return retried;
  }
  throw new SessionError("登录已更新。本次操作未自动重试，请先查看执行记录，再决定是否重新提交。", false, 401);
}

async function rejectedAuthentication(response: Response) {
  if (response.status === 401) return true;
  if (!response.headers.get("content-type")?.includes("application/json")) return false;
  const body = await response.clone().json().catch(() => null);
  return body?.code === 40100 && Object.hasOwn(body, "data");
}

export function logoutSession() {
  const old = readSession();
  clearSession();
  setNotice(undefined);
  if (!old) return;
  void refreshLock(async () => {
    try {
      await fetch(`${baseUrl}/api/auth/logout`, {
        method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${old.accessToken}` },
        body: JSON.stringify({ refreshToken: old.refreshToken }), signal: AbortSignal.timeout(10_000),
      });
    } catch { /* Local logout is immediate; no request is retried or session recreated. */ }
  }).catch(() => undefined);
}

export function installSessionLifecycle() {
  if (installed) return;
  installed = true;
  let lastWrite = 0;
  const activity = (event: Event) => {
    if (!event.isTrusted) return;
    const current = readSession();
    if (!current) return;
    const now = Date.now();
    if (now >= sessionDeadline(current)) { expireSession(); return; }
    if (now - lastWrite < 5_000) return;
    lastWrite = now;
    localStorage.setItem(activityKey(current), String(now));
    if (notice?.kind === "warning") setNotice(undefined);
    if (Date.parse(current.expiresAt) - now <= renewAheadMs)
      void ensureAccessToken().catch(() => undefined);
  };
  for (const type of ["pointerdown", "keydown", "wheel", "touchstart"]) window.addEventListener(type, activity, { passive: true });
  window.addEventListener("storage", event => {
    if (event.key === key) {
      if (!readSession()) notice = { kind: "expired", message: "登录已在另一页面退出或失效，请重新登录。" };
      else notice = undefined;
      emit();
    }
  });
  setInterval(() => {
    const current = readSession();
    if (!current) return;
    const remaining = sessionDeadline(current) - Date.now();
    if (remaining <= 0) { expireSession(); return; }
    if (remaining <= renewAheadMs) {
      setNotice({ kind: "warning", message: "当前登录即将到期，请保存正在编辑的内容。" });
    }
    if (!document.hidden && Date.parse(current.expiresAt) - Date.now() <= renewAheadMs)
      void ensureAccessToken().catch(() => undefined);
  }, 30_000);
}
