import type { ApiResponse } from './types';

/**
 * 统一 REST 请求封装，自动解包 ApiResponse<T>。
 */
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  let body: ApiResponse<T> | null = null;
  try {
    body = await resp.json();
  } catch {
    /* 非 JSON */
  }
  if (!body) throw new Error('网络异常，请稍后重试');
  if (!body.success) {
    const err = new Error(body.message || '请求失败') as Error & { errorCode?: string };
    err.errorCode = body.errorCode ?? undefined;
    throw err;
  }
  return body.data;
}

export const API = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, data?: unknown) =>
    request<T>(path, { method: 'POST', body: data === undefined ? undefined : JSON.stringify(data) }),
  put: <T>(path: string, data: unknown) =>
    request<T>(path, { method: 'PUT', body: JSON.stringify(data) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};
