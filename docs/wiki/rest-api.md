# REST API 参考

> [返回 Wiki 导航](./README.md)

本文档面向前端及第三方对接开发者，覆盖后端暴露的全部 REST 接口。所有接口均基于源码逐一核对（`dingRing-adapter` 的 10 个端点 Controller、`GlobalExceptionHandler` 全局异常处理，及 `dingRing-app` 的 DTO），未收录源码中不存在的端点。

---

## 1. 通用约定

### 1.1 BaseURL

所有接口统一以 `/api` 为前缀，服务默认端口 `8080`（无额外的 servlet context-path）：

```
http://<host>:8080/api/...
```

### 1.2 统一响应包装 `ApiResponse<T>`

所有接口（含错误）均返回如下 JSON 结构（`ApiResponse`）：

```json
{
  "success": true,
  "errorCode": null,
  "message": "操作成功",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `success` | boolean | 业务是否成功 |
| `errorCode` | string \| null | 失败时为错误码枚举名（见 1.4），成功时为 `null` |
| `message` | string | 提示信息；成功固定为「操作成功」，失败为具体原因 |
| `data` | T \| null | 业务数据；`ApiResponse<Void>` 接口（如删除）成功时为 `null` |

### 1.3 分页结构 `PageResult<T>`

分页接口（群消息、主题消息）的 `data` 结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `items` | T[] | 当前页数据列表 |
| `total` | long | 总条数 |
| `page` | int | 当前页码（从 1 开始） |
| `pageSize` | int | 每页条数 |

### 1.4 错误响应与错误码

业务层抛出 `BizException`（携带 `ErrorCode`），由 `GlobalExceptionHandler`（`@RestControllerAdvice`）统一转换为 `ApiResponse` + 对应 HTTP 状态码。错误响应体示例：

```json
{
  "success": false,
  "errorCode": "NOT_FOUND",
  "message": "资源不存在",
  "data": null
}
```

异常处理规则：

| 异常场景 | HTTP 状态码 | errorCode | message |
| --- | --- | --- | --- |
| 业务异常 `BizException` | 该错误码对应状态码 | 对应错误码枚举名 | 异常携带的具体信息 |
| `@Valid` 参数校验失败 | 400 | `PARAM_INVALID` | `字段名: 校验提示`（取第一条错误） |
| 静态资源/路由不存在 | 404 | `NOT_FOUND` | 默认提示 |
| 其他未捕获异常（兜底） | 500 | `INTERNAL_ERROR` | 默认提示 |

错误码清单（`ErrorCode` 枚举，`errorCode` 字段返回枚举名）：

| errorCode | HTTP 状态码 | 默认提示 |
| --- | --- | --- |
| `PARAM_INVALID` | 400 | 参数校验失败 |
| `UNAUTHORIZED` | 401 | 未登录 |
| `FORBIDDEN` | 403 | 无权限 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `TOPIC_ALREADY_IN_PROGRESS` | 409 | 当前群已有进行中的主题，请先结束当前讨论 |
| `TOPIC_NOT_IN_PROGRESS` | 409 | 主题非进行中状态 |
| `LLM_API_ERROR` | 502 | LLM API 调用异常 |
| `TOPIC_CONCLUSION_FAILED` | 500 | 结论生成失败 |
| `INTERNAL_ERROR` | 500 | 系统内部错误 |
| `ALL_AGENTS_FAILED` | 503 | 所有 Agent 暂时不可用，请稍后重试 |

### 1.5 端点总览

| 领域 | Controller | 前缀 | 端点数 | 条件注册 |
| --- | --- | --- | --- | --- |
| Agent 管理 | `AgentController` | `/api/agents` | 4 | 否 |
| 群组 | `GroupController` | `/api/groups` | 5 | 否 |
| 主题与消息 | `TopicController` | `/api` | 7 | 否 |
| 知识卡片 | `CardController` | `/api/cards` | 4 | 否 |
| 知识库 | `KbController` | `/api/kb` | 8 | 否 |
| 技能 | `SkillController` | `/api/skills` | 5 | 否 |
| 用户问卷 | `QuestionnaireController` | `/api/questionnaire` | 2 | 否 |
| 观测日志 | `LogController` | `/api/logs` | 6 | 是（见第 10 节） |
| 调试接口 | `TestController` / `ToolTestController` | `/api/test/llm`、`/api/test/tool` | 4 | 是（见第 10 节） |

共 10 个端点 Controller、45 个端点。下文各节表格中，参数列默认标注「必填」/「可选」。

---

## 2. Agent 管理（AgentController）

### 2.1 端点列表

| 方法 | 路径 | 用途 | 参数 | 请求体 | 响应 `data` |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/agents` | 创建 Agent | 无 | `SaveAgentRequest` | `AgentDTO` |
| GET | `/api/agents` | Agent 列表 | 无 | 无 | `AgentDTO[]`（`apiKey` 为 `null`） |
| GET | `/api/agents/{id}` | 按 id 查询 Agent 详情（编辑回填场景） | 路径 `id`（Long，必填） | 无 | `AgentDTO`（含 `apiKey`） |
| PUT | `/api/agents/{id}` | 修改 Agent 配置 | 路径 `id`（Long，必填） | `SaveAgentRequest` | `AgentDTO` |

