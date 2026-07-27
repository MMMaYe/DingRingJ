/* ============================================================
   后端 API 类型定义（与 Java DTO 一一对应）
   ============================================================ */

// ---- 群 ----
export interface MemberInfo {
  id: number;
  type: 'USER' | 'AGENT';
  role: 'OWNER' | 'MEMBER' | 'EXPERT';
  name: string;
  avatar: string | null;
}

export interface GroupSummary {
  id: number;
  name: string;
  memberCount: number;
  activeTopicTitle: string | null;
  lastMessagePreview: string | null;
  lastMessageTime: string | null;
}

export interface TopicSummary {
  id: number;
  title: string;
  status: 'IN_PROGRESS' | 'CONCLUDING' | 'CLOSED';
  messageCount: number;
  createTime: string;
}

export interface GroupDetail {
  id: number;
  name: string;
  ownerId: number;
  members: MemberInfo[];
  activeTopic: TopicSummary | null;
  createTime: string;
}

// ---- 消息 ----
export interface MessageDTO {
  id: number;
  groupId: number;
  topicId: number | null;
  senderId: number;
  senderName: string;
  senderType: 'USER' | 'AGENT' | 'SYSTEM';
  senderAvatar: string | null;
  messageType: string;
  content: string;
  replyToMessageId: number | null;
  replyToSenderName: string | null;
  replyToContent: string | null;
  createTime: string;
}

// ---- Agent ----
export interface AgentDTO {
  id: number;
  name: string;
  profilePicture: string | null;
  description: string | null;
  baseUrl: string;
  modelName: string;
  /** 调用方式：API / CLI */
  callType: string;
  systemPrompt: string | null;
  feature: Record<string, unknown> | null;
  createTime: string;
  updateTime: string;
}

/** Agent 创建/修改统一请求（与后端 SaveAgentRequest 对齐） */
export interface SaveAgentRequest {
  name: string;
  description?: string;
  profilePicture?: string;
  baseUrl: string;
  apiKey: string;
  modelName: string;
  callType?: string;
  systemPrompt?: string;
  feature?: Record<string, unknown>;
}

// ---- 知识卡片 ----
export interface KnowledgeCardDTO {
  id: number;
  topicId: number;
  topicTitle: string;
  question: string;
  answer: string;
  category: string;
  createTime: string;
}

export interface ReviewCardDTO {
  cards: KnowledgeCardDTO[];
  total: number;
}

// ---- 结论 ----
export interface ConclusionDTO {
  topicId: number;
  title: string;
  conclusion: string;
  messageCount: number;
  closedAt: string;
  expertAgentName: string;
}

// ---- 分页 ----
export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

// ---- 统一响应 ----
export interface ApiResponse<T = unknown> {
  success: boolean;
  errorCode: string | null;
  message: string;
  data: T;
}

// ---- WebSocket 消息 ----
export interface WsPayload {
  type: string;
  data: Record<string, unknown>;
}

export interface CreateGroupRequest {
  name: string;
  agentIds: number[];
  expertAgentId: number;
}

/** PUT /api/groups/{id}/members 更新群成员请求 */
export interface UpdateMembersRequest {
  agentIds: number[];
  expertAgentId: number;
}
