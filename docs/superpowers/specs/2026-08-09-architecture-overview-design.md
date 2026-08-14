# DingRingJ 综合总览架构图（手绘 SVG/CSS）设计文档

日期：2026-08-09
状态：已获用户批准（设计阶段）

## 背景与目标

DingRingJ 项目 wiki 中已有两张 Mermaid 架构图（01 章系统总览、02 章模块分层），项目根目录已有 `system-architecture.html`（2026-08-09 生成，手绘泳道式架构图，反映最新代码状态：含 SAA 工作流、RAG、Skill、Nacos、pgvector）。本设计**在原位增强该文件**：修正组件层归属错误、补充 hover 高亮与 tooltip 交互，产出面向新成员 onboarding 的零依赖单文件综合总览架构图。

## 已确认需求

| 维度 | 选择 |
|---|---|
| 受众 | 团队内部 / 新成员 onboarding |
| 图表类型 | 综合总览图（浏览器 → 六模块 → MySQL/LLM 网关，含关键数据流） |
| 粒度 | 组件级（模块 + 关键类/组件） |
| 输出格式 | HTML 单页（独立文件，非 wiki 内嵌） |

## 方案评估与选择

| 方案 | 描述 | 结论 |
|---|---|---|
| A. Mermaid.js 渲染 | 内嵌 Mermaid 源码 + mermaid.min.js | 可维护性最好但与 wiki 重复；自动布局可控性有限 |
| **B. 手绘 SVG/CSS（已选）** | 纯 HTML + 内联 SVG，零依赖，布局/配色完全可控 | 视觉精致、适合 onboarding 讲解；代价是布局硬编码 |
| C. 交互式架构图 | SVG + 缩放/聚焦/搜索 | 演示最佳但开发维护成本最高，超出"一张图"范畴 |

选择理由：受众为新成员，视觉表现力与讲解体验优先于"改源码即改图"的可维护性；零依赖单文件最便于分发。

## 详细设计

### 1. 图结构（自上而下）

```
浏览器 React SPA（Chat / Topics / Agents / Cards / KB 五页）
   │ REST /api/*（实线）      ⇄ WebSocket /ws/chat（双向）
Spring Boot 单体（start 模块 :8080）— 七泳道横向分层
   │ adapter 接入层
   │ app 应用层（含 SAA 工作流 StateGraph）
   │ domain 领域层
   │ infrastructure 基础设施层
   │ common 通用底座
   │ JDBC（实线）   JDBC（实线）   HTTP（实线）   可选
MySQL ring_chat    PG + pgvector    LLM 网关 ×N    Nacos / ./rag-files
```

### 2. 组件清单（组件级粒度，以实际代码为准）

**adapter 接入层**（`com.dingring.adapter`）
- REST：GroupController / TopicController / AgentController / CardController / KbController / SkillController / TestController（dev）+ GlobalExceptionHandler
- WebSocket：ChatWebSocketHandler / WsMessageDispatcher / WebSocketConfig

**app 应用层**（`com.dingring.app`）
- orchestrator：ChatOrchestrator、DiscussionEngine、SpeakerScheduler、Terminator、ModeratorService、ContextBuilder、MessageRouter、StreamMarkerGuard、MessageContext
- service：GroupAppService / TopicAppService / AgentAppService / CardAppService / KnowledgeBaseAppService / SkillAppService / MessageAssembler
- event：CardEventHandler、ProfileEventHandler
- task：CardReconciler、ConclusionWatchdog

**domain 领域层**（`com.dingring.domain`）
- 聚合实体：Group / Member / Message、Topic（TopicStatus）、KnowledgeCard、Agent、User / UserProfile、KnowledgeBase / File、Skill
- 仓储端口 ×10：Group / Message / Topic / Card / Agent / User / UserProfile / KnowledgeBase / File / Skill
- 服务端口 ×9：LlmService / MemoryService / ProfileService / RagService / Reranker / AgentSpeakerService / DiscussionFlowService / GroupBroadcastService / DomainEventPublisher
- 领域事件 ×9（含 DiscussionFlowResult 工作流契约）

**infrastructure 基础设施层**（`com.dingring.infrastructure`）
- 持久化：MyBatis Mapper ×10 + *RepositoryImpl ×10 + TypeHandler
- LLM：SpringAiLlmService、MockLlmService、SaaModelFactory（按 Agent 动态构建）
- 记忆/画像/事件：SimpleMemoryService、SimpleProfileService、SpringEventPublisher、@Event + EventAspect
- RAG：DocumentIngestionPipeline、SaaRagService、LlmReranker、MockEmbeddingModel、PgVectorStoreConfig
- Skill：SkillHotReloader、SkillLoaderServiceImpl、SkillToolkitFactory
- Agent 运行时：SaaReactAgentFactory、SupervisorAgentFactory、Hooks ×5、Tools ×3
- SAA 工作流：SaaWorkflow（StateGraph）、NodeHandlerRegistry、PromptTemplateLoader（Nacos 热更新）
- 文件存储：FileStorageService（./rag-files）
- WebSocket 推送：WsBroadcastAdapter（implements GroupBroadcastService）、WsSessionRegistryImpl

