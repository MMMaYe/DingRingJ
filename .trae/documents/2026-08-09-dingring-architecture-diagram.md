# DingRing 架构大图绘制计划

> 日期：2026-08-09
> 目标产物：`/Users/Zhuanz/IdeaProjects/DingRingJ/system-architecture.html`（单文件，浏览器直接打开）

## 1. Summary

为 DingRing 多 Agent AI 群聊系统绘制一张**系统全貌端到端架构大图**：浏览器前端 → Spring Boot 单体（六模块 DDD 分层）→ MySQL / PostgreSQL+pgvector / 各家 LLM 网关 / Nacos。

- 使用 `architecture-diagram` 技能的工作流（产出单文件 HTML），但**不遵循该技能的默认深色 UI**，改为**浅色 / 文档风格**。
- 布局：**纵向分层泳道**（浏览器 → 接入层 → 应用层 → 领域层 → 基础设施层 → 外部服务），自上而下，符合架构大图惯例。
- 信息密度：**全包含** —— SAA StateGraph 工作流、RAG 知识库、Skill 技能、Agent Hook 注入、事件驱动与后台任务等新子系统全部呈现。

## 2. Current State Analysis（探索结论）

### 系统本质
DingRing 是「多 Agent AI 群聊学习系统」：用户建群并拉入多个 Agent（各自花名 / 人设 / 独立 LLM 端点），用户发言由引擎自动判定意图（CHAT / DISCUSS / WORK / CONCLUDE），判定讨论时**追溯式自动建题**，Agent 按评分调度轮流发言并靠 `[[PASS]]` / `[[CONCLUDE]]` 协作；收束后由总结 Agent 生成 STAR 框架结论，再异步提取 Q&A 知识卡片；系统持续从用户发言提炼**用户画像**、积累**群记忆**注入上下文。

### 技术栈
| 层 | 技术 |
|---|---|
| 后端 | Java 21（虚拟线程）、Spring Boot 3.4.7、MyBatis、Spring AI（OpenAI 协议适配） |
| 数据库 | MySQL 8（`ring_chat`）/ H2（schema.sql 兼容）|
| 向量库 | PostgreSQL + pgvector（`my_rag` 库，`vector_store` 表，第二数据源） |
| 前端 | React 19、Vite 8、TypeScript、react-router-dom 7 |
| 通信 | REST（`/api/*`）+ 原生 WebSocket（`/ws/chat?groupId=`） |
| 外部 | DeepSeek / StepFun / CloudBase 等 LLM 网关；Nacos（可选，提示词热更新）；本地文件存储（`./rag-files`）|

### 六模块依赖关系（DDD 依赖倒置）
`adapter → app → domain ← infrastructure`；`common` 为底座被各层依赖。
- `app` 只依赖 `domain` 端口接口，不依赖 `infrastructure`，Spring 运行时注入实现。
- `domain` 为纯 POJO，无 Spring Web 依赖，可独立单测。

### 各层关键组件（图的内容清单）

**前端（React SPA）**
- 页面：Chat（聊天）/ Topics（主题）/ Agents（智能体）/ Cards（知识卡片）/ KB（知识库）
- 基建：`WebSocketContext` + `useWebSocket`、`api.ts`、`Sidebar / Modal / Toast / Avatar` 等

**dingRing-adapter（接入层）**
- REST：`GroupController / TopicController / AgentController / CardController / KbController / SkillController / TestController` + `GlobalExceptionHandler`
- WebSocket：`ChatWebSocketHandler` → `WsMessageDispatcher`
- 出站：`WsBroadcastAdapter`（实现 app 层 `ChatPusher` 端口）

**dingRing-app（应用层）**
- 编排引擎：`ChatOrchestrator`、`DiscussionEngine`（每群单线程虚拟线程主循环）、`SpeakerScheduler`（评分选人）、`Terminator`（熔断）、`ModeratorService`（可选主持人）、`ContextBuilder`（上下文组装）、`MessageRouter`（意图路由）、`StreamMarkerGuard`
- SAA 工作流：`WorkflowConfig` + 9 个节点（`PreprocessNode / IntentClassifyNode / ChatNode / EnsureTopicNode / DiscussNode / ConcludeNode / SedimentNode / ProfileExtractNode / WorkNode`）
- 应用服务：`Group / Topic / Agent / Card / KnowledgeBase / Skill AppService` + `MessageAssembler`
- 事件与任务：`CardEventHandler`、`ProfileEventHandler`、`CardReconciler`（@Scheduled 对账补卡）、`ConclusionWatchdog`（卡死主题自愈）
- 端口：`ChatPusher`（出站推送）

