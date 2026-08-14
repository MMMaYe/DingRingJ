# 三框架融合改造方案 vs 现状对比核对报告

* 核对对象：[three-frameworks-fusion-implementation-plan.md](file:///Users/Zhuanz/IdeaProjects/DingRingJ/.trae/documents/three-frameworks-fusion-implementation-plan.md)

* 核对日期：2026-08-10

* 核对方式：以方案各 Phase 的设计清单（新增/改造文件、DB 变更、prompt 模板、WS 协议）为基准，逐一与当前代码、schema、前端做静态比对

* 结论：**Phase 0/A/B/D/E/F/G 已完整落地；主要遗漏集中在 Phase C 的 6.3.5/6.3.6/6.3.7 三节**（消息标签+摘要、话题级画像、TOPIC\_STATUS 后端推送），另有 1 项方案外新增（SystemMessageMergeHook）。

***

## 一、总体落地情况总览

| Phase                              | 方案要点                                                                             | 状态     | 说明                                                                                                                                                                                                                                                                                                                          |
| ---------------------------------- | -------------------------------------------------------------------------------- | ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Phase 0 依赖升级                       | Spring Boot 3.5.16 + SAA 1.1.2.3 + Spring AI 1.1.2 + Java 21                     | ✅ 已落地  | [pom.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/pom.xml) 版本与 BOM 均已就位                                                                                                                                                                                                                                                 |
| Phase A SAA Model 层 + Nacos Prompt | SaaModelFactory / SpringAiLlmService / PromptTemplateLoader / prompt-config.json | ✅ 已落地  | [SaaModelFactory.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SaaModelFactory.java) 存在且有单测；prompt-config.json 已有 8 个模板（见差异 4）                                                                                                                    |
| Phase B 广播统一                       | GroupBroadcastService                                                            | ✅ 已落地  | 各节点/编排器统一经其广播 WS 事件                                                                                                                                                                                                                                                                                                         |
| Phase C StateGraph 重构              | 9 节点 + WorkflowConfig + SaaWorkflow + 三态循环                                       | ✅ 主体落地 | 节点骨架齐全；**但 6.3.5/6.3.6/6.3.7 三个子项遗漏**，见"差异"部分                                                                                                                                                                                                                                                                               |
| Phase D Toolkit + ReAct            | ReactAgent 工厂 / AgentSpeakerService / Hook / Tool                                | ✅ 已落地  | 与方案 [7.7 实施记录](file:///Users/Zhuanz/IdeaProjects/DingRingJ/.trae/documents/three-frameworks-fusion-implementation-plan.md) 一致（ToolSet 枚举、recursionLimit、Hook 迁移均按记录落地）；另有方案外新增 SystemMessageMergeHook（见差异 6）                                                                                                                |
| Phase E RAG                        | SaaRagService / LlmReranker / 摄取管线 / PGVector / MockEmbedding                    | ✅ 已落地  | rag 包 8 个文件齐全；存在已知问题 P0-1（见"已知问题"）                                                                                                                                                                                                                                                                                          |
| Phase F SKILL + Supervisor         | skill 表 + skill-config 种子 + SupervisorAgentFactory                               | ✅ 已落地  | <br />                                                                                                                                                                                                                                                                                                                      |
| Phase G WORK 闭环                    | WorkNode + WORK\_\* WS 常量 + WorkProgressBroadcastHook                            | ✅ 已落地  | [WsConstants.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-common/src/main/java/com/dingring/common/constant/WsConstants.java) 含 WORK\_PROGRESS/WORK\_RESULT/WORK\_CONFIRM\_REQUEST/WORK\_TASK\_STARTED                                                                                                        |
| 前端 DiscussionStatus                | 按 6.3.7 设计实现状态横幅                                                                 | ✅ 已落地  | [DiscussionStatus.tsx](file:///Users/Zhuanz/IdeaProjects/DingRingJ/frontend/src/components/DiscussionStatus.tsx) 完整（discussMode 映射/发散进度条/restartHint 提示条/收束按钮）；[Chat/index.tsx](file:///Users/Zhuanz/IdeaProjects/DingRingJ/frontend/src/pages/Chat/index.tsx#L321-L323) 已监听 `TOPIC_STATUS`。**但后端从未推送该事件，组件实际收不到数据**（见差异 3） |

***

## 二、遗漏 / 差异明细（重点）

### 差异 1（6.3.6）：消息标签 + 选择性摘要 —— 整体未落地

方案要求"每条消息打标签（KEY/NOISE/MARKER\_\*/VIEWPOINT）+ VIEWPOINT 异步 LLM 摘要"，用"观点摘要列表 + 近期窗口"替代"全量 200 条原文窗口"。现状 **全链路缺失**：

| 方案设计                                                                | 方案位置         | 现状                                                                                                                                                                                         |
| ------------------------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ALTER TABLE group_message ADD COLUMN tag/viewpoint`                | 6.3.6 DB 变更  | ❌ [schema.sql](file:///Users/Zhuanz/IdeaProjects/DingRingJ/start/src/main/resources/schema.sql#L62-L75) 的 `message` 表无 `tag`/`viewpoint` 列（仅通用 `feature` JSON 字段）                          |
| `MessageTag` 枚举                                                     | 6.3.6 domain | ❌ 不存在                                                                                                                                                                                      |
| `GroupMessage.tag / viewpoint` 字段                                   | 6.3.6        | ❌ 不存在                                                                                                                                                                                      |
| `MessageRepository.findViewpointsByTopicId / updateTagAndViewpoint` | 6.3.6        | ❌ 不存在                                                                                                                                                                                      |
| `MessageTagger`（app/orchestrator）                                   | 6.3.6        | ❌ 不存在                                                                                                                                                                                      |
| `MessageSummaryHandler`（app/event）                                  | 6.3.6        | ❌ [event 目录](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/event/) 仅有 CardEventHandler、ProfileEventHandler                                       |
| ContextBuilder 改为"观点列表 + 近期窗口"                                      | 6.3.6        | ❌ [ContextBuilder.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/ContextBuilder.java#L54-L80) 仍是 `context-window=200` 全量原文滑动窗口 |

**影响**：长讨论上下文 token 膨胀问题（方案指出 50 轮可达 10 万+ token）未解决；DiscussNode 仍把全量近 200 条原文塞给 LLM。

### 差异 2（6.3.7）：话题级用户画像 —— 整体未落地

方案要求 TopicClosed 后分析用户在该话题的表现写入 `user_topic_profile`，话题重启时 EnsureTopicNode 回溯历史生成 `userHistoryHint`/`restartHint`，DiscussNode 注入历史表现提示。现状 **全链路缺失**：

| 方案设计                                                 | 方案位置         | 现状                                                                                                                                                                                                                 |
| ---------------------------------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `user_topic_profile` 表（理解程度/薄弱点/亮点/建议）               | 6.3.7        | ❌ [schema.sql](file:///Users/Zhuanz/IdeaProjects/DingRingJ/start/src/main/resources/schema.sql) 无该表                                                                                                                |
| `UserTopicProfile` 实体 / Repository                   | 6.3.7 domain | ❌ [domain/user 目录](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-domain/src/main/java/com/dingring/domain/user/) 仅有 User、UserProfile、UserProfileRepository、UserRepository                                   |
| `TopicProfileEventHandler`（app/event，订阅 TopicClosed） | 6.3.7        | ❌ 不存在                                                                                                                                                                                                              |
| `StateKeys.USER_HISTORY_HINT`                        | 6.3.7        | ❌ [StateKeys.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-domain/src/main/java/com/dingring/domain/workflow/StateKeys.java#L25) 只有 RESTART\_HINT，无 USER\_HISTORY\_HINT                               |
| EnsureTopicNode 回溯历史提示                               | 6.3.7        | ❌ [EnsureTopicNode.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/EnsureTopicNode.java#L163) 的 restartHint 是静态文案"新话题「…」已开始，可以开始讨论"，无"第 N 次讨论/上次薄弱点"回溯 |
| DiscussNode 注入 userHistoryHint                       | 6.3.7        | ❌ 无相关逻辑                                                                                                                                                                                                            |

**影响**：Agent 无法感知用户的历史表现与成长轨迹，话题重启时引导仍是"泛泛而谈"，与全局画像的差异化价值（用户能力成长轨迹）缺失。

### 差异 3（6.3.7）：`TOPIC_STATUS` 后端推送缺失 —— 前端已就绪但收不到数据

| 环节    | 方案设计                                                                                                      | 现状                                                                                                                                                                                                                                                                                                                                                              |
| ----- | --------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 前端组件  | `DiscussionStatus` 横幅，按 TOPIC\_STATUS 实时更新                                                                | ✅ [DiscussionStatus.tsx](file:///Users/Zhuanz/IdeaProjects/DingRingJ/frontend/src/components/DiscussionStatus.tsx) 已完整实现；[Chat/index.tsx](file:///Users/Zhuanz/IdeaProjects/DingRingJ/frontend/src/pages/Chat/index.tsx#L321-L323) 已监听 `TOPIC_STATUS`；[types.ts](file:///Users/Zhuanz/IdeaProjects/DingRingJ/frontend/src/types.ts#L154) 已定义 TopicStatusPayload |
| WS 常量 | 新增 `TOPIC_STATUS`                                                                                         | ❌ [WsConstants.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-common/src/main/java/com/dingring/common/constant/WsConstants.java) 仅有 `TOPIC_STATUS_CHANGED`，**无** **`TOPIC_STATUS`**                                                                                                                                                                |
| 后端推送  | DiscussionEngine.runLoop 每次 advance 后广播 discussMode/topicTitle/divergeRounds/maxDivergeRounds/restartHint | ❌ 全项目 Java 代码中无任何 `"TOPIC_STATUS"` 广播调用（[DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java#L259) 只推 TOPIC\_STATUS\_CHANGED）                                                                                                                                     |

**影响**：前端 `DiscussionStatus` 横幅形同虚设——discussMode 标签、发散进度条、restartHint 提示条、CONCLUDE\_PROPOSED 收束按钮永远不会出现。这是**前端做了、后端漏了**的典型断链。另外方案还要求 TOPIC\_CREATED 携带 restartHint（前端 TOPIC\_CREATED 分支未消费该字段），链路也不完整。

### 差异 4（6.3.5）：Nacos prompt 模板缺 `message-viewpoint` 与 `topic-profile`

| 模板                  | 用途                    | 现状                                                                                                                |
| ------------------- | --------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `message-viewpoint` | 单条消息观点判断+摘要（6.3.6 依赖） | ❌ [prompt-config.json](file:///Users/Zhuanz/IdeaProjects/DingRingJ/start/src/main/resources/prompt-config.json) 无 |
| `topic-profile`     | 话题级用户表现分析（6.3.7 依赖）   | ❌ 无                                                                                                               |

现状 prompt-config.json 仅 8 个模板：chat-base、collaboration-protocol、intent-classify、conclude、moderator、sediment、profile-extract、rag-rerank。缺失的两个模板恰好是差异 1 / 差异 2 的 LLM 能力依赖。

### 差异 5（6.3.3 / 7.4-6）：`AgentEventTranslator` 未创建

方案 6.3.3 要求新增占位文件 `dingRing-infrastructure/.../workflow/AgentEventTranslator.java`（标注"Phase D 引入后激活"），7.4-6 又作为 Phase D 设计决策提及"ReactAgent 事件通过 AgentEventTranslator 转换为领域事件"。

* 现状：**该文件不存在**；全项目无匹配。

* 替代方案：Phase D 实际落地时各节点/编排器**直接发布领域事件**（如 [EnsureTopicNode.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/EnsureTopicNode.java#L156) 直接 `eventPublisher.publish(new TopicCreated(...))`），[DiscussNode.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/DiscussNode.java#L317-L318) 也直接发 MessageSent。

* 结论：**无功能缺口**，属"方案设计未落地但已有等价实现"。若后续需要按 ReactAgent 事件维度做翻译（如 AgentSelected/AgentFailed），可再补。

### 差异 6（方案外新增）：`SystemMessageMergeHook`

现状 hook 目录存在 [SystemMessageMergeHook.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/hook/SystemMessageMergeHook.java)，**不在方案 7.3.2 的 hook 清单内**（方案列出 MemoryInjectionHook/ProfileInjectionHook/StreamMarkerHook/ProfanityFilterHook，实施记录列出 Memory/Profile/GroupRoster/Rag/WorkProgress）。属实施期的额外补充，推测用于合并多个 SystemMessage 防止重复注入，建议在文档中补记，无问题。

***

## 三、已知问题（方案外，另行确认）

| 级别   | 问题          | 说明                                                                                                                                                                                                                   |
| ---- | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| P0-1 | RAG 向量维度不匹配 | PGVector 配置 768 维 vs [MockEmbeddingModel](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/rag/MockEmbeddingModel.java) 1536 维，检索静默降级。用户已明确"后面再改"，此处仅记录 |

***

## 四、核对结论与建议优先级

按影响面排序的修复建议（若决定补齐）：

1. **差异 3（TOPIC\_STATUS 后端推送）**：成本最低、收益最直接——前端组件已就绪，只需后端补常量 + DiscussionEngine 推送即可点亮整个状态横幅。**建议优先**。
2. **差异 1（消息标签+摘要）**：收益大（上下文 token 从 10 万降到 \~2500），但涉及 DB 列、枚举、Repository、异步处理器、ContextBuilder 改造，工作量最大。需先补 `message-viewpoint` 模板。
3. **差异 2（话题级画像）**：依赖 `topic-profile` 模板 + 新表 + 新实体 + TopicProfileEventHandler + EnsureTopicNode/DiscussNode 注入，属体验增强，可排在消息优化之后。
4. **差异 5（AgentEventTranslator）**：当前无功能缺口，建议仅在需要按 ReactAgent 事件做翻译时再补，或补一个占位保持一致。
5. **差异 4**：随差异 1/2 一并补齐模板即可。

> 注：本报告为静态比对结果，未执行运行时验证；"已落地"仅表示对应文件/逻辑存在，不代表行为完全符合方案预期（例如 TOPIC\_STATUS\_CHANGED 的 payload 字段与方案 TOPIC\_STATUS 设计不同属预期内的两套协议）。

