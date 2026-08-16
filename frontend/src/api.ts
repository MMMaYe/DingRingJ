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

// ==================== P2 知识库（RAG） ====================

/** 知识库摘要（GET /api/kb），与后端 KbSummary 对齐 */
export interface KbSummary {
  id: number;
  name: string;
  scope: 'GLOBAL' | 'GROUP';
  groupId: number | null;
  status: string;
  createTime: string;
}

/** 知识库文件元信息（GET /api/kb/glm-5.3_common/files），与后端 FileDTO 对齐 */
export interface KbFileDTO {
  id: number;
  knowledgeBaseId: number;
  name: string;
  fileType: string;
  fileSize: number;
  status: 'UPLOADED' | 'CHUNKED' | 'EMBEDDED' | 'READY' | 'FAILED';
  chunkCount: number | null;
  errorMsg: string | null;
  createTime: string;
  updateTime: string;
}

/** 知识库详情（GET /api/kb/glm-5.3_common），与后端 KbDetail 对齐 */
export interface KbDetail extends KbSummary {
  files: KbFileDTO[];
  updateTime: string;
}

export const KbApi = {
  list: () => API.get<KbSummary[]>('/api/kb'),
  create: (data: { name: string; scope: 'GLOBAL' | 'GROUP'; groupId?: number }) =>
    API.post<KbDetail>('/api/kb', data),
  detail: (id: number) => API.get<KbDetail>(`/api/kb/${id}`),
  remove: (id: number) => API.del<void>(`/api/kb/${id}`),
  listFiles: (id: number) => API.get<KbFileDTO[]>(`/api/kb/${id}/files`),
  deleteFile: (id: number, fileId: number) => API.del<void>(`/api/kb/${id}/files/${fileId}`),
  /**
   * multipart 上传不走统一 request 封装：FormData 禁止手动设置 Content-Type，
   * 需让浏览器自动携带 multipart boundary。
   */
  uploadFile: async (id: number, file: File): Promise<KbFileDTO> => {
    const form = new FormData();
    form.append('file', file);
    const resp = await fetch(`/api/kb/${id}/files`, { method: 'POST', body: form });
    const body = await resp.json();
    if (!body?.success) throw new Error(body?.message || '上传失败');
    return body.data;
  },
};