**dingRing-domain（领域层）**
- 实体：`Group / GroupMember / GroupMessage`、`Topic / TopicStatus / KnowledgeCard`、`Agent`、`User / UserProfile`、`KnowledgeBase / File`、`Skill`
- 仓储端口：`GroupRepository / TopicRepository / CardRepository / AgentRepository / UserRepository / UserProfileRepository / MessageRepository / KnowledgeBaseRepository / FileRepository / SkillRepository`
- 服务端口：`LlmService`、`MemoryService`、`ProfileService`、`RagService`、`Reranker`、`AgentSpeakerService`、`DiscussionFlowService`、`GroupBroadcastService`、`DomainEventPublisher`
- 领域事件：`MessageSent / TopicCreated / TopicConcluding / TopicClosed / KnowledgeCardGenerated / GroupCreated / AgentSelected / AgentFailed` 等
- 工作流契约：`DiscussionFlowResult`、`DiscussionRules`、`StateKeys`

**dingRing-infrastructure（基础设施层）**
- 持久化：MyBatis Mapper ×10 + XML、`*RepositoryImpl` ×10、TypeHandler（JSON/List）
- LLM：`SpringAiLlmService` / `MockLlmService`（`dingring.llm.mock` 开关）、`SaaModelFactory`（按 Agent 动态构建 `OpenAiChatModel`）
- 记忆/画像：`SimpleMemoryService`、`SimpleProfileService`
- 事件：`SpringEventPublisher` + `@Event` 注解 + `EventAspect` AOP
- RAG：`DocumentIngestionPipeline`、`FileStorageService`、`MockEmbeddingModel`、`SaaRagService`、`LlmReranker`、`PgVectorStoreConfig / VectorDataSourceConfig`
- 技能：`SkillHotReloader`、`SkillLoaderServiceImpl`、`SkillToolkitFactory`
- Agent 运行时：`SaaReactAgentFactory`、`SupervisorAgentFactory`、Hooks（`RagInjectionHook / MemoryInjectionHook / ProfileInjectionHook / GroupRosterHook / WorkProgressBroadcastHook`）、Tools（`KnowledgeSearchTool / TopicHistoryTool / UserProfileQueryTool`）
- 工作流：`SaaWorkflow`（StateGraph 编译执行）、`NodeHandlerRegistry`

**外部服务**
- MySQL 8 `ring_chat`（业务数据）
- PostgreSQL + pgvector `my_rag`（向量库）
- LLM 网关：DeepSeek / StepFun / CloudBase
- Nacos（提示词热更新，可选）
- 本地文件存储 `./rag-files`

### 核心数据流（图中要体现的连线）
1. 浏览器 REST `/api/*` → adapter REST Controllers → app 应用服务
2. 浏览器 WS `/ws/chat` ↔ `ChatWebSocketHandler`（上行消息）
3. WS → `ChatOrchestrator.onUserMessage`（落库 + 广播 `NEW_MESSAGE` + publish `MessageSent`）
4. `ChatOrchestrator` → `DiscussionEngine`（信号入队 + wake）
5. `DiscussionEngine` → `SaaWorkflow.advance()`（StateGraph：preprocess → intent-classify → 条件边分流）
6. 工作流节点 / 编排引擎 → domain 端口（仓储 / LLM / 记忆 / 画像 / RAG）
7. domain 端口 ← infrastructure 实现（**虚线**，表示运行时注入）
8. app `ChatPusher` 端口 ← adapter `WsBroadcastAdapter` → WebSocket 推送前端
9. `SpringEventPublisher` → 事件处理器（卡片生成、画像提炼）→ 独立虚拟线程
10. infra → MySQL / PG / LLM 网关 / Nacos / 本地文件

### 线程模型（摘要卡片呈现）
- 每群一个单线程虚拟线程执行器（`group-{id}-`），群内串行免锁，跨群并行
- 画像提炼 / 卡片生成各自独立虚拟线程，不占用群执行器
- `ConclusionWatchdog`（60s）、`CardReconciler`（10min）为 @Scheduled 后台任务

## 3. Proposed Changes

### 唯一改动：新建 `/Users/Zhuanz/IdeaProjects/DingRingJ/system-architecture.html`

单文件、零依赖（不引 CDN，字体用系统字体栈）、无 JavaScript（若需交互动效用纯 CSS）。

#### 3.1 UI 风格（浅色 / 文档风格）
- 背景 `#f7f8fa` 类纸张色，卡片白底 `#ffffff`，柔和边框（`#e5e7eb`），文字 `#111827` / 次级 `#6b7280`
- 模块语义色（浅色版，用于泳道左边条 / 组件描边 / 标签）：
  - 前端 `cyan`（`#0891b2`）
  - 接入层 adapter `coral`（`#e11d48`）
  - 应用层 app `green`（`#059669`）
  - 领域层 domain `blue`（`#2563eb`）
  - 基础设施 infra `violet`（`#7c3aed`）
  - common 底座 `slate`（`#64748b`）
  - 外部服务 `amber`（`#b45309`）
