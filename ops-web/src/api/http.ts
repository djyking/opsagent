import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResponse } from '@/types/api'

export class ApiError extends Error {
  constructor(message: string, public status?: number, public code?: number) { super(message) }
}

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 65_000,
})

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('opsagent_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error.response?.status as number | undefined
    const body = error.response?.data as Partial<ApiResponse<unknown>> | undefined
    if (status === 401) {
      localStorage.removeItem('opsagent_token')
      localStorage.removeItem('opsagent_token_expire_at')
      if (!location.pathname.startsWith('/login')) location.assign('/login')
    }
    return Promise.reject(new ApiError(body?.message || error.message || '请求失败', status, body?.code))
  },
)

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await client.request<ApiResponse<T>>(config)
  if (response.data.code !== 0) throw new ApiError(response.data.message, response.status, response.data.code)
  return response.data.data
}