**common 通用底座**（`com.dingring.common`）：ApiResponse / PageResult / BizException / ErrorCode / ParamException / WsConstants / JsonHelper / DateUtil / LogHelper

**外部服务**：MySQL 8（ring_chat）、PostgreSQL + pgvector（my_rag）、LLM 网关（DeepSeek/StepFun/CloudBase）、Nacos（可选配置中心）、本地文件 ./rag-files

### 3. 数据流与连线规范

| 连线 | 样式 | 路径 |
|---|---|---|
| REST 调用 | 实线箭头 | FE → REST ×4 → AppService |
| WS 双向 | 实线双向 | FE ⇄ ChatWebSocketHandler → WsMessageDispatcher → ChatOrchestrator |
| 主循环 | 实线 | ChatOrchestrator → DiscussionEngine（标注"每群串行虚拟线程"） |
| 协作 | 实线 | DiscussionEngine → SpeakerScheduler / Terminator / ModeratorService / ContextBuilder |
| 端口调用 | 实线 | AppService / Orchestrator / Engine → domain 端口 |
| 端口实现 | 虚线 | domain 端口 ←运行时注入— infrastructure 实现（标注"依赖倒置"） |
| 出站推送 | 青色曲线回环 | Engine / EventHandler → ChatPusher / GroupBroadcastService → WsBroadcastAdapter → 浏览器（NEW_MESSAGE / MESSAGE_DELTA / TOPIC_*） |
| 持久化 | 实线 | Mapper → MySQL；RAG → PostgreSQL + pgvector（第二数据源） |
| LLM | 实线 | SpringAiLlmService → 各网关（OpenAI 协议）；Nacos 可选虚线 |

### 4. 视觉规范（沿用现有 system-architecture.html 的泳道配色体系）

- 七泳道语义色：前端 cyan / adapter coral / app green / domain blue / infra violet / common slate / external amber；泳道左侧竖排 lane-tag + 顶部 3px 色条
- 组件框：白底、圆角、泳道色描边；组件名 monospace 字体；服务端口用紫色虚线框（chip--port）、领域事件用琥珀虚线框（chip--evt）
- 图例区：说明七层语义色 + 实线（调用/数据流）+ 紫色虚线（端口实现）+ 青色曲线（WS 推送）
- 页面：浅色中性背景（`#f7f8fa`），居中容器，`.diagram` 固定 1320px 宽 + 页面自适应

### 5. 交互（增强项：现有 system-architecture.html 无交互，本次补充原生 JS，无依赖）

- hover 组件 → 高亮该组件 + 相邻连线 + 关联组件降透明度
- hover 显示 tooltip：组件职责说明（内容取自 docs/wiki/02-架构与模块.md 及实际代码）
- 页头标题 + 更新时间 + 图例（已有，保留）

### 6. 文件位置与维护

- 单文件：项目根目录 `system-architecture.html`（现有文件原位增强，保持文件名与路径不变）
- 布局坐标硬编码在 SVG 中；改动架构时同步更新 SVG 与 tooltip 文案
- 不纳入 wiki 文档体系（wiki 保持 Mermaid 图），本文件独立分发

### 7. 现有文件修正项

1. **层归属错误**：WsBroadcastAdapter / WsSessionRegistryImpl 实际在 infrastructure 层（`com.dingring.infrastructure.websocket`），实现 domain 的 GroupBroadcastService 端口；现有文件将其置于 adapter 泳道，需移入 infrastructure 泳道
2. **交互缺失**：现有文件无 hover 高亮与 tooltip，需补充
3. 其余结构（7 泳道、连线、图例、摘要卡片）经验证与代码一致，保留

## 验收标准

1. `system-architecture.html` 零依赖、双击浏览器直接打开（保持单文件）
2. 图中包含上述全部组件（组件级粒度，以实际代码为准），无遗漏
3. 三条关键链路可循图讲清：用户消息全链路、讨论引擎推进（SAA 工作流）、收束→知识卡片
4. hover 交互与 tooltip 正常；布局在 1280px+ 与移动宽度下均可读（缩放）
5. 与实际代码一致（组件命名、依赖方向、层归属）；WsBroadcastAdapter / WsSessionRegistryImpl 归位 infrastructure 泳道