### 2.2 请求体 `SaveAgentRequest`

创建与修改共用同一请求体（后端用 Bean Validation 分组校验，当前两组规则一致）。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | Agent 花名 |
| `profilePicture` | string | 否 | 头像 |
| `description` | string | 否 | 描述 |
| `baseUrl` | string | 是 | LLM 端点 |
| `apiKey` | string | 是 | API Key（修改时也需重新输入，不保留原 Key） |
| `modelName` | string | 是 | 模型名 |
| `callType` | string | 否 | 调用方式：`API`（直连 LLM API）/ `CLI`（调用 CLI 工具如 Claude Code），默认 `API` |
| `systemPrompt` | string | 否 | 系统提示词 |
| `feature` | object | 否 | 扩展配置（如 `temperature` 默认 0.7、`maxTokens` 默认 100000） |

### 2.3 响应 `AgentDTO`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | Agent ID |
| `name` / `profilePicture` / `description` | string | 基本信息 |
| `baseUrl` / `modelName` | string | LLM 配置 |
| `callType` | string | `API` / `CLI` |
| `systemPrompt` | string | 系统提示词 |
| `feature` | object | temperature / maxTokens 等扩展配置 |
| `createTime` / `updateTime` | string（LocalDateTime） | 时间戳 |
| `apiKey` | string | **仅详情接口返回**；列表接口恒为 `null` |

---

## 3. 群组（GroupController）

### 3.1 端点列表