- 字体：系统栈（`-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`），等宽可用 `ui-monospace, "SF Mono", monospace` 标注类名/端口名
- 布局：页面宽度 `min-width: 1360px`，居中，支持水平滚动；分层泳道自上而下堆叠

#### 3.2 页面结构（四段式）
1. **Header**：标题「DingRing 系统架构总览」+ 副标题（技术栈一句话）+ 图例（颜色 / 线型说明）
2. **主体分层泳道**（自上而下 7 个泳道）：
   - 泳道 0：浏览器 / React SPA（前端）
   - 泳道 1：dingRing-adapter（接入层）
   - 泳道 2：dingRing-app（应用层，含编排引擎 / SAA 工作流 / 应用服务 / 事件任务四组）
   - 泳道 3：dingRing-domain（领域层，含实体 / 端口 / 事件 / 工作流契约四组）
   - 泳道 4：dingRing-infrastructure（基础设施层，含持久化 / LLM / 记忆 / 事件 / RAG / 技能 / Agent 运行时 / SAA 工作流八组）
   - 泳道 5：common 底座（横向细条，`adapter/app/domain/infra` 均依赖）
   - 泳道 6：外部服务（MySQL / PG+pgvector / LLM 网关 / Nacos / 本地文件）
   - 每个泳道左侧竖排泳道名 + 语义色左边条；组件为圆角卡片（标题=类名/组件，副标题=职责一行）
3. **SVG 覆盖箭头层**：绝对定位覆盖在泳道区之上，绘制跨层连线
   - 实线（`#94a3b8`，带箭头）= 调用 / 数据流
   - 虚线（`#7c3aed`，带箭头）= 端口实现（infra → domain）
   - 推送线（`#0891b2`）= 出站 WebSocket 推送
   - 连线标注简短标签（如 `/api/*`、`/ws/chat`、`StateGraph`、`注入`）
4. **Footer**：生成日期 + 模块统计（6 模块 / 约 10 Mapper / 9 领域事件）

#### 3.3 图例与摘要卡片
- 图例置于 Header 下方（颜色语义 + 实线/虚线/推送线含义）
- 摘要卡片 3 张（置于主体之后 / Footer 之前）：
  - 「核心机制」：意图路由 → 追溯建题 → 评分调度轮流发言 → STAR 结论 → 卡片沉淀
  - 「线程模型」：每群单线程虚拟线程执行器；画像/卡片独立虚拟线程；Watchdog/Reconciler 定时任务
  - 「降级哲学」：Moderator 失败回退评分调度；Agent 失败接力下一候选；RAG/画像失败静默降级不影响主流程

#### 3.4 实现要点
- 泳道用 HTML `<section>` + CSS flex 网格布局；组件用 `<div class="comp">` 卡片，避免手写 SVG 文本排版
- 连线统一在一个 `<svg>` 覆盖层中按坐标绘制（坐标通过固定泳道高度 + 组件行内锚点计算，规划时给出锚点约定即可，实现时按实际布局微调）
- 无 JavaScript；hover 高亮用 CSS `:hover`
- 页面标题 `<title>DingRing 系统架构总览</title>`，`lang="zh-CN"`

## 4. Assumptions & Decisions

| 决策 | 选择 | 理由 |
|---|---|---|
| 文件位置 | 项目根目录 `system-architecture.html` | 用户指定 |
| UI 风格 | 浅色 / 文档风格 | 用户指定，不遵循技能默认深色 UI |
| 布局 | 纵向分层泳道（浏览器 → 接入 → 应用 → 领域 → 基础设施 → 外部） | 用户指定 |
| 内容密度 | 全包含（SAA 工作流 / RAG / Skill / Hook / 事件任务） | 用户指定 |
| 技术实现 | HTML + CSS 布局 + 单个覆盖 SVG 画连线，无 JS 无外部依赖 | 与仓库已有 `core-flow-diagram.html` 同族，可离线打开 |
| 语言 | 中文（组件名保留英文类名/端口名，职责用中文） | 与 wiki 文档一致 |
| common 模块 | 横向底座细条呈现（不占整层） | 它是被依赖的通用件，非纵向链路环节 |
| Nacos | 标注「可选 / 降级本地」 | 配置中 `NACOS_ADDR` 为空时降级 classpath 加载 |

## 5. Verification

1. 用浏览器打开 `file:///Users/Zhuanz/IdeaProjects/DingRingJ/system-architecture.html`，确认：
   - 7 个泳道全部渲染，无内容溢出 / 卡片重叠
   - 箭头连线与组件锚点对齐，无断线 / 错位
   - 文本可读（缩放 100% 与 80% 均无遮挡）
2. 核对图中组件名与源码一致（对照 02-架构与模块.md 与 03-核心链路图.md）
3. 打开 DevTools 确认无控制台报错（无 JS，理论上无错）
4. 提交前用 `open ./system-architecture.html`（macOS）快速预览
