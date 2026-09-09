import axios, { type AxiosRequestConfig } from "axios";
import type { ApiResponse } from "@/types/api";
import { ensureAccessToken, readSession, SessionError } from "./session";

declare module "axios" {
  interface AxiosRequestConfig {
    sessionToken?: string;
    sessionId?: string;
    sessionRetried?: boolean;
    expectedIdentity?: string;
  }
}

function publicAuth(url = "") {
  return /^\/api\/auth\/(captcha|features|login|register|refresh|logout)(?:$|\?)/.test(url);
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status?: number,
    public code?: number,
  ) {
    super(message);
  }
}

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "",
  timeout: 65_000,
});

client.interceptors.request.use(async (config) => {
  if (publicAuth(config.url)) return config;
  if (config.expectedIdentity && readSession()?.identity !== config.expectedIdentity)
    throw new SessionError("登录身份已变化，请重新确认当前操作。", false);
  const token = await ensureAccessToken();
  if (config.expectedIdentity && readSession()?.identity !== config.expectedIdentity)
    throw new SessionError("登录身份已变化，请重新确认当前操作。", false);
  config.headers.Authorization = `Bearer ${token}`;
  config.sessionToken = token;
  config.sessionId = readSession()?.sessionId;
  return config;
});

async function recoverAuthentication(config: AxiosRequestConfig) {
  if (readSession()?.sessionId !== config.sessionId)
    throw new SessionError("登录账号已变化，本次操作已停止。", !readSession());
  if (config.sessionRetried)
    throw new SessionError("该服务未接受更新后的登录凭据。登录已保留，请检查服务状态后重试。", false, 401);
  await ensureAccessToken(config.sessionToken);
  const method = (config.method || "get").toLowerCase();
  if (method === "get" || method === "head") {
    config.sessionRetried = true;
    return client.request(config);
  }
  throw new SessionError("登录已更新。本次操作未自动重试，请先查看执行记录，再决定是否重新提交。", false, 401);
}

client.interceptors.response.use(
  response => response.data?.code === 40100 && !publicAuth(response.config.url)
    ? recoverAuthentication(response.config) : response,
  async (error) => {
    if (error instanceof SessionError) throw error;
    const status = error.response?.status as number | undefined;
    const body = error.response?.data as
      | Partial<ApiResponse<unknown>>
      | undefined;
    const config = error.config as AxiosRequestConfig | undefined;
    if (status === 401 && config && !publicAuth(config.url)) {
      return recoverAuthentication(config);
    }
    return Promise.reject(
      new ApiError(
        body?.message || error.message || "请求失败",
        status,
        body?.code,
      ),
    );
  },
);

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await client.request<ApiResponse<T>>(config);
  if (response.data.code !== 0)
    throw new ApiError(
      response.data.message,
      response.status,
      response.data.code,
    );
  return response.data.data;
}
