# 04 REST API 参考

统一响应包裹 `ApiResponse<T>`：`{ success, errorCode, message, data }`。前端 `api.ts` 自动解包 `data`，`success=false` 时抛出携带 `errorCode` 的错误。

所有端点基址同源（无前缀），下表路径即完整路径。

## GroupController — `/api/groups`

| 方法 | 路径 | 说明 | 入参 | 返回 `data` |
|---|---|---|---|---|
| POST | `/api/groups` | 创建群（含成员配置） | body `CreateGroupRequest` | `GroupDetail` |
| GET | `/api/groups` | 查询用户的群列表 | — | `List<GroupSummary>` |
| GET | `/api/groups/{id}` | 群详情（含成员） | path `id` | `GroupDetail` |
| DELETE | `/api/groups/{id}` | 删除群（**逻辑删除**，历史保留） | path `id` | `Void` |
| PUT | `/api/groups/{id}/members` | 更新群成员配置 | path `id` + body `UpdateMembersRequest` | `GroupDetail` |

## TopicController — `/api`

| 方法 | 路径 | 说明 | 入参 | 返回 `data` |
|---|---|---|---|---|
| GET | `/api/groups/{groupId}/topics` | 群的主题列表 | path `groupId` | `List<TopicSummary>` |
| GET | `/api/groups/{groupId}/messages` | 群消息分页（含闲聊） | path `groupId`，query `page=1`、`pageSize=50` | `PageResult<MessageDTO>` |
| POST | `/api/topics/{topicId}/conclude` | 结束讨论（触发生成结论） | path `topicId` | `Void` |
| GET | `/api/topics/{topicId}/messages` | 主题消息分页 | path `topicId`，query `page=1`、`pageSize=50` | `PageResult<MessageDTO>` |
| GET | `/api/topics/{topicId}/conclusion` | 主题结论 | path `topicId` | `ConclusionDTO` |
| GET | `/api/topics/{topicId}/cards` | 主题生成的知识卡片 | path `topicId` | `List<KnowledgeCardDTO>` |

## AgentController — `/api/agents`

| 方法 | 路径 | 说明 | 入参 | 返回 `data` |
|---|---|---|---|---|
| POST | `/api/agents` | 创建 Agent | body `SaveAgentRequest`（分组 Create） | `AgentDTO` |
| PUT | `/api/agents/{id}` | 修改 Agent 配置 | path `id` + body `SaveAgentRequest`（分组 Update） | `AgentDTO` |
| GET | `/api/agents` | Agent 列表（**不含 apiKey**） | — | `List<AgentDTO>` |
| GET | `/api/agents/{id}` | Agent 详情（**含 apiKey，供编辑回填**） | path `id` | `AgentDTO` |

## CardController — `/api/cards`

| 方法 | 路径 | 说明 | 入参 | 返回 `data` |
|---|---|---|---|---|
| GET | `/api/cards` | 卡片列表（category 为空查全部） | query `category?` | `List<KnowledgeCardDTO>` |
| GET | `/api/cards/review` | 复习卡片 | query `category?`、`order=sequential`（或 `random`） | `ReviewCardDTO` |
| GET | `/api/cards/categories` | 全部分类（筛选面板用） | — | `List<String>` |
| DELETE | `/api/cards/{id}` | 删除卡片（**物理删除**） | path `id` | `Void` |

---

## 请求 DTO

### CreateGroupRequest
| 字段 | 类型 | 校验 |
|---|---|---|
| `name` | String | NotBlank「群名称不能为空」 |
| `agentIds` | List\<Long\> | NotEmpty「至少选择一个 Agent」 |

### SaveAgentRequest
> 合并 Create/Update 两个校验分组。

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | String | NotBlank（两组） |
| `profilePicture` | String | 头像 |
| `description` | String | 人设描述 |
| `baseUrl` | String | NotBlank（两组），LLM 端点 |
| `apiKey` | String | NotBlank（两组），创建与修改都必填 |
| `modelName` | String | NotBlank（两组） |
| `callType` | String | `API` / `CLI`，默认 API |
| `systemPrompt` | String | 系统提示词 |
| `feature` | Map | 扩展（`temperature` 未配置时默认 1.0；`maxTokens` 未配置时 Agent 层默认 4096，模型工厂业务默认 16384） |

### UpdateMembersRequest
| 字段 | 类型 | 校验 |
|---|---|---|
| `agentIds` | List\<Long\> | NotEmpty「至少保留一个成员 Agent」；整体覆盖，USER 群主由后端保留 |

---

## 响应 DTO

### GroupSummary（列表）
`id, name, memberCount(int), activeTopicTitle(可空), lastMessagePreview(可空), lastMessageTime`

### GroupDetail（详情）
`id, name, ownerId, members(List<MemberInfo>), activeTopic(TopicSummary 可空), createTime`

### MemberInfo
`id, type(USER/AGENT), role(OWNER/MEMBER), name, avatar`

### TopicSummary
`id, title, status(IN_PROGRESS/CONCLUDING/CLOSED/ARCHIVED), messageCount(long), round(long, Agent 发言数), maxRounds(int), createTime`

### MessageDTO（REST 分页与 WS NEW_MESSAGE 共用）
`id, groupId, topicId(可空=闲聊), senderId, senderName, senderType(USER/AGENT/SYSTEM), senderAvatar, messageType(TEXT/SYSTEM_NOTICE), content(Markdown), replyToMessageId, replyToSenderName, replyToContent(截断), createTime`

### AgentDTO
`id, name, profilePicture, description, baseUrl, modelName, callType, systemPrompt, feature(Map), createTime, updateTime, apiKey(仅详情返回)`

### KnowledgeCardDTO
`id, topicId, topicTitle(冗余), question, answer, category, createTime`

### ReviewCardDTO
`cards(List<KnowledgeCardDTO>), total(long)`

### ConclusionDTO
`topicId, title, conclusion(STAR Markdown), messageCount(long), closedAt, concluderAgentName`

### PageResult\<T\>
`items(List<T>), total(long), page(int), pageSize(int)`

---

## 异常映射（GlobalExceptionHandler）

| 异常 | HTTP | errorCode |
|---|---|---|
| `BizException` | 取 ErrorCode 的 httpStatus | 该 ErrorCode 名 |
| `MethodArgumentNotValidException` | 400 | `PARAM_INVALID`（message = 「字段名: 默认消息」） |
| `NoResourceFoundException` | 404 | `NOT_FOUND` |
| 兜底 `Exception` | 500 | `INTERNAL_ERROR` |

### ErrorCode 枚举全量
| 枚举 | HTTP | 默认消息 |
|---|---|---|
| `PARAM_INVALID` | 400 | 参数校验失败 |
| `UNAUTHORIZED` | 401 | 未登录 |
| `FORBIDDEN` | 403 | 无权限 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `TOPIC_ALREADY_IN_PROGRESS` | 409 | 当前群已有进行中的主题，请先结束当前讨论 |
| `TOPIC_NOT_IN_PROGRESS` | 409 | 主题非进行中状态 |
| `TOPIC_CONCLUSION_FAILED` | 500 | 结论生成失败 |
| `ALL_AGENTS_FAILED` | 503 | 所有 Agent 暂时不可用，请稍后重试 |
| `LLM_API_ERROR` | 502 | LLM API 调用异常 |
| `INTERNAL_ERROR` | 500 | 系统内部错误 |