| 方法 | 路径 | 用途 | 参数 | 请求体 | 响应 `data` |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/groups` | 创建群（含成员配置） | 无 | `CreateGroupRequest` | `GroupDetail` |
| GET | `/api/groups` | 查询用户的群列表 | 无 | 无 | `GroupSummary[]` |
| GET | `/api/groups/{id}` | 群详情（含成员） | 路径 `id`（Long，必填） | 无 | `GroupDetail` |
| DELETE | `/api/groups/{id}` | 删除群（历史保留） | 路径 `id`（Long，必填） | 无 | `null`（Void） |
| PUT | `/api/groups/{id}/members` | 更新群成员配置（群设置-成员管理） | 路径 `id`（Long，必填） | `UpdateMembersRequest` | `GroupDetail` |

### 3.2 请求体

`CreateGroupRequest`（创建群）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 群名称 |
| `agentIds` | Long[] | 是 | 成员 Agent ID 列表（至少一个；任意 Agent 均可参与讨论与总结） |
| `kbIds` | Long[] | 否 | 绑定的知识库 ID 列表 |

`UpdateMembersRequest`（更新成员，整体覆盖语义）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `agentIds` | Long[] | 是 | 成员 Agent ID 列表，完整替换原配置（至少保留一个；群主 USER 成员由后端自动保留，无需传入） |
| `kbIds` | Long[] | 否 | 绑定的知识库 ID 列表。`null` 表示本次不修改知识库绑定；非 `null`（含空列表）则整体覆盖 |

### 3.3 响应 DTO

`GroupSummary`（列表项）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 群 ID |
| `name` | string | 群名称 |
| `memberCount` | int | 成员数 |
| `activeTopicTitle` | string \| null | 当前进行中的主题标题（无则 `null`） |
| `lastMessagePreview` | string | 最后一条消息预览（截断） |
| `lastMessageTime` | string | 最后一条消息时间 |

`GroupDetail`（详情）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `name` | Long / string | 群 ID、名称 |
| `ownerId` | Long | 群主用户 ID |
| `members` | `MemberInfo[]` | 成员列表（含名称头像） |
| `kbIds` | Long[] | 绑定的知识库 ID 列表（未绑定为空列表） |
| `activeTopic` | `TopicSummary` \| null | 当前进行中的主题（无则 `null`） |
| `createTime` | string | 创建时间 |

`MemberInfo`（`GroupDetail.members` 元素）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 成员 ID |
| `type` | string | `USER` / `AGENT` |
| `role` | string | `OWNER` / `MEMBER` / `EXPERT` |
| `name` / `avatar` | string | 名称、头像 |

---

## 4. 主题与消息（TopicController）

前缀分散在 `/api/groups/*` 与 `/api/topics/*` 下。

### 4.1 端点列表

| 方法 | 路径 | 用途 | 参数 | 请求体 | 响应 `data` |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/groups/{groupId}/topics` | 群的主题列表 | 路径 `groupId`（Long，必填） | 无 | `TopicSummary[]` |
| GET | `/api/topics/closed` | 全部已关闭主题（主题沉淀区，跨群） | 无 | 无 | `TopicDigest[]` |
| GET | `/api/groups/{groupId}/messages` | 群消息分页（含闲聊，群聊主窗口用） | 路径 `groupId`（Long，必填）；查询 `page`（int，可选，默认 1）、`pageSize`（int，可选，默认 50） | 无 | `PageResult<MessageDTO>` |
| POST | `/api/topics/{topicId}/conclude` | 结束讨论（触发专家生成结论） | 路径 `topicId`（Long，必填） | 无 | `null`（Void） |
| GET | `/api/topics/{topicId}/messages` | 主题消息分页 | 路径 `topicId`（Long，必填）；查询 `page`（int，可选，默认 1）、`pageSize`（int，可选，默认 50） | 无 | `PageResult<MessageDTO>` |
| GET | `/api/topics/{topicId}/conclusion` | 主题结论 | 路径 `topicId`（Long，必填） | 无 | `ConclusionDTO` |
| GET | `/api/topics/{topicId}/cards` | 主题生成的知识卡片 | 路径 `topicId`（Long，必填） | 无 | `KnowledgeCardDTO[]` |

### 4.2 响应 DTO

`TopicSummary`（主题摘要）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 主题 ID |
| `title` | string | 主题标题 |
| `status` | string | `IN_PROGRESS` / `CONCLUDING` / `CLOSED` / `ARCHIVED` |
| `messageCount` | long | 消息数 |
| `round` | long | 当前轮次（Agent 发言条数，与收束熔断口径一致） |
| `maxRounds` | int | 最大讨论轮次 |
| `createTime` | string | 创建时间 |

`TopicDigest`（已关闭主题摘要）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `title` | Long / string | 主题 ID、标题 |
| `groupId` / `groupName` | Long / string | 来源群 ID、来源群名 |
| `messageCount` | long | 消息数 |
| `closedAt` / `createTime` | string | 关闭时间、创建时间 |

`MessageDTO`（消息，REST 分页与 WS `NEW_MESSAGE` 共用）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 消息 ID |
| `groupId` / `topicId` | Long | 所属群、主题 |
| `senderId` | Long | 发送者 ID |
| `senderName` / `senderAvatar` | string | 发送者名称、头像（冗余，直接展示） |
| `senderType` | string | `USER` / `AGENT` / `SYSTEM` |
| `messageType` | string | `TEXT` / `SYSTEM_NOTICE` |
| `content` | string | 消息内容，支持 Markdown |
| `replyToMessageId` | Long \| null | 被引用消息 ID |
| `replyToSenderName` / `replyToContent` | string | 引用来源名称、引用内容截断（冗余） |
| `createTime` | string | 发送时间 |

`ConclusionDTO`（主题结论）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `topicId` / `title` | Long / string | 主题 ID、标题 |
| `conclusion` | string | STAR 框架 Markdown 结论 |
| `messageCount` | long | 消息数 |
| `closedAt` | string | 关闭时间 |
| `concluderAgentName` | string | 总结 Agent 花名 |

---

## 5. 知识卡片（CardController）

### 5.1 端点列表

| 方法 | 路径 | 用途 | 参数 | 请求体 | 响应 `data` |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/cards` | 卡片列表 | 查询 `category`（string，可选；为空查全部） | 无 | `KnowledgeCardDTO[]` |
| GET | `/api/cards/review` | 复习卡片 | 查询 `category`（string，可选）、`order`（string，可选，默认 `sequential`；可选 `sequential` / `random`） | 无 | `ReviewCardDTO` |
| GET | `/api/cards/categories` | 全部分类（筛选面板用） | 无 | 无 | `string[]` |
| DELETE | `/api/cards/{id}` | 删除卡片 | 路径 `id`（Long，必填） | 无 | `null`（Void） |

### 5.2 响应 DTO

`KnowledgeCardDTO`（与 `GET /api/topics/{topicId}/cards` 共用）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 卡片 ID |
| `topicId` / `topicTitle` | Long / string | 来源主题 ID、标题（冗余，列表展示来源） |
| `question` / `answer` | string | 卡片问题、答案 |
| `category` | string | LLM 识别的分类 |
| `createTime` | string | 创建时间 |

`ReviewCardDTO`（复习响应）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `cards` | `KnowledgeCardDTO[]` | 本次复习卡片集 |
| `total` | long | 卡片总数 |

---

## 6. 知识库（KbController）

### 6.1 端点列表

| 方法 | 路径 | 用途 | 参数 | 请求体 | 响应 `data` |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/kb` | 创建知识库 | 无 | `CreateKbRequest` | `KbDetail` |
| GET | `/api/kb` | 列出所有知识库 | 无 | 无 | `KbSummary[]` |
| GET | `/api/kb/{id}` | 知识库详情（含文件列表） | 路径 `id`（Long，必填） | 无 | `KbDetail` |
| DELETE | `/api/kb/{id}` | 删除知识库 | 路径 `id`（Long，必填） | 无 | `null`（Void） |
| POST | `/api/kb/{id}/files` | 上传文件 | 路径 `id`（Long，必填）；multipart 表单字段 `file`（必填） | `multipart/form-data` | `FileDTO` |
| GET | `/api/kb/{id}/files` | 列出知识库文件 | 路径 `id`（Long，必填） | 无 | `FileDTO[]` |
| DELETE | `/api/kb/{id}/files/{fileId}` | 删除文件 | 路径 `id`、`fileId`（Long，均必填） | 无 | `null`（Void） |
| POST | `/api/kb/search` | 检索测试（源码注释标注 dev only）：验证知识库召回效果 | 查询 `query`（string，必填）、`groupId`（Long，可选） | 无 | `string`（召回结果文本） |

### 6.2 请求体 `CreateKbRequest`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 知识库名称 |
| `description` | string | 是 | 知识库描述（用途说明，建库时必填） |

### 6.3 响应 DTO

`KbSummary`（列表项）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `name` / `description` | Long / string | 基本信息 |
| `status` | string | `ACTIVE` / `PROCESSING` / `FAILED` |
| `createTime` | string | 创建时间 |

`KbDetail`（详情）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` / `name` / `description` / `status` | 同上 | — |
| `files` | `FileDTO[]` | 知识库下的文件列表 |
| `createTime` / `updateTime` | string | 时间戳 |

`FileDTO`（文件信息，不暴露 path 等内部存储细节）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 文件 ID |
| `knowledgeBaseId` | Long | 所属知识库 ID |
| `name` | string | 文件名 |
| `fileType` | string | `PDF` / `MARKDOWN` / `TXT` |
| `fileSize` | Long | 文件大小（字节） |
| `status` | string | `UPLOADED` / `CHUNKED` / `EMBEDDED` / `READY` / `FAILED` |
| `chunkCount` | int | 分块数 |
| `errorMsg` | string | 失败原因 |
| `createTime` / `updateTime` | string | 时间戳 |

---

## 7. 技能（SkillController）

技能 = 工具组 + 附加系统提示词，可通过 CRUD 动态装配 Agent 能力（热更新走 SkillHotReloader）。

### 7.1 端点列表

| 方法 | 路径 | 用途 | 参数 | 请求体 | 响应 `data` |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/skills` | 创建技能 | 无 | `SaveSkillRequest` | `SkillDTO` |
| PUT | `/api/skills/{id}` | 修改技能 | 路径 `id`（Long，必填） | `SaveSkillRequest` | `SkillDTO` |
| DELETE | `/api/skills/{id}` | 删除技能 | 路径 `id`（Long，必填） | 无 | `null`（Void） |
| GET | `/api/skills` | 技能列表（含全局与绑定技能） | 查询 `agentId`（Long，可选；传则返回该 Agent 可见的技能，不传返回全部） | 无 | `SkillDTO[]` |
| GET | `/api/skills/{id}` | 技能详情 | 路径 `id`（Long，必填） | 无 | `SkillDTO` |

### 7.2 请求体 `SaveSkillRequest`

创建与修改共用；`scope`/`status` 缺省时由后端填充默认值。

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | string | 是 | 技能名称 |
| `description` | string | 否 | 描述 |
| `toolNames` | string | 否 | 工具集标识，逗号分隔（如 `"searchKnowledge,queryUserProfile"`） |
| `systemPrompt` | string | 否 | 附加系统提示词 |
| `scope` | string | 否 | 作用域：`GLOBAL` / `AGENT`，缺省 `GLOBAL` |
| `agentId` | Long | 条件必填 | 绑定 Agent ID，仅 `scope=AGENT` 时必填 |
| `status` | string | 否 | `ACTIVE` / `INACTIVE`，缺省 `ACTIVE` |

### 7.3 响应 `SkillDTO`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | Long | 技能 ID |
| `name` / `description` | string | 基本信息 |
| `toolNames` | string | 工具集标识（逗号分隔原始串） |
| `toolNameList` | string[] | 解析后的工具名列表（便于前端直接渲染） |
| `systemPrompt` | string | 附加系统提示词 |
| `scope` | string | `GLOBAL` / `AGENT` |
| `agentId` | Long | 绑定 Agent ID |
| `status` | string | `ACTIVE` / `INACTIVE` |
| `createTime` / `updateTime` | string | 时间戳 |

---

## 8. 观测日志（LogController，条件注册）

日志观测接口，读取后端结构化日志（`logs/dingring.log`）做聚合查询。**仅当 `dingring.debug.observability.enabled=true` 时才注册**（详见第 10 节），未开启时访问返回 404。

### 8.1 端点列表

| 方法 | 路径 | 用途 | 参数 | 响应 `data` |
| --- | --- | --- | --- | --- |
| GET | `/api/logs/stats` | 总览统计（日志总数、ERROR/WARN 计数、LLM 调用统计、Token 总量） | 无 | `Stats` |
| GET | `/api/logs/events` | 事件列表（seq 游标增量 + 多条件过滤，时间降序） | 查询 `afterSeq`（Long，可选）、`limit`（int，可选，默认 2000）、`level`（string，可选）、`eventCode`（string，可选）、`traceId`（string，可选）、`keyword`（string，可选） | `QueryResult` |
| GET | `/api/logs/events/{seq}` | 单条事件全文（按 cursor 懒读） | 路径 `seq`（long，必填）；查询 `includeMessage`（boolean，可选，默认 `false`） | `LogEventRecord` |
| GET | `/api/logs/traces` | Trace 摘要列表（按 traceId 聚合，时间降序） | 查询 `groupId`（string，可选） | `TraceSummary[]` |
| GET | `/api/logs/traces/{traceId}` | Trace 详情（事件流 + 关联入口 + LLM 调用） | 路径 `traceId`（string，必填） | `TraceDetail` |
| GET | `/api/logs/llm-calls` | LLM 调用列表（耗时 + token 关联） | 无 | `LlmCall[]` |

`/api/logs/events` 的增量协议（seq 游标）：

- `afterSeq` 不传或 `<=0`：返回匹配过滤条件的最新 `limit` 条（seq 降序，即最新在前），用于首次拉取或过滤条件变化后重置；
- `afterSeq>0`：返回 seq 大于该值的匹配事件（seq 升序），用于增量拉取；
- 响应恒携带 `latestSeq`（当前最大 seq），前端下次原样回传即可不重不丢。

`keyword` 匹配范围：事件 `message`、`eventName`、`summary` 三者的包含匹配。

### 8.2 响应 DTO

`Stats`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `total` | long | 日志事件总数 |
| `errorCount` / `warnCount` | long | ERROR / WARN 计数 |
| `llmCallCount` | long | LLM 调用次数（`CHAT_RESPONSE` 事件计数） |
| `avgLatencyMs` | long | LLM 调用平均耗时（毫秒） |
| `totalTokens` | long | Token 总量（`BEFORE_CALL_LOG` 的 usage 汇总） |

`QueryResult`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `events` | `LogEventRecord[]` | 事件列表 |
| `latestSeq` | long | 采集器当前最大 seq（增量游标） |
| `fileSize` | long | 日志文件大小（字节） |

`LogEventRecord`（单条事件，含 `/api/logs/events/{seq}` 与 Trace 详情内的元素）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `seq` | long | 采集器内全局递增序号（稳定排序用） |
| `cursor` | long | 事件起始行在日志文件中的字节偏移 |
| `timestamp` | long | 事件时间戳（毫秒） |
| `level` | string | 日志级别（INFO/WARN/ERROR 等） |
| `traceId` / `groupId` | string | 链路 ID、群 ID |
| `thread` / `logger` / `source` | string | 线程、logger、来源 |
| `eventCode` / `eventName` | string | 事件码、事件名 |
| `costMs` | Long \| null | 方法执行耗时（EventAspect 锚点，可空） |
| `summary` | string | 摘要（首行截断 120 字符） |
| `message` | string | 事件全文（多行合并；列表场景可缺省） |
| `fields` | object \| null | 结构化字段（如 LLM token 用量、节点 elapsedMs） |

`TraceSummary`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `traceId` / `groupId` | string | 链路 ID、群 ID |
| `title` | string | 摘要标题（首事件 summary） |
| `eventCount` / `errorCount` / `llmCount` | int | 事件数、错误数、LLM 调用数 |
| `startTimestamp` / `endTimestamp` | long | 起止时间戳（毫秒） |
| `maxCost` | Long \| null | 单事件最大耗时 |

`TraceDetail`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `events` | `LogEventRecord[]` | 主事件流（seq 升序） |
| `relatedEntries` | `LogEventRecord[]` | 关联入口事件（同 groupId、时间窗口 ±30s 的 `G{g}-T?` 段事件） |
| `llmCalls` | `LlmCall[]` | 该 trace 内的 LLM 调用 |

`LlmCall`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `seq` | long | 关联事件 seq |
| `traceId` | string | 链路 ID |
| `agent` / `model` | string | Agent 花名、模型名 |
| `latencyMs` | long | 调用耗时（毫秒） |
| `promptTokens` / `completionTokens` / `totalTokens` | Long \| null | token 用量（按 traceId + 时间邻近匹配 `BEFORE_CALL_LOG`） |
| `timestamp` | long | 时间戳（毫秒） |

---

## 9. 调试接口（TestController / ToolTestController，条件注册）

调试接口**仅当 `dingring.debug.llm.enabled=true` 时才注册**（详见第 10 节），未开启时访问返回 404。

### 9.1 LLM 调试（TestController，前缀 `/api/test/llm`）

通过任意 HTTP 客户端直接构造请求产生真实 LLM 调用，用于排查 API Key 有效性、baseUrl 解析、temperature/maxTokens 覆盖、EventAspect 日志完整性、流式 delta 输出等。请求体中的 LLM 参数直接内联（不依赖 DB 中已有 Agent 记录，不落库）。

| 方法 | 路径 | 用途 | 参数 | 请求体 | 响应 `data` |
| --- | --- | --- | --- | --- | --- |
| POST | `/api/test/llm/chat` | 非流式对话调试（对应 `LlmService.chat` 带 `CallOptions` 的 4 参重载；请求 `override` 字段非空即走该重载） | 无 | `LlmDebugChatRequest` | `LlmDebugChatResponse` |
| POST | `/api/test/llm/chat-stream` | 流式对话调试（同步等流式结束后一次性返回，兼容非 SSE 客户端；每个 delta 块单独收集在 `deltas`） | 无 | `LlmDebugChatRequest` | `LlmDebugChatResponse` |

请求体 `LlmDebugChatRequest`（`/chat` 与 `/chat-stream` 共用）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `agentName` | string | 否 | Agent 花名（仅用于日志与回显，留空默认 `debug`） |
| `baseUrl` | string | 是 | LLM 端点，例如 `https://api.siliconflow.cn/v1` |
| `apiKey` | string | 是 | API Key |
| `modelName` | string | 是 | 模型名 |
| `temperature` | number | 否 | 默认采样温度（可被 `override.temperature` 覆盖） |
| `maxTokens` | integer | 否 | 默认 maxTokens（可被 `override.maxTokens` 覆盖） |
| `systemPrompt` | string | 否 | 系统提示词 |
| `jsonMode` | boolean | 否 | 是否强制 JSON 输出（`response_format=json_object`），默认否 |
| `logReasoning` | boolean | 否 | 是否打印推理过程（推理模型的 `reasoning_content`），默认否 |
| `messages` | `Turn[]` | 是 | 对话消息（时间升序） |
| `override` | `OverrideOptions` | 否 | 单次调用参数覆盖；不传则沿用上面的 `temperature`/`maxTokens` + 全局默认读超时 |

`messages[].Turn`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `role` | string | 是 | `USER` / `ASSISTANT` |
| `content` | string | 是 | 消息内容 |

`override.OverrideOptions`：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `temperature` | number | 否 | 覆盖 temperature（分类任务建议 0） |
| `maxTokens` | integer | 否 | 覆盖 maxTokens（分类输出 JSON 建议限小） |
| `readTimeoutSeconds` | integer | 否 | 覆盖读超时秒数（短任务建议 15） |

响应 `LlmDebugChatResponse`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `content` | string | LLM 返回的完整文本（已 trim） |
| `durationMs` | long | 调用耗时（毫秒，wall-clock） |
| `length` | int | 返回文本字符长度（快速判断截断/空响应） |
| `deltaCount` | int | 流式模式下收到的 delta 块数量（非流式为 0） |
| `deltas` | string[] \| null | 流式模式下所有 delta 块按序拼接（与 `content` 等价，用于排查拼接异常；非流式为 `null`） |

### 9.2 Tool 调试（ToolTestController，前缀 `/api/test/tool`）

不经 LLM 直接调用工具方法，用于验证 Tavily 连通性、Key 有效性、格式化与截断输出。

| 方法 | 路径 | 用途 | 参数 | 响应 `data` |
| --- | --- | --- | --- | --- |
| GET | `/api/test/tool/web-search` | 网页搜索工具直调 | 查询 `query`（string，必填） | `object`：`query`、`result`（工具返回文本）、`length`（结果长度）、`durationMs`（耗时毫秒） |
| GET | `/api/test/tool/web-fetch` | 网页抓取工具直调 | 查询 `url`（string，必填） | `object`：`url`、`result`、`length`、`durationMs` |

示例：`GET /api/test/tool/web-search?query=今天AI新闻`、`GET /api/test/tool/web-fetch?url=https://example.com`。

---

## 10. 调试接口的条件注册说明

三类调试 Controller 均通过 `@ConditionalOnProperty` 条件注册，**配置项未开启时整个 Controller 不存在，请求返回 404**（并非鉴权失败）。确切配置键如下（均要求 `havingValue = "true"`）：

| Controller | 触发条件（配置键） | 覆盖端点 |
| --- | --- | --- |
| `LogController` | `dingring.debug.observability.enabled=true` | `/api/logs/**`（第 8 节，6 个端点） |
| `TestController` | `dingring.debug.llm.enabled=true` | `/api/test/llm/**`（第 9.1 节，2 个端点） |
| `ToolTestController` | `dingring.debug.llm.enabled=true` | `/api/test/tool/**`（第 9.2 节，2 个端点） |

各环境默认值（`start/src/main/resources/`）：

| 配置文件 | `dingring.debug.llm.enabled` | `dingring.debug.observability.enabled` |
| --- | --- | --- |
| `application.yml`（默认） | `true`（dev 开启调试接口） | 未设置（默认关闭） |
| `application-dev.yml` | 未覆盖 | `true` |
| `application-prod.yml` | `false`（生产必须关闭） | 未设置（默认关闭） |

对接提示：前端/第三方在调用日志观测与调试端点前，应先确认目标环境已开启对应开关；生产环境（prod）下这些端点预期不可用。此外 `POST /api/kb/search` 虽非条件注册，但源码注释标注为 dev only（检索测试用途），不建议在生产对接。

---

## 11. 用户问卷（QuestionnaireController）

画像主数据采集：答案存 `user_questionnaire`（事实源），并拼接画像文本写入 `user_profile` 新版本（消费视图），经 `ProfileInjectionHook` 注入所有群的 Agent。

| 方法 | 路径 | 说明 | 路径参数 | 请求体 | 响应 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/questionnaire` | 题目 schema + 当前有效答案（未填过返回空 `answers`） | 无 | 无 | `QuestionnaireDTO` |
| POST | `/api/questionnaire` | 提交问卷答案（事实源与画像同事务写入） | 无 | `SubmitQuestionnaireRequest` | 无 |

### 11.1 响应 `QuestionnaireDTO`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `questions` | `QuestionDTO[]` | 题目 schema（有序，13 题） |
| `answers` | `object` | 当前有效答案（题目 key → 枚举编码 / 文本） |

`QuestionDTO`：`key`（题目键）、`label`（题干）、`dimension`（BACKGROUND/GOAL/FOCUS/STYLE/PRESENTATION）、`type`（SINGLE/MULTI/TEXT/AREA_LEVELS）、`required`、`options[]`（`{value, label}`）。

### 11.2 请求体 `SubmitQuestionnaireRequest`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `answers` | `object` | 是 | 题目 key → 答案；必答项缺失、取值非法、`areaLevels` 与 `focusAreas` 联动不一致均返回 400 `PARAM_INVALID` |

---

## 附：本文档对应的源码位置

| 内容 | 源码路径 |
| --- | --- |
| 全部 Controller | `dingRing-adapter/src/main/java/com/dingring/adapter/rest/` |
| 请求/响应 DTO | `dingRing-app/src/main/java/com/dingring/app/dto/`（`request`、`response`、`test` 子包） |
| 日志查询 DTO（Stats/QueryResult/Trace*/LlmCall） | `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/observability/LogQueryService.java` |
| 日志事件结构 `LogEventRecord` | `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/observability/LogEventRecord.java` |
| `ApiResponse` / `PageResult` | `dingRing-common/src/main/java/com/dingring/common/response/` |
| `BizException` / `ErrorCode` | `dingRing-common/src/main/java/com/dingring/common/exception/` |
| 条件注册配置 | `start/src/main/resources/application*.yml` |
