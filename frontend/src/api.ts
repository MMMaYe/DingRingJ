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
  /** 知识库描述（用途说明） */
  description: string | null;
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
  // 创建传名称+描述：作用域概念已移除，群与库的关联由群侧绑定（chat_group.knowledge_base_config）
  create: (data: { name: string; description: string }) =>
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
    // 非 JSON 响应（如网关 500 的 HTML 页）时 json() 抛英文 SyntaxError，转译为友好提示
    let body: { success?: boolean; message?: string; data?: KbFileDTO } | null = null;
    try {
      body = await resp.json();
    } catch {
      throw new Error('上传失败：服务响应异常');
    }
    if (!body?.success) throw new Error(body?.message || '上传失败');
    return body.data as KbFileDTO;
  },
};

// ==================== 日志观测仪表盘 ====================

/** 单条日志事件（与后端 LogEventRecord 对齐） */
export interface LogEvent {
  seq: number;
  cursor: number;
  timestamp: number;
  level: 'INFO' | 'WARN' | 'ERROR' | string;
  traceId: string;
  groupId?: string;
  thread: string;
  logger: string;
  source: string;
  eventCode: string;
  eventName: string;
  costMs?: number | null;
  summary: string;
  message: string;
  fields?: Record<string, unknown> | null;
}

/** 事件列表查询结果（latestSeq 为服务端已分配的最大 seq，前端增量回传） */
export interface LogQueryResult {
  events: LogEvent[];
  latestSeq: number;
  fileSize: number;
}

/** 顶部统计概览 */
export interface LogStats {
  total: number;
  errorCount: number;
  warnCount: number;
  llmCallCount: number;
  avgLatencyMs: number;
  totalTokens: number;
}

/** Trace 摘要（列表项） */
export interface TraceSummary {
  traceId: string;
  groupId: string;
  title?: string;
  eventCount: number;
  errorCount: number;
  llmCount: number;
  startTimestamp: number;
  endTimestamp: number;
  maxCost?: number | null;
}

/** Trace 详情 */
export interface TraceDetail {
  events: LogEvent[];
  relatedEntries: LogEvent[];
  llmCalls: LlmCall[];
}

/** LLM 调用记录 */
export interface LlmCall {
  seq: number;
  traceId: string;
  agent: string;
  model: string;
  latencyMs: number;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalTokens?: number | null;
  timestamp: number;
}

export const LogApi = {
  stats: () => API.get<LogStats>('/api/logs/stats'),
  events: (params: {
    afterSeq?: number;
    limit?: number;
    level?: string;
    eventCode?: string;
    traceId?: string;
    keyword?: string;
  }) => {
    const q = new URLSearchParams();
    if (params.afterSeq != null && params.afterSeq > 0) q.set('afterSeq', String(params.afterSeq));
    if (params.limit != null) q.set('limit', String(params.limit));
    if (params.level) q.set('level', params.level);
    if (params.eventCode) q.set('eventCode', params.eventCode);
    if (params.traceId) q.set('traceId', params.traceId);
    if (params.keyword) q.set('keyword', params.keyword);
    const qs = q.toString();
    return API.get<LogQueryResult>(`/api/logs/events${qs ? `?${qs}` : ''}`);
  },
  eventBySeq: (seq: number, includeMessage = false) =>
    API.get<LogEvent>(`/api/logs/events/${seq}?includeMessage=${includeMessage}`),
  traces: (groupId?: string) =>
    API.get<TraceSummary[]>(`/api/logs/traces${groupId ? `?groupId=${groupId}` : ''}`),
  traceDetail: (traceId: string) =>
    API.get<TraceDetail>(`/api/logs/traces/${encodeURIComponent(traceId)}`),
  llmCalls: () => API.get<LlmCall[]>('/api/logs/llm-calls'),
};
