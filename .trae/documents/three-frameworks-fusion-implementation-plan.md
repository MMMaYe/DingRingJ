# DingRingJ 融合方案 — 可落码技术方案

> 基于架构方案 [three-frameworks-fusion-into-dingringj.md](file:///Users/Zhuanz/IdeaProjects/agentscope-java/.trae/documents/three-frameworks-fusion-into-dingringj.md)
> 目标:将架构方案转化为可直接编码实现的步骤,每个 Phase 决策完整、文件路径精确、API 签名有据

---

## 一、摘要

DingRingJ 当前基于 Spring Boot 3.4.7 + Spring AI 1.0.0,DDD 六模块分层。本方案将其升级到 **SAA 1.1.2.3**(Spring AI Alibaba),引入 StateGraph Workflow + ReactAgent + Nacos Prompt,分 7 个 Phase 逐步落地。

### 关键版本决策

| 组件 | 当前版本 | 目标版本 | 依据 |
|---|---|---|---|
| Spring Boot | 3.4.7 | 3.5.x (最新稳定) | SAA 1.1.2.3 要求 3.5.x |
| Spring AI | 1.0.0 | 1.1.2 | SAA 1.1.2.3 要求 1.1.2 |
| SAA | 无 | 1.1.2.3 | GitHub Releases 最新发布版(2026-05-11) |
| Java | 21 | 21(不变) | SAA 要求 17+,21 兼容 |

### SAA 1.1.2.3 提供的核心能力(替代原方案的 AgentScope + Spring AI 双框架)

| 原能力来源 | SAA 对应模块 | 说明 |
|---|---|---|
| AgentScope ReActAgent | `spring-ai-alibaba-agent-framework` 的 `ReactAgent` | SAA 自有 ReactAgent,原生集成 StateGraph(`asNode()`) |
| AgentScope Hook(已废弃) | SAA `AgentHook` / `ModelHook` / `ToolInterceptor` | SAA 的 Hook/Interceptor 体系,非废弃 |
| AgentScope MsgHub | SAA StateGraph 条件边 + OverAllState AppendStrategy | 无 MsgHub,用 StateGraph 实现等价的群聊广播 |
| AgentScope MultiAgentFormatter | SAA OverAllState messages(AppendStrategy) | 消息历史在 state 中累积,Agent 读取即可 |
| Spring AI ChatClient/Advisor | SAA 继承 Spring AI 全部能力 | ChatClient + Advisor 直接可用 |
| SAA StateGraph | `spring-ai-alibaba-graph-core` | 原生 StateGraph,非 AgentScope 间接 |
| SAA Nacos Prompt | `spring-ai-alibaba-starter-nacos-prompt` | ConfigurablePromptTemplateFactory |
| SAA VectorStore | Spring AI VectorStore(通过 SAA BOM) | PgVector 等 20+ 实现 |

### 依赖引入策略

用户选择:通过 SAA starter 引入 AgentScope。

```xml
<!-- 父 POM dependencyManagement 新增 -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-bom</artifactId>
    <version>1.1.2.3</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

SAA BOM 管理以下模块,按需引入:
- `spring-ai-alibaba-graph-core` — StateGraph(Phase C)
- `spring-ai-alibaba-agent-framework` — ReactAgent + Hook + Interceptor(Phase D)
- `spring-ai-alibaba-starter-nacos-prompt` — Nacos Prompt(Phase A)
- `spring-ai-alibaba-starter-agentscope` — AgentScope 集成(Phase B/D,可选)
- `spring-ai-alibaba-starter-dashscope` — DashScope 模型(可选,Phase A)

> **注意**:SAA 自有的 `ReactAgent`(`spring-ai-alibaba-agent-framework`)比 AgentScope 的 ReActAgent 更原生地集成 StateGraph。本方案以 SAA ReactAgent 为主,`spring-ai-alibaba-starter-agentscope` 作为补充(如需 AgentScope 特有功能)。

---

## 二、现状分析(基于代码探索)

### 2.0 术语对照表

方案中涉及大量概念,部分词汇在现状和方案中有不同含义,统一对照如下:

#### 框架与组件

| 术语 | 全称 | 含义 | 现状/方案中的角色 |
|---|---|---|---|
| **SAA** | Spring AI Alibaba | 阿里开源的 Spring AI 扩展框架,建立在 Spring AI 之上 | Phase 0 引入 1.1.2.3 版本,提供 StateGraph/ReactAgent/Nacos Prompt |
| **Spring AI** | - | Spring 官方的 AI 工程框架 | SAA 的底层依赖,DingRingJ 现状用 1.0.0,方案升级到 1.1.2 |
| **AgentScope** | - | 独立的 Agent 编程框架(有 Java 版) | 原架构方案考虑引入,本方案用 SAA 替代,仅保留 starter 作为补充 |
| **StateGraph** | - | SAA 的工作流编排引擎,基于图(节点+边+条件)驱动流程 | Phase C 用它替代 DiscussionEngine 的三态循环硬编码 |
| **ReactAgent** | - | SAA 的 ReAct(Reasoning+Acting)Agent,内部编译为 StateGraph 子图 | Phase D 引入,Agent 获得 Tool 调用 + Hook 干预能力 |
| **OverAllState** | - | StateGraph 的共享状态对象(Map + KeyStrategy),所有节点通过 key 读写 | Phase C 引入,替代散落的 GroupState 局部变量 |
| **NodeAction** | - | StateGraph 节点的执行接口,`apply(OverAllState) -> Map<String,Object>` | Phase C 的每个流程步骤(preprocess/discuss/conclude 等)都是一个 NodeAction |
| **MsgHub** | - | AgentScope 的消息总线(发布-订阅),Agent 间自动广播消息 | 原方案用,本方案用 OverAllState + GroupBroadcastService 替代 |
| **Nacos Prompt** | - | SAA 的提示词管理组件,支持 Nacos 配置中心热更新 | Phase A 引入,替代 PromptConstants 静态常量 |

#### 群聊业务概念

| 术语 | 含义 | 现状 | 方案变化 |
|---|---|---|---|
| **三态循环** | DiscussionEngine 的三种工作状态:闲聊/讨论/收束 | 硬编码 if-else + pace 随机延迟 | Phase C 映射为 StateGraph 条件分支 |
| **pace** | 讨论态 Agent 发言间隔 | 固定 5-15s 随机延迟 | 改为"收敛+发散"模型:收敛无延迟,发散 2s 防刷屏 |
| **收敛模式(CONVERGE)** | Agent 直接回答用户问题 | 无此概念(统一 pace) | Phase C 新增,用户驱动为主,无延迟 |
| **发散模式(DIVERGE)** | Agent 提出关联但非直接回答的新视角 | 无此概念(统一 pace) | Phase C 新增,轻量 pace + maxDivergeRounds 自动回拉 |
| **等待模式(WAIT)** | Agent 追问用户,阻塞等用户回答 | 无此概念 | Phase C 新增,`queue.take()` 阻塞 |
| **提议收束(CONCLUDE_PROPOSED)** | Agent 提议收束,等用户确认 | 无此概念(`[[CONCLUDE]]` 直接收束) | Phase C 新增,`[[CONCLUDE]]` 改为提议,用户确认后才正式收束,5 分钟超时兜底 |
| **收束(conclude)** | 结束讨论,生成 STAR 结论 | 4 种触发:CONCLUDE标记/PASS计数/用户要求/熔断 | 改为:用户确认提议/用户要求结束/熔断(去掉 PASS 计数,CONCLUDE 改为提议) |
| **信号折叠(fold)** | 用户连发多条消息时合并为一条 | 取最后一条内容 + @并集 | **Phase C 移除**(消息已入库,从 DB 拉完整上下文) |
| **意图分类** | 判断用户消息意图:CHAT/DISCUSS/CONCLUDE | MessageRouter + LLM | Phase C 迁移到 IntentClassifyNode,prompt 从 Nacos 加载 |
| **追溯建题** | 连续 LOW 置信度 DISCUSS 时补建 Topic | DiscussionEngine.ensureTopic | Phase C 迁移到 EnsureTopicNode,新增历史回溯 |
| **convergePassCount** | 连续 PASS 达阈值触发收束 | 默认 2 | **Phase C 去掉**(PASS 只影响选人,不触发收束) |
| **协作标记** | Agent 在发言中输出的控制信号 | `[[CONCLUDE]]` 直接收束 / `[[PASS]]` | Phase C:新增 `[[DIVERGE]]`/`[[ASK_USER]]`,`[[CONCLUDE]]` 改为提议(用户确认后才收束);Phase D 迁移为 Tool 调用 |
| **SpeakerScheduler** | 讨论态评分选人 | 保留 | Phase C 注入 DiscussNode,逻辑不变 |
| **ModeratorService** | LLM 全局判断(选人/收束) | 可选(`moderator.enabled=false`) | Phase C 保留,不再管 pace |
| **Topic** | 讨论主题,状态机:IN_PROGRESS/CONCLUDING/CLOSED/ARCHIVED | 同标题可多次创建(CLOSED 后重建) | 不变,新增话题级画像关联 |
| **UserSignal** | 用户消息信号(入队 DiscussionEngine) | content + mentionedAgentIds + repliedToAgentId | Phase C 保留,去掉 fold |

#### 提示词分层

| 术语 | 含义 | 存储 | 热更新 | 示例 |
|---|---|---|---|---|
| **层1 系统级** | Agent 人设 | DB `agent.system_prompt` | 是(DB 读取) | "你是柯南,擅长逻辑推理..." |
| **层2 流程级** | 编排流程 prompt | Nacos `prompt-config.json` | 是(Nacos 监听) | intent-classify / conclude / moderator |
| **层3 能力级** | Tool/Hook 内的 prompt | Nacos(编程式加载) | 是(Nacos 监听) | Hook prompt 直接 `promptLoader.render()`；Tool description 用编程式注册(运行时从 Nacos 加载,非注解硬编码) |
| **层4 运行时动态** | 群记忆/画像/成员名单 | 运行时生成 | 实时 | MemoryInjectionHook 注入群记忆 |

#### 消息标签与上下文

| 术语 | 含义 | Phase |
|---|---|---|
| **MessageTag** | 消息标签:KEY/NOISE/MARKER_DIVERGE/MARKER_QUESTION/MARKER_CONCLUDE/VIEWPOINT | Phase C 新增 |
| **KEY** | 用户消息,永远进入上下文 | - |
| **NOISE** | 无观点(规则+LLM双重判定),不进入上下文 | - |
| **MARKER_*** | 含协作标记的发言(`[[DIVERGE]]`/`[[ASK_USER]]`/`[[CONCLUDE]]`),标记即观点 | - |
| **VIEWPOINT** | 其他 Agent 发言,LLM 判断有观点则摘要 | - |
| **viewpoint** | 一句话观点摘要(30字),仅 VIEWPOINT 且 LLM 判定有观点时有 | Phase C 新增 |
| **观点列表** | 有 viewpoint 或 tag=KEY/MARKER_* 的消息集合,替代全量窗口 | Phase C 新增 |

#### 用户画像

| 术语 | 含义 | 现状 | 方案变化 |
|---|---|---|---|
| **全局画像** | 用户跨话题长期特征(表达习惯/情绪基调) | `user_profile.profile_text` 纯文本,增量合并 | 保留,对话输入过滤 NOISE |
| **话题级画像** | 用户在特定话题下的能力表现(理解程度/薄弱点/亮点/建议) | 无 | Phase C 新增 `user_topic_profile` 表,多条记录=进步轨迹 |
| **userHistoryHint** | 话题重启时注入 Agent 上下文的历史表现提示 | 无 | Phase C 新增,EnsureTopicNode 产出 |
| **restartHint** | 话题重启时推送给前端的一句话提示 | 无 | Phase C 新增,WS TOPIC_CREATED 携带 |

#### 架构分层

| 术语 | 含义 | 示例 |
|---|---|---|
| **domain 层** | 纯业务逻辑,不依赖任何框架 | Agent 实体 / LlmService 端口 / DiscussionFlowDefinition |
| **app 层** | 业务编排,不依赖 SAA | GroupChatFlowDefinition(流程定义) / MessageTagger / TopicProfileEventHandler |
| **infrastructure 层** | 技术实现,依赖 SAA | SaaWorkflow(StateGraph 实现) / DiscussNode(NodeAction) / PromptTemplateLoader |
| **adapter 层** | 外部接入(REST/WebSocket) | GroupController / ChatWebSocketHandler |
| **端口(Port)** | domain 层定义的接口,infra 层实现 | LlmService / GroupBroadcastService / DiscussionFlowService |

### 2.1 项目结构与版本

```
DingRingJ/
├── pom.xml                          # spring-boot-starter-parent:3.4.7, spring-ai:1.0.0, java:21
├── dingRing-common/                 # BizException, ApiResponse, WsConstants
├── dingRing-domain/                 # Agent实体, LlmService/MemoryService/ProfileService端口, 8个领域事件
├── dingRing-infrastructure/         # SpringAiLlmService, MockLlmService, SimpleMemoryService, MyBatis Repos
├── dingRing-app/                    # DiscussionEngine(~900行), ChatOrchestrator, SpeakerScheduler, ContextBuilder...
├── dingRing-adapter/                # GroupController, ChatWebSocketHandler, WsMessageDispatcher
└── start/                           # DingRingApplication, application.yml
```

### 2.2 核心类与交互链路

**LLM 调用路径**(当前):
- [SpringAiLlmService.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SpringAiLlmService.java) — 每次 `chat()` 动态构建 `OpenAiChatModel`
- `resolveUrl()` 解决双重版本号 404 坑(StepFun/CloudBase)
- `chatStream()` 用 `Flux.timeout` 块间超时

**三态循环**(当前):
- [DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java) — 每群一个虚拟线程 + `BlockingQueue<UserSignal>`
- 三态:闲聊(poll 空即退出) / 讨论(pace 随机推进) / 收束(tryConclude 退出让位)
- `handleSignal` -> `MessageRouter.route`(LLM 意图分类 CHAT/DISCUSS/CONCLUDE)
- `advanceDiscussion` -> `SpeakerScheduler.rank` + `ModeratorService.decide` + `speakOnce`
- `speakOnce` -> `ContextBuilder.build` + `chatWithRetry` + `StreamMarkerGuard` + WS 推送

**关键配置**(application.yml):
- `dingring.llm.mock: false`, `dingring.streaming.enabled: true`
- `dingring.orchestrator.context-window: 200`, `max-rounds: 100`, `auto-replies: 3`
- `pace-min-ms: 5000`, `pace-max-ms: 15000`, `converge-pass-count: 2`

### 2.3 需要迁移的提示词位置

| 位置 | 内容 | 迁移目标 |
|---|---|---|
| [PromptConstants.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java) | 意图分类/STAR总结/知识卡片/画像提炼/主持人决策 prompt | Nacos `prompt-config.json` |
| [ContextBuilder.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/ContextBuilder.java) | 群聊角色说明/记忆/画像拼接 | SAA Hook 注入(Phase C) |

---

## 三、Phase 0:版本升级(前置,所有 Phase 的基础)

### 3.1 目标

Spring Boot 3.4.7 -> 3.5.x,Spring AI 1.0.0 -> 1.1.2,引入 SAA 1.1.2.3 BOM。确保现有功能在升级后正常工作。

### 3.2 改动文件与内容

#### 3.2.1 父 POM

**文件**:[pom.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/pom.xml)

```xml
<properties>
    <java.version>21</java.version>
    <spring-ai.version>1.1.2</spring-ai.version>           <!-- 1.0.0 -> 1.1.2 -->
    <spring-ai-alibaba.version>1.1.2.3</spring-ai-alibaba.version>  <!-- 新增 -->
    <!-- spring-boot-starter-parent 版本在 parent 标签中改 -->
</properties>

<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.16</version>  <!-- 3.4.7 -> 3.5.x 最新稳定 -->
</parent>

<dependencyManagement>
    <dependencies>
        <!-- 新增:SAA BOM -->
        <dependency>
            <groupId>com.alibaba.cloud.ai</groupId>
            <artifactId>spring-ai-alibaba-bom</artifactId>
            <version>${spring-ai-alibaba.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <!-- 保留:Spring AI BOM(SAA BOM 已管理,显式声明确保版本) -->
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>${spring-ai.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

#### 3.2.2 infrastructure 模块 POM

**文件**:[dingRing-infrastructure/pom.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/pom.xml)

- `spring-ai-openai` 依赖保留(SAA BOM 管理版本,自动升级到 1.1.2)
- 暂不新增 SAA 依赖(Phase A 再加)

#### 3.2.3 Spring AI 1.0 -> 1.1 Breaking Changes 检查

需验证以下 API 变更(基于 Spring AI 1.1 changangelog):
- `OpenAiApi.builder()` API 是否变化
- `OpenAiChatModel.builder()` API 是否变化
- `Prompt` / `Message` 类是否有 breaking change
- `ChatResponse.getResult().getOutput().getText()` 是否仍可用

### 3.3 验证步骤

1. `mvn clean compile` — 全模块编译通过
2. `mvn test` — 所有现有单测绿
3. 手动测试:启动应用,发消息,验证群聊发言正常
4. 验证 `resolveUrl` 逻辑在 Spring AI 1.1.2 下仍正确

### 3.4 回退方案

Git revert 即可回到 3.4.7 + 1.0.0。

---

## 四、Phase A:SAA Model 层替换 + Nacos Prompt 基础设施

### 4.1 目标

用 SAA 的 Model 层替换 `SpringAiLlmService`,同时引入 Nacos Prompt 基础设施。最小改动,验证 SAA 兼容性。

### 4.2 改动范围

#### 4.2.1 依赖变更

**文件**:[dingRing-infrastructure/pom.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/pom.xml)

```xml
<!-- 新增:SAA Nacos Prompt -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-nacos-prompt</artifactId>
</dependency>
<!-- 新增:SAA Graph Core(Phase A 只引入依赖,Phase C 才用) -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-graph-core</artifactId>
</dependency>
```

**文件**:[start/pom.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/start/pom.xml)

```xml
<!-- 新增:Nacos Client(如不在 SAA starter 传递依赖中) -->
<dependency>
    <groupId>com.alibaba.nacos</groupId>
    <artifactId>nacos-client</artifactId>
</dependency>
```

#### 4.2.2 LLM Model 层

**新增文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SaaLlmService.java` -- **不需要,直接改 SpringAiLlmService**

```java
package com.dingring.infrastructure.llm;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * SAA Model 层实现 LlmService 端口。
 * 内部用 Spring AI 的 OpenAiChatModel(通过 SAA BOM 管理版本)。
 * 兼容 DeepSeek/StepFun/智谱等 OpenAI 兼容厂商。
 */
@Service
@ConditionalOnProperty(name = "dingring.llm.provider", havingValue = "saa", matchIfMissing = false)
public class SaaLlmService implements LlmService {

    private final SaaModelFactory modelFactory;

    // chat(Agent, systemPrompt, messages, CallOptions) — 迁移 SpringAiLlmService 的 buildChatModel 逻辑
    // chatStream(Agent, systemPrompt, messages, onDelta) — 迁移流式逻辑
    // 核心区别:用 SaaModelFactory 构建 OpenAiChatModel(而非直接 new)
}
```

**新增文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SaaModelFactory.java` -- **需要,从 SpringAiLlmService 提取模型构建逻辑**

```java
package com.dingring.infrastructure.llm;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService.CallOptions;
import org.springframework.stereotype.Component;

/**
 * 按 Agent 领域实体配置构建 Spring AI OpenAiChatModel。
 * 迁移 SpringAiLlmService.resolveUrl() 兼容逻辑。
 * 迁移 SpringAiLlmService.buildChatModel() 构建逻辑。
 */
@Component
public class SaaModelFactory {

    /**
     * 构建 OpenAiChatModel,参数优先级:CallOptions > Agent 配置 > 全局默认。
     * 复用 SpringAiLlmService.resolveUrl() 的 baseUrl 拆分逻辑。
     */
    public OpenAiChatModel buildChatModel(Agent agent, CallOptions options) {
        // 1. resolveUrl(agent.getBaseUrl()) — 直接迁移 SpringAiLlmService 的静态方法
        // 2. 构建 OpenAiApi.builder().baseUrl().completionsPath().apiKey()...
        // 3. 构建 OpenAiChatOptions(temperature/maxTokens/responseFormat)
        // 4. 返回 OpenAiChatModel.builder().openAiApi().defaultOptions().build()
    }
}
```

**改造文件**:[SpringAiLlmService.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SpringAiLlmService.java)

- 添加 `@ConditionalOnProperty(name = "dingring.llm.provider", havingValue = "spring-ai", matchIfMissing = true)` -- **不需要条件装配,直接去掉 @ConditionalOnProperty,成为默认实现**
- `resolveUrl()` 和 `buildChatModel()` **提取到 `SaaModelFactory`**(Phase D 的 `ReactAgentFactory` 也需要构建 Model,工厂复用避免重复 resolveUrl 逻辑)
- `SpringAiLlmService` 注入 `SaaModelFactory`,`chat()`/`chatStream()` 只管调用,不管模型构建
- 功能不变,仅调整装配条件

**保留文件**:[MockLlmService.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/MockLlmService.java) — 不动

#### 4.2.3 提示词管理:现状对比与细化方案

##### 现状:提示词散落在 5 个位置,无分层管理

**位置 1:[PromptConstants.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java) - 7 个静态常量**

| 常量名 | 内容摘要 | 变量占位 | 调用方 |
|---|---|---|---|
| `CHAT_BASE` | 通用对话 system prompt(花名+发言要求) | `%s`=agentName | `ContextBuilder.buildSystemPrompt()` |
| `GROUP_MEMBERS_HEADER` | 群成员名单前置说明 | 无 | `ContextBuilder.buildMemberRoster()` |
| `COLLABORATION_PROTOCOL` | [[CONCLUDE]]/[[PASS]] 协作协议 | 无 | `ContextBuilder.build()` 讨论态追加 |
| `CONCLUSION_STAR` | STAR 框架总结 prompt | `%s`=topicTitle | `ContextBuilder.buildForConclusion()` |
| `INTENT_CLASSIFIER` | 意图分类(CHAT/DISCUSS/CONCLUDE) | 无(示例硬编码) | `MessageRouter.route()` |
| `HOST_DECISION` | 主持人决策 prompt | 无 | `ModeratorService.decide()` |
| `KNOWLEDGE_EXTRACT` | 知识卡片提取 | 无 | `CardEventHandler.generateCards()` |
| `USER_PROFILE_EXTRACT` | 用户画像提炼 | 无 | `SimpleProfileService.extractAndMerge()` |

特点:Java 常量,改 prompt 必须改代码重新编译部署。`%s` 占位用 `String.format` 替换,类型不安全。

**位置 2:[ContextBuilder.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/ContextBuilder.java) - 运行时动态拼接**

`buildSystemPrompt()` 方法拼接顺序(第 144-163 行):
```
Agent.systemPrompt(DB)          ← Agent 人设
+ CHAT_BASE(%s=agentName)       ← 通用对话
+ GROUP_MEMBERS_HEADER + roster ← 群成员名单(花名+简介,发言者标"你")
+ memoryService.retrieveMemory  ← 群记忆(历史5条结论文本)
+ profileService.getProfile     ← 用户画像(跨群长期特征)
```

`buildForConclusion()` 方法拼接(第 124-139 行):
```
Agent.systemPrompt(DB)
+ CONCLUSION_STAR(%s=topicTitle)
+ memoryService.retrieveMemory  ← 群记忆(不注入画像)
```

特点:拼接逻辑硬编码在 Java 方法中,顺序不可配置,无法热更新。

**位置 3:各调用方动态构建 user message(非 system prompt)**

| 调用方 | 动态拼接内容 | 代码位置 |
|---|---|---|
| `MessageRouter.route()` | `"(当前群里正在讨论主题「xxx」)\n用户消息：xxx"` | 第 67-69 行 |
| `ModeratorService.buildInput()` | roster + dialogue(成员名单+发言次数+最近记录) | 第 108-121 行 |
| `CardEventHandler.generateCards()` | `"讨论主题：xxx\n\n讨论结论：\nxxx"` | 第 65-66 行 |
| `SimpleProfileService.extractAndMerge()` | `"已有画像：xxx\n\n最近对话：xxx"` | 第 48-49 行 |

特点:user message 拼接格式散落各处,改格式要改多个文件。

**位置 4:Agent.systemPrompt(DB 字段)**

`agent` 表的 `system_prompt` 列,每个 Agent 独立的人设。前端 Agent 管理页面可编辑。这部分已经是"DB 管理 + 热更新",不需要迁移。

**位置 5:ContextBuilder 标记符常量**

`CONCLUDE_MARKER = "[[CONCLUDE]]"` / `PASS_MARKER = "[[PASS]]"` - 协作标记,非 prompt 内容,是编排控制信号。

##### 现状问题总结

| 问题 | 影响 |
|---|---|
| 7 个 prompt 硬编码在 Java 常量中 | 改 prompt 必须改代码、编译、部署,非技术人员无法修改 |
| `%s` 占位用 `String.format`,类型不安全 | 参数顺序错位不会报错,产出乱码 prompt |
| ContextBuilder 拼接顺序硬编码 | 无法调整注入顺序(如想先注入画像再注入记忆) |
| user message 格式散落 4 处 | 改一个格式要找 4 个文件 |
| 无版本管理 | prompt 改了无法回滚到上一版 |
| 无 A/B 测试 | 无法对比两个 prompt 版本效果 |

##### 目标:四层提示词管理

```
┌─ 层1:系统级(Agent 人设)─────────────────────────────────────┐
│  存储:agent 表 system_prompt 字段(DB)                       │
│  管理:前端 Agent 管理页面可编辑(已有,不动)                  │
│  注入:Phase D ReactAgent.builder().systemPrompt(agent.xxx)   │
│  热更新:已支持(DB 读取,每次调用最新)                         │
│  迁移:无,保持现状                                             │
└──────────────────────────────────────────────────────────────┘
┌─ 层2:流程级(Nacos Prompt 热更新)──────────────────────────┐
│  存储:Nacos 配置中心 prompt-config.json                     │
│  管理:Nacos 控制台 GUI(无需重启即可热更新)                  │
│  注入:PromptTemplateLoader.render(name, vars)               │
│  热更新:Nacos 监听器自动推送,无需重启                         │
│  本地兜底:classpath:/prompt-config.json(无 Nacos 时降级)    │
│  迁移源:PromptConstants 的 7 个常量                           │
└──────────────────────────────────────────────────────────────┘
┌─ 层3:能力级(Tool/Hook, Nacos 编程式加载)──────────────────┐
│  存储:Nacos `prompt-config.json`(与层2 同文件)              │
│  注入:Hook 内 `promptLoader.render()`;Tool 用编程式注册      │
│       (运行时从 Nacos 加载 description,非注解硬编码)         │
│  热更新:是(Nacos 监听,调优 Tool 选择准确率 / Hook 策略)    │
│  迁移:Phase D 新增,现阶段无                                  │
└──────────────────────────────────────────────────────────────┘
┌─ 层4:运行时动态(Hook 生成)────────────────────────────────┐
│  存储:无,运行时生成                                          │
│  注入:Phase D ModelHook.beforeModel() 注入                   │
│  内容:群记忆 / 用户画像 / 群成员名单 / 讨论记录               │
│  迁移源:ContextBuilder.buildSystemPrompt() 的拼接逻辑         │
└──────────────────────────────────────────────────────────────┘
```

##### 迁移映射:现状 -> 目标(按 Phase 分步)

| 现有 prompt | 现有位置 | 迁移到 | 迁移 Phase | 变量变化 |
|---|---|---|---|---|
| `CHAT_BASE` | PromptConstants 常量 | **层2 Nacos** `chat-base` | Phase C | `%s` -> `{agentName}` |
| `GROUP_MEMBERS_HEADER` | PromptConstants 常量 | **层4 Hook** `GroupRosterHook` | Phase D | 拼接逻辑移到 Hook |
| `COLLABORATION_PROTOCOL` | PromptConstants 常量 | **层2 Nacos** `collaboration-protocol` | Phase C | 无变量 |
| `CONCLUSION_STAR` | PromptConstants 常量 | **层2 Nacos** `conclude` | Phase C | `%s` -> `{topicTitle}` |
| `INTENT_CLASSIFIER` | PromptConstants 常量 | **层2 Nacos** `intent-classify` | Phase C | 无变量(示例内嵌) |
| `HOST_DECISION` | PromptConstants 常量 | **层2 Nacos** `moderator` | Phase C | 无变量 |
| `KNOWLEDGE_EXTRACT` | PromptConstants 常量 | **层2 Nacos** `sediment` | Phase C | 无变量 |
| `USER_PROFILE_EXTRACT` | PromptConstants 常量 | **层2 Nacos** `profile-extract` | Phase C | 无变量 |
| Agent.systemPrompt | DB agent 表 | **层1 DB**(不动) | - | - |
| 群记忆拼接 | ContextBuilder.buildSystemPrompt | **层4 Hook** `MemoryInjectionHook` | Phase D | 运行时 memoryService.retrieve |
| 用户画像拼接 | ContextBuilder.buildSystemPrompt | **层4 Hook** `ProfileInjectionHook` | Phase D | 运行时 profileService.getProfile |
| 群成员名单拼接 | ContextBuilder.buildMemberRoster | **层4 Hook** `GroupRosterHook` | Phase D | 运行时遍历群成员 |
| MessageRouter user message | MessageRouter.route 第67行 | **层2 Nacos** `intent-classify-input` | Phase C | `{activeTopicTitle}` + `{content}` |
| Moderator user message | ModeratorService.buildInput | **层2 Nacos** `moderator-input` | Phase C | `{roster}` + `{dialogue}` |
| Card user message | CardEventHandler 第65行 | **层2 Nacos** `sediment-input` | Phase C | `{title}` + `{conclusion}` |
| Profile user message | SimpleProfileService 第48行 | **层2 Nacos** `profile-extract-input` | Phase C | `{existing}` + `{dialogue}` |

##### Phase A:只建基础设施,不迁移 prompt

Phase A 引入 `PromptTemplateLoader` 和 `prompt-config.json`,但**不修改任何调用方代码**。现有 `PromptConstants` 常量继续使用。`PromptTemplateLoader` 可被注入但暂无调用方。

**新增文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/prompt/PromptTemplateLoader.java`

```java
package com.dingring.infrastructure.prompt;

import com.alibaba.cloud.ai.prompt.ConfigurablePromptTemplateFactory;
import com.alibaba.cloud.ai.prompt.ConfigurablePromptTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 封装 SAA ConfigurablePromptTemplateFactory。
 * 提供 render(name, vars) 接口,从 Nacos 实时加载最新模板(热更新)。
 * 无 Nacos 时降级为 classpath:/prompt-config.json。
 */
@Component
@ConditionalOnClass(ConfigurablePromptTemplateFactory.class)
public class PromptTemplateLoader {

    private final ConfigurablePromptTemplateFactory factory;

    /**
     * 渲染模板。
     * @param name 模板名(对应 Nacos prompt-config.json 的 name 字段)
     * @param vars 模板变量(如 {agentName} -> "柯南")
     * @return 渲染后的 prompt 字符串;模板不存在返回空字符串
     */
    public String render(String name, Map<String, Object> vars) {
        try {
            ConfigurablePromptTemplate tpl = factory.create(name, getDefaultTemplate(name));
            Prompt prompt = tpl.create(vars);
            return prompt.getContents();
        } catch (Exception e) {
            // 模板加载失败不阻塞主流程,返回空字符串由调用方降级
            return "";
        }
    }

    /**
     * 无参便捷方法(无变量模板)。
     */
    public String render(String name) {
        return render(name, Map.of());
    }

    /** 本地兜底模板(无 Nacos 时从 classpath:/prompt-config.json 加载) */
    private String getDefaultTemplate(String name) {
        // 从 classpath 加载 prompt-config.json,按 name 查找对应 template
        // 如果本地也找不到,返回空字符串(调用方降级到 PromptConstants 常量)
    }
}
```

**新增文件**:`start/src/main/resources/prompt-config.json`

与 Phase A 的 `prompt-config.json` 内容一致(见下方),但**Phase A 阶段这些模板尚未被调用**,仅作为 Nacos 基础设施的本地兜底文件。Phase C 迁移时直接使用。

```json
[
  {
    "name": "chat-base",
    "template": "你正在参与一个多人群聊讨论，你的花名是「{agentName}」。历史消息以「花名: 内容」形式给出。\n发言要求：\n- 直接输出你的发言内容，不要重复花名前缀\n- 每次发言 3-5 句话（约 100-200 字），像真实群聊一样简短聚焦\n- 可用 Markdown 加粗、列表等基本格式，但不要用标题（#）\n- 与前面的讨论自然衔接，不要重复已有观点\n- HTML和SVG画图不计算在总字数内"
  },
  {
    "name": "collaboration-protocol",
    "template": "协作协议（严格遵守）：\n\n1. 直接回答用户问题或围绕用户问题给出观点时，正常输出内容即可（默认收敛模式）。\n\n2. 当你提出与用户问题相关但非直接回答的新视角、新方向时，在发言末尾另起一行，精确输出标记 [[DIVERGE]]\n   示例：\n   说到多实例，你们有没有考虑过用 CDN 缓存静态数据？\n   [[DIVERGE]]\n\n3. 当你需要向用户提问以获取更多信息时，在发言末尾另起一行，精确输出标记 [[ASK_USER]]\n   示例：\n   这个量级两者都行。你目前的 QPS 大概多少？\n   [[ASK_USER]]\n\n4. 认为讨论已充分、可以总结时，在发言末尾另起一行，精确输出标记 [[CONCLUDE]]\n   注意：输出此标记后系统会询问用户是否确认结束，由用户最终决定是否收束，你只是在提议收束。\n   示例：\n   我觉得可以收尾了，大家的观点基本对齐。\n   [[CONCLUDE]]\n   其他情况绝不要输出或提及该标记。\n\n5. 对当前讨论没有新观点时，只输出 [[PASS]]，不输出任何其他内容\n   示例：[[PASS]]\n   有实质内容时绝不要输出该标记。不要为了发言而发言。"
  },
  {
    "name": "intent-classify",
    "template": "你是群聊意图分类器。请判断用户这条群聊消息的意图，三选一：\n- CONCLUDE = 用户希望对当前正在进行的讨论做总结/收尾/出结论（仅在当前有进行中的讨论时才可能成立）；\n- DISCUSS = 用户抛出了一个值得群成员展开讨论的话题/问题/求助，或正在深入推进当前话题；\n- CHAT = 日常寒暄、闲聊、情绪表达、简短应答等其他内容。\n置信度 confidence 判定：\n- HIGH = 意图明确无歧义（如清晰的提问/求助、明确的\"总结一下\"、无实质内容的纯寒暄）；\n- LOW = 意图模糊、模棱两可、可讨论也可闲聊时。对 DISCUSS 尤其重要：只有确信这是一个值得独立开题讨论的话题时才给 HIGH，含糊的随口一说给 LOW。\ntopicTitle 拟定规则（仅 DISCUSS，其他意图为空字符串）：\n- 用名词短语概括讨论的核心议题，提炼关键词（如\"缓存方案选型\"），不要照抄原句；\n- 20 字以内，不含标点、语气词和\"怎么办\"\"求助\"等口语化表达；\n- 尽量具体可区分，避免\"技术问题\"\"一个想法\"等过泛标题（同一群内标题需可辨识）。\n参考示例：\n- \"哈哈哈是的\" -> {\"intent\":\"CHAT\",\"topicTitle\":\"\",\"confidence\":\"HIGH\"}\n- \"我们项目该用 Redis 还是本地缓存？\" -> {\"intent\":\"DISCUSS\",\"topicTitle\":\"缓存方案选型\",\"confidence\":\"HIGH\"}\n- \"最近总睡不好，大家有什么改善睡眠的办法吗\" -> {\"intent\":\"DISCUSS\",\"topicTitle\":\"睡眠质量改善方法\",\"confidence\":\"HIGH\"}\n- \"那就先这样吧，帮我总结下结论\" -> {\"intent\":\"CONCLUDE\",\"topicTitle\":\"\",\"confidence\":\"HIGH\"}\n- \"这个方案有什么问题吗\" -> {\"intent\":\"DISCUSS\",\"topicTitle\":\"方案问题分析\",\"confidence\":\"LOW\"}\n- \"我最近在学 Go，感觉挺有意思的\" -> {\"intent\":\"CHAT\",\"topicTitle\":\"\",\"confidence\":\"LOW\"}\n严格输出一行 JSON，不要输出任何其他内容，格式：\n{\"intent\": \"CHAT|DISCUSS|CONCLUDE\", \"topicTitle\": \"按上述规则拟定的话题标题，非 DISCUSS 为空字符串\", \"confidence\": \"HIGH|LOW\"}"
  },
  {
    "name": "conclude",
    "template": "你被推选为本次群讨论的总结者。请针对主题「{topicTitle}」，基于完整讨论记录，用 STAR 框架输出 Markdown 格式的讨论结论。\n\n格式要求：\n## Situation（背景）\n一段话概述讨论的起因和背景。\n\n## Task（任务）\n讨论要解决的核心问题。\n\n## Action（行动）\n用列表归纳各方观点（标注发言人花名）：\n- **花名**：观点摘要\n- **花名**：观点摘要\n\n## Result（结论）\n达成的共识、未解决的分歧、后续建议。\n\n全文控制在 500 字以内。"
  },
  {
    "name": "moderator",
    "template": "你是一场多人群聊讨论的主持人。请根据讨论主题、成员介绍、已发言次数和最近的讨论记录，判断：1. 讨论是否还有信息增量（还有值得展开的新角度/未充分讨论的分歧）；2. 下一位最能推进讨论的发言者（必须从成员花名中选，倾向让发言少的、视角互补的人开口）；3. 是否已经可以收尾出结论。严格输出一行 JSON，不要输出任何其他内容，格式：{\"should_continue\": true|false, \"next_speaker\": \"成员花名\", \"should_conclude\": true|false, \"guidance\": \"给下一位发言者的一句话引导，可为空字符串\"}"
  },
  {
    "name": "sediment",
    "template": "你是知识卡片提取助手。请从给定的讨论结论中提取知识卡片（Q&A），并为每张卡片识别分类。严格输出 JSON 数组，不要输出任何其他内容，格式：[{\"question\": \"...\", \"answer\": \"...\", \"category\": \"...\"}]"
  },
  {
    "name": "profile-extract",
    "template": "你是用户画像分析师。请基于「已有画像」与「最近一段群聊对话」，输出更新后的用户画像。\n关注用户的表达习惯、情绪基调、决策偏好、思考方式等长期稳定特征。\n画像是跨群的全局特征，不要把特定群的讨论话题当作用户的长期特征。\n\n严格按以下格式输出 3-6 条，不要输出任何其他内容：\n- 第一条特征描述\n- 第二条特征描述\n- ..."
  }
]
```

##### Phase C:正式迁移(调用方改用 Nacos)

Phase C 迁移时,每个调用方的改动模式:

**改动前(MessageRouter.route 第 70 行)**:
```java
String raw = llmService.chat(judge, PromptConstants.INTENT_CLASSIFIER,
    List.of(LlmService.ChatTurn.user(userInput)),
    new LlmService.CallOptions(ROUTE_TEMPERATURE, ROUTE_MAX_TOKENS, ...));
```

**改动后**:
```java
// systemPrompt 从 Nacos 加载(热更新)
String systemPrompt = promptTemplateLoader.render("intent-classify");
String raw = llmService.chat(judge, systemPrompt,
    List.of(LlmService.ChatTurn.user(userInput)),
    new LlmService.CallOptions(ROUTE_TEMPERATURE, ROUTE_MAX_TOKENS, ...));
```

**变量替换改动(ContextBuilder.buildForConclusion 第 130 行)**:

改动前:
```java
sp.append(String.format(PromptConstants.CONCLUSION_STAR, topicTitle));
```

改动后:
```java
String prompt = promptTemplateLoader.render("conclude", Map.of("topicTitle", topicTitle));
sp.append(prompt);
```

**每个调用方的具体迁移**:

| 调用方 | 改动行 | 改动内容 |
|---|---|---|
| `MessageRouter.route()` | 第 70 行 | `PromptConstants.INTENT_CLASSIFIER` -> `promptTemplateLoader.render("intent-classify")` |
| `ModeratorService.decide()` | 第 81 行 | `PromptConstants.HOST_DECISION` -> `promptTemplateLoader.render("moderator")` |
| `ContextBuilder.buildSystemPrompt()` | 第 149 行 | `String.format(CHAT_BASE, agentName)` -> `render("chat-base", Map.of("agentName", agentName))` |
| `ContextBuilder.build()` | 第 84 行 | `PromptConstants.COLLABORATION_PROTOCOL` -> `render("collaboration-protocol")` |
| `ContextBuilder.buildForConclusion()` | 第 130 行 | `String.format(CONCLUSION_STAR, topicTitle)` -> `render("conclude", Map.of("topicTitle", topicTitle))` |
| `CardEventHandler.generateCards()` | 第 64 行 | `PromptConstants.KNOWLEDGE_EXTRACT` -> `render("sediment")` |
| `SimpleProfileService.extractAndMerge()` | 第 50 行 | `PromptConstants.USER_PROFILE_EXTRACT` -> `render("profile-extract")` |

**PromptConstants.java 改造**:
- 7 个 prompt 常量标记 `@Deprecated`
- 保留 `CONCLUDE_MARKER` / `PASS_MARKER` 标记符常量(非 prompt,是控制信号)
- 保留 `stripMarkers()` / `stripConcludeMarker()` 工具方法

##### Phase D:运行时动态提示词迁移到 Hook

Phase D 引入 ReactAgent 后,ContextBuilder 的动态拼接逻辑迁移到 Hook:

**群成员名单** -> `GroupRosterHook`(ModelHook.beforeModel):
```java
// 改动前:ContextBuilder.buildMemberRoster() 拼接到 systemPrompt
// 改动后:Hook 在模型调用前注入
@Override
public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
    List<Agent> members = groupAppService.getMembers(state.value("groupId"));
    Agent self = agentRepository.findById(state.value("speakerAgentId"));
    String roster = buildMemberRoster(self, members);  // 复用现有逻辑
    // 注入到 systemPrompt
    return CompletableFuture.completedFuture(Map.of("systemPromptAppend", roster));
}
```

**群记忆** -> `MemoryInjectionHook`(ModelHook.beforeModel):
```java
// 改动前:ContextBuilder.buildSystemPrompt() 第 153-156 行拼接 memoryService.retrieveMemory
// 改动后:Hook 注入
String memory = memoryService.retrieveMemory(groupId);
// 追加到 systemPrompt
```

**用户画像** -> `ProfileInjectionHook`(ModelHook.beforeModel):
```java
// 改动前:ContextBuilder.buildSystemPrompt() 第 158-161 行拼接 profileService.getProfile
// 改动后:Hook 注入
String profile = profileService.getProfile(DEFAULT_USER_ID);
// 追加到 systemPrompt
```

**ContextBuilder 改造**:
- `buildSystemPrompt()` 拼接逻辑退役(群成员/记忆/画像交给 Hook)
- `buildMemberRoster()` / `memberIntro()` 迁移到 `GroupRosterHook`
- `toTurns()` 保留(消息 -> 对话轮次转换,非 prompt 拼接)
- `buildForConclusion()` 简化:只拼 Agent 人设 + Nacos `conclude` 模板,记忆交给 Hook

##### 占位符语法对比

| 维度 | 现状 | 目标(Nacos Prompt) |
|---|---|---|
| 占位语法 | `%s`(Java `String.format`) | `{varName}`(SAA `ConfigurablePromptTemplate`) |
| 类型安全 | 否(参数顺序错位不报错) | 是(按名称匹配) |
| 缺失变量 | `MissingFormatArgumentException` | 保留原样 `{varName}` |
| 多变量 | `%s` 顺序依赖,易错 | `{agentName}` / `{topicTitle}` 按名引用 |

##### 热更新对比

| 维度 | 现状(Java 常量) | 目标(Nacos Prompt) |
|---|---|---|
| 修改 prompt | 改 Java 代码 -> 编译 -> 部署 | Nacos 控制台改 -> 即时生效 |
| 重启 | 需要 | 不需要 |
| 版本管理 | Git log | Nacos 自带版本 + 回滚 |
| A/B 测试 | 难 | Nacos 灰度发布 |
| 非技术人员改 | 不能(需懂 Java) | 能(Nacos 控制台 GUI) |
| 审计日志 | Git commit | Nacos 操作日志 |

#### 4.2.4 配置

**文件**:[application.yml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/start/src/main/resources/application.yml)

```yaml
dingring:
  llm:
    # 不需要 provider 配置,SpringAiLlmService 直接用 SAA(Spring AI 1.1.2)
    mock: false            # mock=true 时用 MockLlmService 离线演示

spring:
  ai:
    nacos:
      prompt:
        template:
          enabled: true    # 开启 Nacos prompt 监听
  config:
    import:
      - "optional:nacos:prompt-config.json"  # Nacos Data ID,optional 表示无 Nacos 时不报错
  nacos:
    # 本地开发可不配,Nacos Prompt 自动降级为 classpath:/prompt-config.json
    server-addr: ${NACOS_ADDR:}
    username: ${NACOS_USERNAME:nacos}
    password: ${NACOS_PASSWORD:nacos}
```

### 4.3 验证步骤

1. 启动应用,群聊发言行为与升级前一致(SpringAiLlmService 直接用 SAA 1.1.2)
2. `dingring.llm.mock=true` 离线演示正常
4. Nacos Prompt:修改 Nacos 中 `prompt-config.json`,不重启,`PromptTemplateLoader.render()` 返回新模板
5. 无 Nacos 时:`classpath:/prompt-config.json` 正常加载
6. `resolveUrl` 兼容性:DeepSeek(无路径)/ StepFun(带路径前缀)均正常
7. 所有现有单测绿
8. Phase A 阶段 `PromptConstants` 常量仍被调用(迁移在 Phase C)

### 4.4 不做什么

- 不动 DiscussionEngine
- 不引入 ReactAgent / Toolkit / StateGraph
- **不迁移现有提示词到 Nacos(Phase C 再迁移)** -- Phase A 只建基础设施
- 不动领域事件
- 不动 ContextBuilder(Phase C/D 再改)

---

## 五、Phase B:群聊消息广播统一

### 5.1 目标

将 `chatPusher` 散点(20+ 处 WS 推送调用)统一为 `GroupBroadcastService` 端口。为 Phase C 的 StateGraph 做准备。

> **与原方案差异**:原方案用 AgentScope MsgHub,但 SAA 无 MsgHub。本方案用 `GroupBroadcastService` 端口统一 WS 推送,Phase C 的 StateGraph 通过 OverAllState 实现消息广播给 Agent。

##### MsgHub vs GroupBroadcastService + OverAllState 对比

**MsgHub 基本原理**(AgentScope):

AgentScope 的 MsgHub 是一个消息总线,Agent 之间通过"发布-订阅"模式通信。Agent A 发言时,MsgHub 自动将消息广播给所有参与者(Agent B/C/D),每个 Agent 的 `Memory` 中会追加其他 Agent 的发言。`MultiAgentFormatter` 负责将多 Agent 消息格式化为 `<history>` 标签结构,让 LLM 理解"谁说了什么"。

```
Agent A 发言 "我认为用 Redis"
    ↓ MsgHub.broadcast(msg)
    ├── Agent B.observe(msg)  -> B 的 Memory 追加 "A: 我认为用 Redis"
    ├── Agent C.observe(msg)  -> C 的 Memory 追加 "A: 我认为用 Redis"
    └── Agent D.observe(msg)  -> D 的 Memory 追加 "A: 我认为用 Redis"
下一次 Agent B.call() 时,其 Memory 中已有 A 的发言,LLM 自然看到上下文
```

**本方案基本原理**(GroupBroadcastService + OverAllState):

不用消息总线,而是用 StateGraph 的 `OverAllState` 作为共享状态。所有 Agent 的发言追加到 `OverAllState` 的 `messages` 字段(AppendStrategy)。每个 Agent 节点执行时从 state 读取完整消息历史,拼入 LLM 上下文。WS 推送由 `GroupBroadcastService` 统一处理,与 Agent 间消息共享解耦。

```
Agent A 节点执行:
    1. 从 OverAllState.messages 读取历史
    2. LLM 生成发言 "我认为用 Redis"
    3. 发言追加到 OverAllState.messages (AppendStrategy)
    4. GroupBroadcastService.broadcast() 推送 WS 给前端

Agent B 节点执行(下一轮):
    1. 从 OverAllState.messages 读取历史(已含 A 的发言)
    2. LLM 上下文自然包含 "A: 我认为用 Redis"
    3. 生成发言...
```

**优缺点对比**:

| 维度 | MsgHub(AgentScope) | GroupBroadcastService + OverAllState(本方案) |
|---|---|---|
| **消息共享机制** | 发布-订阅,Agent Memory 自动追加 | 共享 State,Agent 节点主动读取 |
| **广播实时性** | 实时(A 发言瞬间 B/C/D Memory 即更新) | 延迟一轮(B 下一轮执行时才读到 A 的发言) |
| **MultiAgentFormatter** | 内置,自动格式化多 Agent 消息(`<history>` 标签) | 需在节点内手动拼接(或 Phase D Hook 注入) |
| **动态参与者** | 支持(`hub.add(agent)` / `hub.delete(agent)`) | 需重启流程或动态修改节点(较复杂) |
| **Agent 间解耦** | 高(Agent 不感知彼此,只订阅 Hub) | 中(Agent 节点共享 State,有隐式耦合) |
| **WS 推送** | 不处理(纯 Agent 间通信) | `GroupBroadcastService` 统一处理(前后端一体) |
| **流程可视化** | 无(MsgHub 是运行时对象,不可视) | StateGraph 可序列化为 JSON,可可视化 |
| **持久化恢复** | 无(Memory 在内存) | OverAllState 可持久化(CheckpointSaver),重启可恢复 |
| **框架依赖** | 绑定 AgentScope | 仅依赖 SAA StateGraph(已在技术栈内) |
| **实现复杂度** | 低(框架内置,几行代码) | 中(需实现节点 + 状态管理 + 广播) |
| **调试可观测** | 低(消息在 Memory 中分散) | 高(State 集中,可快照查看完整上下文) |
| **适用场景** | Agent 数量动态变化、实时对话 | 固定流程、需要可视化/持久化的场景 |

**结论**:MsgHub 在"实时广播 + 动态参与者"上更优,但本方案的群聊流程是固定 DAG(意图分类 -> 讨论 -> 收束 -> 沉淀),参与者相对固定,且需要可视化/持久化,OverAllState 方案更契合。`MultiAgentFormatter` 的缺失在 Phase D 通过 Hook 补偿(群成员名单/消息历史格式化)。

### 5.2 改动范围

#### 5.2.1 domain 层(新增业务端口)

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/service/GroupBroadcastService.java`

```java
package com.dingring.domain.service;

/**
 * 群聊消息广播端口。
 * 统一所有 WS 推送逻辑,替代 chatPusher 散点。
 */
public interface GroupBroadcastService {

    /** 广播消息到群内所有客户端 */
    void broadcast(Long groupId, String type, Object data);

    /** 广播 Agent 发言(含流式标记处理) */
    void broadcastAgentMessage(Long groupId, Long agentId, String agentName,
                                String content, Long topicId, Long replyToMessageId);
}
```

#### 5.2.2 infrastructure 层(实现)

**新增文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/broadcast/GroupBroadcastServiceImpl.java`

```java
package com.dingring.infrastructure.broadcast;

import com.dingring.domain.service.GroupBroadcastService;
import org.springframework.stereotype.Service;

/**
 * GroupBroadcastService 实现。
 * 内部委托给 WsSessionManager(从 adapter 层移到 infrastructure 或通过端口注入)。
 */
@Service
public class GroupBroadcastServiceImpl implements GroupBroadcastService {
    // 注入 ChatPusher(现有接口)或直接用 WsSessionManager
    // broadcast() — 委托 pushToGroup
    // broadcastAgentMessage() — 封装 NEW_MESSAGE / MESSAGE_DELTA / MESSAGE_COMPLETE 推送逻辑
}
```

#### 5.2.3 app 层(改造散点推送)

**改造文件**:[DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java)

- `speakOnce` 中的 `chatPusher.pushToGroup(NEW_MESSAGE, ...)` / `MESSAGE_COMPLETE` / `MESSAGE_DELTA` / `AGENT_TYPING` / `ERROR` 等调用,统一改为 `groupBroadcastService.broadcast()`
- `StreamEmitter` 内部推送改为委托 `groupBroadcastService`

**改造文件**:[ChatOrchestrator.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/ChatOrchestrator.java)

- `onUserMessage` 中的 `chatPusher.pushToGroup(NEW_MESSAGE, dto)` 改为 `groupBroadcastService.broadcast()`
- `conclude` / `generateConclusion` 中的推送同理

**改造文件**:[ChatPusher.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/service/ChatPusher.java)

- 保留接口,但改为继承/委托 `GroupBroadcastService`
- 或直接用 `GroupBroadcastService` 替代,`ChatPusher` 标记 `@Deprecated`

### 5.3 验证步骤

1. 群聊全流程行为与 Phase A 一致(闲聊/讨论/收束/沉淀)
2. WS 推送行为不变(前端无感)
3. 所有 `chatPusher.pushToGroup` 调用被 `groupBroadcastService.broadcast` 替代
4. 单测:`GroupBroadcastServiceImpl` 广播正确

### 5.4 不做什么

- 不动三态循环逻辑
- 不引入 StateGraph
- 不引入 ReactAgent

---

## 六、Phase C:SAA StateGraph 重构三态循环为核心 Workflow

### 6.1 目标

用 SAA StateGraph 替代 `DiscussionEngine` 三态硬编码,实现"Workflow 骨架 + Agentic 节点"。**业务编排放 app 层,技术编排放 infra 层**。

### 6.2 架构设计

#### 6.2.1 Workflow 流程图

```
START
  ↓
[preprocess] — 校验+入库+广播(现有 onUserMessage 逻辑)
  ↓
[intent-classify] — LLM 意图分类(CHAT/DISCUSS/WORK)
  ↓ (条件边)
  ├── CHAT -> [chat-node] — 单 Agent 闲聊应答
  │              ↓
  │            (缓冲达阈值?) -> [profile-extract] — 画像提炼
  │              ↓
  │            END
  │
  ├── DISCUSS -> [ensure-topic] — 追溯建题
  │               ↓
  │           [discuss-node] - 多 Agent 讨论(收敛+发散模型)
  │               ↓ (条件边:discussMode)
  │               ├── WAIT              -> END(等用户回答,阻塞队列)
  │               ├── CONVERGE          -> [discuss-node](立即下一轮,无延迟)
  │               ├── DIVERGE           -> [discuss-node](轻量 pace 防刷屏,divergeRounds++)
  │               │                        ↓ (divergeRounds >= maxDivergeRounds)
  │               │                     [discuss-node](自动回拉收敛)
  │               ├── CONCLUDE_PROPOSED -> END(提议收束,WS 推送确认提示,等用户确认)
  │               │                        ├── 用户确认  -> [conclude-node]
  │               │                        ├── 用户拒绝  -> [discuss-node](CONVERGE,注入"用户认为还需讨论")
  │               │                        └── 超时 5 分钟 -> [conclude-node](兜底自动收束)
  │               └── CONCLUDE          -> [conclude-node](用户要求结束 / 熔断)
  │           [conclude-node] - STAR 总结
  │               ↓
  │           [sediment-node] — 知识卡片抽取
  │               ↓
  │             END
  │
  └── WORK -> [work-node] — 单 Agent ReAct + 通用工具集(Phase D 完善)
               ↓
             [sediment-node] — 工作产出沉淀
               ↓
             END
```

#### 6.2.2 OverAllState 关键字段

```java
// KeyStrategyFactory 定义
Map<String, KeyStrategy> strategies = new HashMap<>();
strategies.put("input", new ReplaceStrategy());          // 用户原始消息
strategies.put("groupId", new ReplaceStrategy());         // 群 ID
strategies.put("topicId", new ReplaceStrategy());         // 主题 ID
strategies.put("intent", new ReplaceStrategy());          // CHAT/DISCUSS/WORK
strategies.put("messages", new AppendStrategy(false));    // 消息历史(追加)
strategies.put("conclusion", new ReplaceStrategy());      // 结论文本
strategies.put("workResult", new ReplaceStrategy());      // 工作产出
strategies.put("cards", new AppendStrategy(false));       // 知识卡片列表
strategies.put("converged", new ReplaceStrategy());       // 是否收敛
strategies.put("nextSpeaker", new ReplaceStrategy());     // 下一个发言者
strategies.put("discussMode", new ReplaceStrategy());     // 讨论模式:CONVERGE/DIVERGE/WAIT/CONCLUDE_PROPOSED/CONCLUDE
strategies.put("divergeRounds", new ReplaceStrategy());   // 发散轮次计数(达 maxDivergeRounds 回拉)
```

### 6.3 改动范围

#### 6.3.1 domain 层(新增纯数据结构 + 业务端口)

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/workflow/DiscussionFlowDefinition.java`

```java
package com.dingring.domain.workflow;

import java.util.List;

/**
 * 群聊流程定义(纯数据结构,不依赖任何框架)。
 * app 层组装,infra 层翻译为 StateGraph。
 */
public record DiscussionFlowDefinition(
    String name,
    List<FlowNode> nodes,
    List<FlowEdge> edges,
    DiscussionRules rules
) {}
```

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/workflow/DiscussionRules.java`

```java
package com.dingring.domain.workflow;

/**
 * 群聊业务规则(强类型,替代 Map<String, Object>)。
 * pace 机制改为"收敛+发散"模型:收敛模式无延迟(用户在等),
 * 发散模式轻量防刷屏,发散达 maxDivergeRounds 自动回拉收敛。
 * 收束只由显式信号触发(CONCLUDE/用户要求/熔断),不再用 convergePassCount。
 */
public record DiscussionRules(
    long divergePaceMs,          // 发散模式发言间隔(轻量防刷屏,如 2000ms)
    int maxDivergeRounds,        // 发散最大轮次(自动回拉收敛,如 3)
    int maxRounds,               // 总熔断轮次(Agent 发言总数上限)
    int profileExtractThreshold, // 画像提炼触发阈值
    int backfillLimit,           // 建题回填消息上限
    int contextWindow,           // 上下文窗口
    long concludeConfirmTimeoutMs // 提议收束后等用户确认的超时(如 300000=5分钟,超时自动收束)
) {}
```

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/workflow/FlowNode.java`

```java
package com.dingring.domain.workflow;

import java.util.Map;

public record FlowNode(
    String name,
    NodeType type,                // FUNCTION / AGENT
    String handlerRef,            // 处理器引用(infra 层按名查找)
    String promptTemplateName,    // Nacos Prompt 模板名(可空)
    Map<String, Object> promptVars // 模板变量(可空)
) {
    public enum NodeType { FUNCTION, AGENT }
}
```

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/workflow/FlowEdge.java`

```java
package com.dingring.domain.workflow;

public record FlowEdge(
    String from,
    String to,
    String condition  // 条件表达式(可空=无条件边)
) {}
```

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/workflow/DiscussionFlowResult.java`

```java
package com.dingring.domain.workflow;

import java.util.Map;

public record DiscussionFlowResult(
    String finalNode,       // 最终执行的节点
    Map<String, Object> state // 最终状态
) {}
```

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/service/DiscussionFlowService.java`

```java
package com.dingring.domain.service;

import com.dingring.domain.workflow.DiscussionFlowDefinition;
import com.dingring.domain.workflow.DiscussionFlowResult;
import java.util.Map;

/**
 * 群聊流程服务端口。
 * app 层调用,infrastructure 层用 SAA StateGraph 实现。
 */
public interface DiscussionFlowService {

    /**
     * 推进群聊流程。
     * @param definition 流程定义(节点+边+规则)
     * @param inputs 输入状态(groupId/userMessage/mentionedAgentIds 等)
     * @return 流程执行结果
     */
    DiscussionFlowResult advance(DiscussionFlowDefinition definition, Map<String, Object> inputs);
}
```

#### 6.3.2 app 层(业务编排,不依赖 SAA)

**新增文件**:`dingRing-app/src/main/java/com/dingring/app/workflow/GroupChatFlowDefinition.java`

```java
package com.dingring.app.workflow;

import com.dingring.domain.workflow.*;
import org.springframework.stereotype.Component;

/**
 * 群聊流程定义组装器。
 * 集中定义节点、边、业务规则,看一个类就懂群聊流程。
 */
@Component
public class GroupChatFlowDefinition {

    private final Terminator terminator;
    // 注入其他业务配置...

    public DiscussionFlowDefinition build() {
        return new DiscussionFlowDefinition(
            "group-chat",
            List.of(
                new FlowNode("preprocess", FUNCTION, "preprocessHandler", null, null),
                new FlowNode("intent-classify", FUNCTION, "intentClassifyHandler",
                    "intent-classify", Map.of()),
                new FlowNode("chat", AGENT, "chatHandler", null, null),
                new FlowNode("profile-extract", AGENT, "profileExtractHandler",
                    "profile-extract", null),
                new FlowNode("ensure-topic", FUNCTION, "ensureTopicHandler", null, null),
                new FlowNode("discuss", AGENT, "discussHandler", null, null),
                new FlowNode("work", AGENT, "workHandler", null, null),
                new FlowNode("conclude", AGENT, "concludeHandler",
                    "conclude", Map.of()),
                new FlowNode("sediment", AGENT, "sedimentHandler",
                    "sediment", null)
            ),
            List.of(
                new FlowEdge("preprocess", "intent-classify", null),
                new FlowEdge("intent-classify", "chat", "intent == 'CHAT'"),
                new FlowEdge("intent-classify", "ensure-topic", "intent == 'DISCUSS'"),
                new FlowEdge("intent-classify", "work", "intent == 'WORK'"),
                new FlowEdge("chat", "profile-extract", "chatBuffer >= profileExtractThreshold"),
                new FlowEdge("chat", "END", "chatBuffer < profileExtractThreshold"),
                new FlowEdge("profile-extract", "END", null),
                new FlowEdge("ensure-topic", "discuss", null),
                new FlowEdge("discuss", "conclude", "discussMode == 'CONCLUDE'"),          // 用户要求结束 / 熔断
                new FlowEdge("discuss", "discuss", "discussMode == 'CONVERGE'"),           // 收敛:立即下一轮
                new FlowEdge("discuss", "discuss", "discussMode == 'DIVERGE'"),            // 发散:轻量 pace + divergeRounds++
                new FlowEdge("discuss", "END", "discussMode == 'WAIT'"),                   // 等用户回答
                new FlowEdge("discuss", "END", "discussMode == 'CONCLUDE_PROPOSED'"),      // 提议收束,等用户确认(runLoop 处理)
                new FlowEdge("work", "sediment", null),
                new FlowEdge("conclude", "sediment", null),
                new FlowEdge("sediment", "END", null)
            ),
            new DiscussionRules(
                2000,           // divergePaceMs(发散模式轻量防刷屏)
                3,              // maxDivergeRounds(发散 3 轮自动回拉)
                100,            // maxRounds(总熔断)
                15,             // profileExtractThreshold
                15,             // backfillLimit
                200,            // contextWindow
                300000          // concludeConfirmTimeoutMs(5分钟,超时自动收束)
            )
        );
    }
}
```

**改造文件**:[DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java)

大幅重构,从 ~900 行降至 ~200 行:
- **保留**:信号入队(`onUserSignal` / `BlockingQueue<UserSignal>`)、虚拟线程执行器(`executorOf`)、`wake` / `execute` 机制
- **保留**:追溯建题(`ensureTopic`)的业务逻辑(移到 `EnsureTopicHandler`)
- **移除**:信号折叠(`fold`) -- 消息已入库,意图分类和 Agent 发言都从 DB 拉完整上下文,折叠是多余的(且有信息丢失漏洞:只保留最后一条内容,中间消息丢失)。积压信号直接 `queue.clear()` 丢弃(已入库不丢数据)
- **替换**:`runLoop` / `handleSignal` / `advanceDiscussion` / `speakOnce` 改为调用 `discussionFlowService.advance(groupChatFlow, inputs)`
- **移除**:`StreamEmitter` / `StreamMarkerGuard`(移到 infra 层节点实现)
- **移除**:`chatWithRetry`(移到 infra 层)

```java
// DiscussionEngine 改造后核心逻辑
private void runLoop(Long groupId) {
    while (true) {
        var active = topicRepository.findActiveByGroupId(groupId);
        // 收敛模式:阻塞等待用户消息(无延迟)
        // 发散模式:轻量 pace 防刷屏(divergePaceMs)
        var head = active.isPresent() ? queue.poll(paceForMode(groupId), MILLISECONDS) : queue.poll();
        if (head != null) {
            queue.clear();  // 丢弃积压信号(已入库,意图分类从 DB 拉完整上下文)
            var inputs = Map.of(
                "groupId", groupId,
                "userMessage", head.content(),
                "mentionedAgentIds", head.mentionedAgentIds(),
                "repliedToAgentId", head.repliedToAgentId()
            );
            var result = discussionFlowService.advance(groupChatFlow, inputs);
            if (result.isConcluded()) return;

            String mode = result.state().get("discussMode");
            // WAIT 模式:Agent 追问用户,阻塞等下一条用户消息
            if ("WAIT".equals(mode)) {
                head = queue.take();  // 阻塞等待用户回答
                continue;
            }
            // CONCLUDE_PROPOSED 模式:Agent 提议收束,等用户确认(5 分钟超时兜底)
            if ("CONCLUDE_PROPOSED".equals(mode)) {
                groupBroadcastService.broadcast(groupId, "CONCLUDE_PROPOSED", Map.of(
                    "topicId", result.state().get("topicId"),
                    "topicTitle", result.state().get("topicTitle")
                ));
                var confirm = queue.poll(5, TimeUnit.MINUTES);  // 5 分钟超时
                if (confirm == null) {
                    // 超时:自动收束(兜底,防用户离开导致卡死)
                    discussionFlowService.advance(groupChatFlow, Map.of(
                        "groupId", groupId, "forceConclude", true));
                    return;
                }
                queue.clear();
                // 用户消息可能是确认("总结吧")或拒绝("还想继续讨论")
                var confirmResult = discussionFlowService.advance(groupChatFlow, Map.of(
                    "groupId", groupId, "userMessage", confirm.content(),
                    "concludeConfirmation", true));
                if (confirmResult.isConcluded()) return;
                // 用户拒绝:继续讨论(Agent 上下文已注入"用户认为还需讨论")
                continue;
            }
            continue;
        }
        if (active.isEmpty()) return;
        // 讨论态自主推进(仅 DIVERGE 模式;CONVERGE 模式无消息时退出等用户)
        var inputs = Map.of("groupId", groupId, "autoAdvance", true);
        discussionFlowService.advance(groupChatFlow, inputs);
    }
}

/** 根据当前讨论模式决定 pace:收敛=阻塞等待,发散=轻量防刷屏 */
private long paceForMode(Long groupId) {
    String mode = groupState.get(groupId).discussMode;
    if ("DIVERGE".equals(mode)) {
        return rules.divergePaceMs();  // 2000ms 轻量防刷屏
    }
    return Long.MAX_VALUE;  // 收敛模式:阻塞等待用户消息(不自主推进)
}
```

**保留文件**:
- [ChatOrchestrator.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/ChatOrchestrator.java) — 收束域逻辑不变,内部调用改为 DiscussionFlowService
- [SpeakerScheduler.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/SpeakerScheduler.java) — 保留,作为业务规则注入 DiscussHandler
- [ModeratorService.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/ModeratorService.java) - 保留,决策 prompt 迁移到 Nacos `moderator` 模板(Moderator 仍负责选人/收束判断,不再管 pace)
- [MessageRouter.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/MessageRouter.java) — 保留,作为 intent-classify handler 的业务实现,分类 prompt 迁移到 Nacos
- [Terminator.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/Terminator.java) — 保留,作为熔断业务规则
- [ContextBuilder.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/ContextBuilder.java) — 群聊拼接逻辑退役(Phase D Hook 替代),保留 RAG/画像注入部分

**改造文件**:[CardEventHandler.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/event/CardEventHandler.java) — 卡片抽取 prompt 迁移到 Nacos `sediment` 模板
**改造文件**:[ProfileEventHandler.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/event/ProfileEventHandler.java) — 画像提炼 prompt 迁移到 Nacos `profile-extract` 模板

#### 6.3.3 infrastructure 层(技术编排,依赖 SAA StateGraph)

**新增文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/SaaWorkflow.java`

```java
package com.dingring.infrastructure.workflow;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.*;
import com.dingring.domain.service.DiscussionFlowService;
import com.dingring.domain.workflow.*;
import org.springframework.stereotype.Service;

import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

/**
 * DiscussionFlowService 的 SAA StateGraph 实现。
 * 接收 app 层 DiscussionFlowDefinition(纯数据),翻译为 StateGraph 并执行。
 */
@Service
public class SaaWorkflow implements DiscussionFlowService {

    private final NodeHandlerRegistry nodeRegistry;

    @Override
    public DiscussionFlowResult advance(DiscussionFlowDefinition definition, Map<String, Object> inputs) {
        // 1. 构建 KeyStrategyFactory(从 definition 推导)
        KeyStrategyFactory keyStrategyFactory = buildKeyStrategyFactory();

        // 2. 构建 StateGraph
        StateGraph graph = new StateGraph(definition.name(), keyStrategyFactory);

        // 3. 添加节点
        for (FlowNode node : definition.nodes()) {
            NodeAction handler = nodeRegistry.getHandler(node.handlerRef());
            graph.addNode(node.name(), node_async(handler));
        }

        // 4. 添加边(区分条件边和直接边)
        for (FlowEdge edge : definition.edges()) {
            if (edge.condition() != null) {
                // 条件边:用 EdgeAction 解析条件
                graph.addConditionalEdges(edge.from(),
                    edge_async(buildConditionEvaluator(edge.condition())),
                    Map.of(edge.to(), edge.to(), "END", END));
            } else {
                graph.addEdge(edge.from(), edge.to());
            }
        }

        // 5. 编译并执行
        CompiledGraph compiled = graph.compile();
        Optional<OverAllState> result = compiled.invoke(inputs);

        // 6. 返回结果
        return new DiscussionFlowResult(
            result.map(s -> s.value("finalNode", "").orElse("")).orElse(""),
            result.map(OverAllState::data).orElse(Map.of())
        );
    }
}
```

**新增文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/NodeHandlerRegistry.java`

```java
package com.dingring.infrastructure.workflow;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * 节点处理器注册表。
 * 按 handlerRef 查找节点实现(Spring Bean 名)。
 */
@Component
public class NodeHandlerRegistry {

    private final Map<String, NodeAction> handlers;

    public NodeHandlerRegistry(Map<String, NodeAction> handlers) {
        this.handlers = handlers;
    }

    public NodeAction getHandler(String handlerRef) {
        return handlers.get(handlerRef);
    }
}
```

**新增节点实现**(均实现 `com.alibaba.cloud.ai.graph.action.NodeAction`):

| 文件 | handlerRef | 职责 | 从现有代码迁移 |
|---|---|---|---|
| `PreprocessNode.java` | preprocessHandler | 校验+入库+广播 | `ChatOrchestrator.onUserMessage` |
| `IntentClassifyNode.java` | intentClassifyHandler | LLM 意图分类 | `MessageRouter.route`,从 Nacos 加载 `intent-classify` prompt |
| `ChatNode.java` | chatHandler | 单 Agent 闲聊应答 | `DiscussionEngine.handleChat` + `speakOnce` |
| `EnsureTopicNode.java` | ensureTopicHandler | 追溯建题 | `DiscussionEngine.ensureTopic` + `backfillChatMessages` |
| `DiscussNode.java` | discussHandler | 多 Agent 讨论 | `DiscussionEngine.advanceDiscussion` + `SpeakerScheduler.rank` + `ModeratorService.decide` + `speakOnce` |
| `ConcludeNode.java` | concludeHandler | STAR 总结 | `ChatOrchestrator.generateConclusion`,从 Nacos 加载 `conclude` prompt |
| `SedimentNode.java` | sedimentHandler | 知识卡片抽取 | `CardEventHandler`,从 Nacos 加载 `sediment` prompt |
| `WorkNode.java` | workHandler | 工作任务(Phase D 完善) | 新增,Phase D 给 ReactAgent + 工具 |
| `ProfileExtractNode.java` | profileExtractHandler | 画像提炼 | `ProfileEventHandler`,从 Nacos 加载 `profile-extract` prompt |

每个 NodeAction 的 `apply(OverAllState state)` 方法:
1. 从 `state` 读取输入(groupId/userMessage/...)
2. 调用对应业务逻辑(MessageRouter/SpeakerScheduler/...)
3. 返回 `Map<String, Object>` 状态更新(intent/messages/conclusion/...)

**示例:IntentClassifyNode**

```java
package com.dingring.infrastructure.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component("intentClassifyHandler")
public class IntentClassifyNode implements NodeAction {

    private final MessageRouter messageRouter;  // app 层业务逻辑
    private final PromptTemplateLoader promptLoader;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String userMessage = state.value("input", "");
        String topicTitle = state.value("topicTitle", "");

        // 从 Nacos 加载意图分类 prompt(热更新)
        String prompt = promptLoader.render("intent-classify", Map.of(
            "userMessage", userMessage,
            "topicTitle", topicTitle
        ));

        // 调用现有 MessageRouter 业务逻辑
        var route = messageRouter.route(prompt, userMessage, topicTitle);

        return Map.of(
            "intent", route.intent().name(),
            "topicTitle", route.topicTitle()
        );
    }
}
```

**新增文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/AgentEventTranslator.java`

```java
package com.dingring.infrastructure.workflow;

import org.springframework.stereotype.Component;

/**
 * SAA Agent 事件 -> DingRingJ 领域事件翻译器。
 * Phase C 暂不使用(Phase D ReactAgent 引入后激活)。
 */
@Component
public class AgentEventTranslator {
    // Phase D 实现:ReactAgent 事件 -> AgentSelected/AgentFailed/MessageSent 等
}
```

#### 6.3.4 common 层

**改造文件**:[PromptConstants.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java)

- 保留标记符常量(`CONCLUDE_MARKER`, `PASS_MARKER` 等)
- 业务 prompt 常量标记 `@Deprecated`,迁移到 Nacos

#### 6.3.5 Nacos 配置

在 Nacos `prompt-config.json` 中添加 5 个流程级 prompt(已在 Phase A 创建,Phase C 正式迁移内容):
- `intent-classify` - 迁移自 `PromptConstants.INTENT_CLASSIFIER`
- `conclude` - 迁移自 `PromptConstants.CONCLUSION_STAR`
- `sediment` - 迁移自 `PromptConstants.KNOWLEDGE_EXTRACT`
- `moderator` - 迁移自 `PromptConstants.HOST_DECISION`
- `profile-extract` - 迁移自 `PromptConstants.USER_PROFILE_EXTRACT`
- `message-viewpoint` - **新增**:单条消息观点摘要(30字以内)
- `topic-profile` - **新增**:话题级用户表现分析(理解程度/薄弱点/亮点/建议)

#### 6.3.6 消息上下文优化:标签 + 选择性摘要

##### 问题:完整消息历史噪音过大

当前 `ContextBuilder` 用 `contextWindow=200` 截断消息全量塞入 LLM 上下文。50 轮讨论后 200 条消息可能 10 万+ token,远超模型窗口,且关键信息被"同意""+1"等短回复淹没。

##### 方案:消息级标签 + 长发言选择性摘要

每条消息入库时打标签(规则判定,零 LLM),非明显噪音且无协作标记的 Agent 发言标记为 VIEWPOINT,异步调 LLM 一次调用同时判断是否有观点 + 摘要。上下文构建时用"观点摘要列表 + 近期窗口"替代"全量原文窗口"。

**消息标签类型**(观点驱动,不按长度区分):

| 标签 | 判定条件 | 是否 LLM | 说明 |
|---|---|---|---|
| `KEY` | 用户消息 | 否 | 原文即观点,永远保留 |
| `NOISE` | 规则判定(纯标点/emoji/<5字) 或 LLM 判定无观点 | 规则否;LLM改标时是(一次调用) | 无观点,不进入上下文 |
| `MARKER_DIVERGE` | 含 `[[DIVERGE]]` 标记 | 否 | 标记即观点 |
| `MARKER_QUESTION` | 含 `[[ASK_USER]]` 标记 | 否 | 原文即问题 |
| `MARKER_CONCLUDE` | 含 `[[CONCLUDE]]` 标记 | 否 | 提议收束 |
| `VIEWPOINT` | 其他所有 Agent 发言 | **是**(异步,判断+摘要一次调用) | 有观点则摘要,无观点则改标 NOISE |

##### DB 变更

```sql
ALTER TABLE group_message ADD COLUMN tag VARCHAR(20) DEFAULT NULL COMMENT '消息标签: KEY/NOISE/MARKER_DIVERGE/MARKER_QUESTION/MARKER_CONCLUDE/VIEWPOINT';
ALTER TABLE group_message ADD COLUMN viewpoint TEXT DEFAULT NULL COMMENT '一句话观点摘要(仅 SUBSTANTIVE 有)';
```

##### domain 层(新增标签枚举 + MessageRepository 扩展)

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/group/MessageTag.java`

```java
package com.dingring.domain.group;

/**
 * 消息标签:观点驱动,不按长度区分。
 * 摘要与否取决于"是否表达观点",而非消息长度。
 */
public enum MessageTag {
    KEY,            // 用户消息,原文即观点,不摘要
    NOISE,          // 无观点(纯附和/标点/LLM判定无观点),不摘要
    MARKER_DIVERGE, // 含 [[DIVERGE]] 标记,标记即观点,不摘要
    MARKER_QUESTION,// 含 [[ASK_USER]] 标记,原文即问题,不摘要
    MARKER_CONCLUDE,// 含 [[CONCLUDE]] 标记,不摘要
    VIEWPOINT       // 其他 Agent 发言,由 LLM 判断是否有观点并摘要
}
```

**改造文件**:[GroupMessage.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-domain/src/main/java/com/dingring/domain/group/GroupMessage.java)

- 新增 `tag`(String)和 `viewpoint`(String)字段

**改造文件**:[MessageRepository.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-domain/src/main/java/com/dingring/domain/group/MessageRepository.java)

- 新增 `findViewpointsByTopicId(Long topicId)` -- 查询 Topic 内所有有 viewpoint 或 tag=KEY/MARKER_* 的消息(观点列表)
- 新增 `updateTagAndViewpoint(Long messageId, String tag, String viewpoint)` -- 更新标签和摘要

##### app 层(消息标签器 + 摘要处理器)

**新增文件**:`dingRing-app/src/main/java/com/dingring/app/orchestrator/MessageTagger.java`

```java
package com.dingring.app.orchestrator;

import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageTag;
import com.dingring.domain.group.SenderType;
import org.springframework.stereotype.Component;

/**
 * 消息标签器:入库时打标签,观点驱动而非长度驱动。
 * <p>设计要点(方案 6.3.6):
 * - 用户消息永远标记为 KEY,原文即观点,不摘要
 * - 明显无观点(纯标点/emoji/极短应答)标记为 NOISE,不摘要,零 LLM 调用
 * - 带协作标记的发言([[DIVERGE]]/[[ASK_USER]]/[[CONCLUDE]])标记为 MARKER_*,标记即观点,不摘要
 * - 其他所有 Agent 发言标记为 VIEWPOINT,由 MessageSummaryHandler 异步调 LLM 判断是否有观点并摘要
 * <p>与旧方案差异:不再用长度阈值(>100字=SUBSTANTIVE)判断是否摘要,
 * 因为50字的清晰观点也应该摘要,150字的废话不应该摘要。
 */
@Component
public class MessageTagger {

    public MessageTag tag(GroupMessage msg) {
        // 用户消息:永远 KEY
        if (msg.getSenderType() == SenderType.USER) {
            return MessageTag.KEY;
        }
        String content = msg.getContent();
        if (content == null || content.isBlank()) {
            return MessageTag.NOISE;
        }
        // 明显无观点:纯标点/emoji/极短应答(零 LLM 调用)
        if (isObviousNoise(content)) {
            return MessageTag.NOISE;
        }
        // 带协作标记的发言:标记即观点,不需摘要
        if (content.contains("[[DIVERGE]]")) {
            return MessageTag.MARKER_DIVERGE;
        }
        if (content.contains("[[ASK_USER]]")) {
            return MessageTag.MARKER_QUESTION;
        }
        if (content.contains("[[CONCLUDE]]")) {
            return MessageTag.MARKER_CONCLUDE;
        }
        // 其他所有发言:交给 LLM 判断是否有观点 + 摘要
        return MessageTag.VIEWPOINT;
    }

    /**
     * 规则判断明显噪音:去除标点/空格/emoji 后不足 5 字符。
     * 如"同意""+1""哈哈""对"等,无需 LLM 判断。
     */
    private boolean isObviousNoise(String content) {
        String stripped = content.replaceAll("[\\s\\p{Punct}]", "");
        return stripped.length() < 5;
    }
}
```

**新增文件**:`dingRing-app/src/main/java/com/dingring/app/event/MessageSummaryHandler.java`

```java
package com.dingring.app.event;

import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageTag;
import com.dingring.domain.service.LlmService;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * 消息摘要处理器:VIEWPOINT 标签的消息异步调 LLM,一次调用同时判断是否有观点 + 摘要。
 * <p>设计要点(方案 6.3.6):
 * - KEY/NOISE/MARKER_* 不需要 LLM,直接由 MessageTagger 规则判定
 * - VIEWPOINT 标签的消息走一次轻量 LLM:有观点则输出一句话摘要,无观点则输出 NO_VIEWPOINT 并改标 NOISE
 * - 失败不阻塞主流程(摘要为增强,非依赖)
 */
@Component
public class MessageSummaryHandler {

    private final LlmService llmService;
    private final MessageRepository messageRepository;
    private final PromptTemplateLoader promptLoader;
    private final MessageTagger messageTagger;

    /** LLM 判定无观点时的输出标记 */
    private static final String NO_VIEWPOINT = "NO_VIEWPOINT";

    /**
     * 消息入库后调用(同步打标签 + 异步判断摘要)。
     */
    public void onMessageSaved(GroupMessage msg) {
        // 1. 打标签(同步,零成本)
        MessageTag tag = messageTagger.tag(msg);
        messageRepository.updateTagAndViewpoint(msg.getId(), tag.name(), null);

        // 2. 仅 VIEWPOINT 触发异步 LLM(判断是否有观点 + 摘要,一次调用)
        if (tag != MessageTag.VIEWPOINT) return;

        Thread.ofVirtual().name("msg-summary-" + msg.getId()).start(() -> {
            try {
                String prompt = promptLoader.render("message-viewpoint",
                    Map.of("content", msg.getContent()));
                String raw = llmService.chat(summarizerAgent(), prompt,
                    List.of(), new LlmService.CallOptions(0.0, 60, null, true, false));

                if (NO_VIEWPOINT.equals(raw.trim())) {
                    // LLM 判定无观点:改标 NOISE,不摘要
                    messageRepository.updateTagAndViewpoint(msg.getId(),
                        MessageTag.NOISE.name(), null);
                } else {
                    // 有观点:保留 VIEWPOINT 标签,写入摘要
                    messageRepository.updateTagAndViewpoint(msg.getId(),
                        tag.name(), raw.trim());
                }
            } catch (Exception e) {
                // 摘要失败仅日志,不影响主流程
            }
        });
    }

    /** 摘要用的 Agent:复用群首个成员或专职轻量模型(低成本) */
    private Agent summarizerAgent() { ... }
}
```

##### infrastructure 层(DiscussNode 上下文构建改造)

**改造文件**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/node/DiscussNode.java`

DiscussNode 构建 LLM 上下文的逻辑改为"观点列表 + 近期窗口":

```java
@Component("discussHandler")
public class DiscussNode implements NodeAction {

    private final MessageRepository messageRepository;
    // ...

    @Override
    public Map<String, Object> apply(OverAllState state) {
        Long topicId = state.value("topicId", null);
        Long groupId = state.value("groupId", null);

        // 层1:观点摘要列表(有 viewpoint 或 tag=KEY/QUESTION/DIVERGE 的消息)
        List<GroupMessage> viewpoints = messageRepository.findViewpointsByTopicId(topicId);
        StringBuilder viewpointCtx = new StringBuilder();
        if (!viewpoints.isEmpty()) {
            viewpointCtx.append("讨论观点:\n");
            for (GroupMessage m : viewpoints) {
                String text = m.getViewpoint() != null ? m.getViewpoint() : m.getContent();
                viewpointCtx.append(senderNameOf(m)).append(": ").append(text).append("\n");
            }
            viewpointCtx.append("\n");
        }

        // 层2:近期窗口(最近 8 条原文,含 NOISE/NORMAL 用于语气衔接)
        List<GroupMessage> recent = messageRepository.findRecentByTopicId(topicId, 8);
        StringBuilder recentCtx = new StringBuilder("最近对话:\n");
        for (GroupMessage m : recent) {
            recentCtx.append(senderNameOf(m)).append(": ")
                .append(ContextBuilder.stripMarkers(m.getContent())).append("\n");
        }

        // 拼入 system prompt
        String systemPrompt = agent.getSystemPrompt() + "\n\n" + viewpointCtx + recentCtx;

        // 调用 LLM(复用 SpeakerScheduler 选人 + speakOnce 逻辑)
        // ...

        // 发言入库后触发打标签 + 摘要
        GroupMessage savedMsg = saveAgentMessage(...);
        messageSummaryHandler.onMessageSaved(savedMsg);

        return Map.of("messages", ..., "discussMode", ...);
    }
}
```

##### 上下文构建效果对比

50 轮讨论的消息分布(经验估计):

| 类型 | 数量 | 进入上下文? | token 贡献 |
|---|---|---|---|
| 用户消息(KEY) | ~10 | 是(原文) | ~1000 |
| 明显噪音(NOISE,规则判定) | ~10 | 否 | 0 |
| LLM判定无观点(NOISE,改标) | ~5 | 否 | 0 |
| 有观点(VIEWPOINT,LLM摘要) | ~15 | 是(viewpoint 摘要) | ~450 |
| 协作标记(MARKER_*) | ~5 | 是(原文) | ~500 |
| 其他(近期窗口原文) | ~5 | 仅近期8条内 | ~500 |
| **合计** | ~50 | | **~2450 token** |

对比现状 200 条全量原文(~10 万 token),信息密度提升 40 倍。

##### Nacos `message-viewpoint` prompt

```json
{
  "name": "message-viewpoint",
  "template": "判断以下发言是否表达了明确观点。\n\n如果有观点,用一句话(30字以内)概括核心观点,只输出观点,不要前缀和引号。\n如果没有明确观点(如纯疑问、附和、过渡语),只输出 NO_VIEWPOINT。\n\n发言:{content}"
}
```

##### 成本分析

| 维度 | 现状(全量窗口) | 本方案(观点驱动标签+判断摘要) |
|---|---|---|
| LLM 摘要调用 | 0 | ~25次/50轮(所有非NOISE非MARKER的Agent发言) |
| 每次输入 | 无 | 1 条消息(~500 token) |
| 每次输出 | 无 | 30字摘要或 NO_VIEWPOINT(~50 token) |
| 上下文 token | ~10 万 | ~2600 |
| 噪音过滤 | 无 | 规则+LLM 双重过滤(规则过滤明显噪音,LLM过滤隐式噪音) |
| 漏摘风险 | - | 低(所有发言都判断,不依赖长度) |
| 误摘风险 | - | 低(LLM判断无观点则不摘要) |
| 可检索 | 否 | 按 viewpoint/tag 查询 |

#### 6.3.7 话题级用户表现画像

##### 问题:全局画像无法追踪用户能力成长

现状 `SimpleProfileService` 维护的是跨群全局特征("表达习惯""情绪基调"),无法回答"用户在缓存选型方面上次表现如何?薄弱在哪?":话题重启时 Agent 无法针对性引导用户提升。

##### 方案:话题级用户表现画像 + 历史回溯

每次 `TopicClosed` 时,LLM 分析用户在该话题下的表现(理解程度/薄弱点/亮点/建议提升方向),存入 `user_topic_profile` 表。话题重启时回溯上次表现,注入 Agent 上下文,让 Agent 针对性引导。

**与全局画像的关系**(互补,不替代):

| 维度 | 全局画像(现状,保留) | 话题级表现(新增) |
|---|---|---|
| 描述什么 | 用户跨话题的长期特征 | 用户在特定话题下的能力 |
| 粒度 | 粗(表达习惯/情绪基调) | 细(缓存选型/并发编程) |
| 更新时机 | 闲聊累积 + TopicClosed | 仅 TopicClosed |
| 用途 | Agent 理解用户风格 | Agent 针对性引导成长 |
| 可回溯 | 否 | 是(每次讨论一条记录) |

##### DB 变更

```sql
CREATE TABLE user_topic_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    topic_title VARCHAR(200),
    understanding_level VARCHAR(20),   -- BEGINNER/INTERMEDIATE/ADVANCED
    weak_points TEXT,                  -- 薄弱点(具体到行为,非笼统评价)
    strong_points TEXT,                -- 亮点
    suggested_focus TEXT,              -- 建议提升方向(可操作的建议)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    -- 不加唯一约束:同一话题标题允许多条记录(不同 topic_id),记录用户进步轨迹
    INDEX idx_user_title_time (user_id, topic_title, created_at DESC)
);
```

##### domain 层(新增实体 + Repository)

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/user/UserTopicProfile.java`

```java
package com.dingring.domain.user;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class UserTopicProfile {
    private Long id;
    private Long userId;
    private Long topicId;
    private Long groupId;
    private String topicTitle;
    private String understandingLevel;  // BEGINNER/INTERMEDIATE/ADVANCED
    private String weakPoints;
    private String strongPoints;
    private String suggestedFocus;
    private LocalDateTime createdAt;
}
```

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/user/UserTopicProfileRepository.java`

```java
package com.dingring.domain.user;

import java.util.List;
import java.util.Optional;

public interface UserTopicProfileRepository {
    void save(UserTopicProfile profile);
    Optional<UserTopicProfile> findByUserIdAndTopicId(Long userId, Long topicId);
    /** 按话题标题查历史(话题重启时回溯) */
    List<UserTopicProfile> findByUserIdAndTopicTitleOrderByCreatedAtDesc(Long userId, String topicTitle);
}
```

##### app 层(话题表现分析 + 历史回溯)

**新增文件**:`dingRing-app/src/main/java/com/dingring/app/event/TopicProfileEventHandler.java`

```java
package com.dingring.app.event;

import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.user.UserTopicProfile;
import com.dingring.domain.user.UserTopicProfileRepository;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 话题级用户表现画像:监听 TopicClosed,分析用户在该话题下的表现。
 * 独立虚拟线程,失败不阻塞主流程。
 */
@Component
public class TopicProfileEventHandler {

    private final MessageRepository messageRepository;
    private final UserTopicProfileRepository profileRepository;
    private final LlmService llmService;
    private final PromptTemplateLoader promptLoader;

    @EventListener
    public void onTopicClosed(TopicClosed event) {
        Thread.ofVirtual().name("topic-profile-" + event.getTopicId())
            .start(() -> analyze(event));
    }

    private void analyze(TopicClosed event) {
        try {
            // 1. 取 Topic 对话(过滤 NOISE,复用消息标签)
            List<GroupMessage> messages = messageRepository.findRecentByTopicId(event.getTopicId(), 30);
            boolean hasUserMessage = messages.stream()
                .anyMatch(m -> m.getSenderType() == SenderType.USER);
            if (!hasUserMessage) return;

            String dialogue = messages.stream()
                .filter(m -> m.getTag() == null || !m.getTag().equals("NOISE"))
                .map(m -> senderNameOf(m) + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

            // 2. LLM 分析用户表现
            String prompt = promptLoader.render("topic-profile", Map.of(
                "topicTitle", event.getTitle(),
                "dialogue", dialogue
            ));
            String raw = llmService.chat(extractorAgent(), prompt,
                List.of(), new LlmService.CallOptions(0.0, 500, null, true, false));

            // 3. 解析 JSON -> UserTopicProfile
            UserTopicProfile profile = parse(raw, event);
            profileRepository.save(profile);
        } catch (Exception e) {
            // 失败仅日志,不影响主流程
        }
    }
}
```

**改造文件**:`dingRing-app/.../workflow/node/EnsureTopicNode.java`(Phase C 新建的节点)

话题建题时增加历史回溯:

```java
@Component("ensureTopicHandler")
public class EnsureTopicNode implements NodeAction {

    private final UserTopicProfileRepository topicProfileRepository;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        // ... 现有建题逻辑 ...

        // 新增:回溯该话题的用户历史表现(多条记录 = 进步轨迹)
        String topicTitle = state.value("topicTitle", "");
        List<UserTopicProfile> history = topicProfileRepository
            .findByUserIdAndTopicTitleOrderByCreatedAtDesc(DEFAULT_USER_ID, topicTitle);

        if (!history.isEmpty()) {
            String hint = formatHistoryHint(history);
            // 注入到 state,后续 DiscussNode 读取并拼入 Agent system prompt
            return Map.of("topicId", topic.getId(), "userHistoryHint", hint);
        }
        return Map.of("topicId", topic.getId());
    }

    /**
     * 格式化历史表现提示,展示进步轨迹。
     * history 按 created_at DESC 排序,history.get(0) = 最近一次。
     */
    private String formatHistoryHint(List<UserTopicProfile> history) {
        UserTopicProfile latest = history.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("用户上次讨论「").append(latest.getTopicTitle()).append("」时:\n");
        sb.append("- 理解程度: ").append(latest.getUnderstandingLevel()).append("\n");
        sb.append("- 薄弱点: ").append(latest.getWeakPoints()).append("\n");
        sb.append("- 建议提升: ").append(latest.getSuggestedFocus());

        // 2 条以上展示进步轨迹,让 Agent 感知用户是否在进步
        if (history.size() >= 2) {
            sb.append("\n\n进步轨迹:");
            for (int i = history.size() - 1; i >= 0; i--) {
                UserTopicProfile p = history.get(i);
                sb.append("\n- 第").append(history.size() - i).append("次: ")
                    .append(p.getUnderstandingLevel())
                    .append("(薄弱: ").append(p.getWeakPoints()).append(")");
            }
        }

        sb.append("\n请在本次讨论中针对性地引导用户提升薄弱点。");
        return sb.toString();
    }
}
```

**改造文件**:`dingRing-infrastructure/.../workflow/node/DiscussNode.java`

DiscussNode 构建上下文时,如果 state 中有 `userHistoryHint`,追加到 system prompt:

```java
@Override
public Map<String, Object> apply(OverAllState state) {
    // ... 观点列表 + 近期窗口 ...

    // 新增:用户历史表现提示(话题重启时)
    String historyHint = state.value("userHistoryHint", "");
    if (!historyHint.isBlank()) {
        systemPrompt += "\n\n" + historyHint;
    }

    // ... 调用 LLM ...
}
```

##### Nacos `topic-profile` prompt

```json
{
  "name": "topic-profile",
  "template": "你是用户学习表现分析师。请基于以下讨论,分析用户在话题「{topicTitle}」下的表现。\n\n讨论记录:\n{dialogue}\n\n严格输出 JSON,不要输出任何其他内容:\n{\"understanding_level\": \"BEGINNER|INTERMEDIATE|ADVANCED\", \"weak_points\": \"用户在此话题下的薄弱点(具体到行为,非笼统评价)\", \"strong_points\": \"用户的亮点\", \"suggested_focus\": \"建议下次提升的方向(可操作的建议)\"}"
}
```

##### 完整链路

```
话题A 第一次讨论
  ↓ TopicClosed
  ↓ TopicProfileEventHandler 分析用户表现
  ↓ user_topic_profile 记录1(理解:BEGINNER,薄弱:依赖直觉选型)
  ↓
=== N天后 ===
  ↓
用户再次提类似话题 -> EnsureTopicNode 建题
  ↓ 回溯 user_topic_profile(按 topicTitle 查历史,取全部记录)
  ↓ 注入 userHistoryHint(最近表现 + 进步轨迹)到 state
  ↓
DiscussNode: Agent system prompt 追加历史表现
  ↓ Agent 针对性引导:"上次你倾向于凭经验,这次用 QPS 数据对比?"
  ↓ 用户有进步
  ↓ TopicClosed
  ↓ TopicProfileEventHandler 分析 -> 记录2(理解:INTERMEDIATE,薄弱:未考虑扩展性)
  ↓
=== 又N天后 ===
  ↓ 话题A 再次重启
  ↓ 回溯历史(2条记录,进步轨迹:BEGINNER -> INTERMEDIATE)
  ↓ Agent 看到进步,调整引导力度:"你已经会用数据对比了,这次考虑下扩展性?"
```

##### 话题重启的前端展示

话题建题时(`TopicCreated` 事件 -> WS `TOPIC_CREATED` 推送),如果检测到同标题历史,后端组装好提示文案,前端直接展示:

- **首次讨论**:`restartHint` 为空,前端无提示
- **话题重启**:`restartHint` 后端组装完整文案,前端展示顶部提示条

```json
{
  "type": "TOPIC_CREATED",
  "data": {
    "topicId": 103,
    "title": "缓存方案选型",
    "restartHint": "这是第3次讨论「缓存方案选型」，上次你在「未考虑扩展性」方面还需提升"
  }
}
```

**EnsureTopicNode 组装 `restartHint`**:

```java
if (!history.isEmpty()) {
    UserTopicProfile latest = history.get(0);
    String restartHint = String.format(
        "这是第%d次讨论「%s」，上次你在「%s」方面还需提升",
        history.size() + 1, topicTitle, latest.getWeakPoints()
    );
    return Map.of(
        "topicId", topic.getId(),
        "userHistoryHint", formatHistoryHint(history),  // 给 Agent 看(含进步轨迹)
        "restartHint", restartHint                        // 给前端看(一句话提示)
    );
}
return Map.of("topicId", topic.getId(), "restartHint", "");
```

**前端交互**:收到 `restartHint` 非空时,展示顶部提示条 + 关闭按钮;空则无提示。前端零拼接逻辑。

##### 讨论状态实时推送(TOPIC_STATUS)

每次 `advance()` 返回后,`DiscussionEngine.runLoop` 把 OverAllState 的关键字段推给前端,前端展示讨论动态:

**推送时机**:`runLoop` 中每次 `discussionFlowService.advance()` 返回后

**改造文件**:[DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java) -- runLoop 中 advance 返回后推送:

```java
var result = discussionFlowService.advance(groupChatFlow, inputs);
if (result.isConcluded()) return;

// 推送讨论状态快照(过滤敏感字段,只推前端需要的)
groupBroadcastService.broadcast(groupId, "TOPIC_STATUS", Map.of(
    "discussMode", result.state().getOrDefault("discussMode", ""),
    "topicTitle", result.state().getOrDefault("topicTitle", ""),
    "divergeRounds", result.state().getOrDefault("divergeRounds", 0),
    "maxDivergeRounds", discussionRules.maxDivergeRounds(),
    "restartHint", result.state().getOrDefault("restartHint", "")
));
```

**WS 推送格式**:

```json
{
  "type": "TOPIC_STATUS",
  "data": {
    "discussMode": "CONVERGE",
    "topicTitle": "缓存方案选型",
    "divergeRounds": 0,
    "maxDivergeRounds": 3,
    "restartHint": ""
  }
}
```

**discussMode 到前端展示的映射**:

| discussMode | 前端状态标签 | 颜色 | 说明 |
|---|---|---|---|
| `CONVERGE` | 讨论中 | 绿色(brand) | Agent 正在回答用户问题 |
| `DIVERGE` | 发散探索 · 第 N/M 轮 | 蓝色(info) | Agent 提出新视角,有进度条 |
| `WAIT` | 等待你回答 | 琥珀色(alert) | Agent 在追问,需要用户输入 |
| `CONCLUDE_PROPOSED` | 提议收束 | 紫色(accent-violet) | Agent 认为可以收尾,展示确认/拒绝按钮 |
| (无 activeTopic) | 闲聊 | 灰色(tertiary) | 无话题进行中 |

**前端组件**:`DiscussionStatus` -- 讨论状态横幅,位于 chat-header 和 chat-body 之间,根据 `TOPIC_STATUS` WS 事件实时更新:

- 状态标签(discussMode 映射)
- 发散进度条(divergeRounds / maxDivergeRounds)
- 话题重启提示(restartHint 非空时展示)
- 收束确认按钮(CONCLUDE_PROPOSED 时展示"确认结束"/"继续讨论")

**WS 推送链路改造**(`restartHint` 从 state 到前端):

1. `EnsureTopicNode`(infra 层)产出 `restartHint` 到 OverAllState(如上代码)
2. `SaaWorkflow.advance()` 执行完成后,从 `DiscussionFlowResult.state()` 取出 `restartHint`
3. `DiscussionEngine.runLoop` 收到 result 后,在推送 `TOPIC_CREATED` 时携带 `restartHint`

**改造文件**:[DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java) -- runLoop 中 EnsureTopic 执行后推送 TOPIC_CREATED 的逻辑:

```java
// runLoop 中,advance 返回后检查是否新建了 Topic
var result = discussionFlowService.advance(groupChatFlow, inputs);
if (result.isConcluded()) return;

// 如果 EnsureTopicNode 新建了 Topic,result 中会有 topicId 和 restartHint
String newTopicId = result.state().get("topicId");
String restartHint = result.state().getOrDefault("restartHint", "");
if (newTopicId != null && isnewTopic(newTopicId)) {
    groupBroadcastService.broadcast(groupId, "TOPIC_CREATED", Map.of(
        "topicId", newTopicId,
        "title", result.state().get("topicTitle"),
        "restartHint", restartHint
    ));
}
```

**改造文件**:[WsConstants.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-common/src/main/java/com/dingring/common/constant/WsConstants.java) -- 确认 `TOPIC_CREATED` 常量已有(现状已有,无需新增)

### 6.4 关键设计决策

1. **三态循环语义保留**:闲聊/讨论/收束映射为 StateGraph 条件分支,非简单线性
2. **收敛+发散模型替代固定 pace**:讨论态不再用 5-15s 随机延迟假装思考,改为:
   - **收敛模式(CONVERGE)**:Agent 直接回答用户问题,无延迟,用户在等
   - **发散模式(DIVERGE)**:Agent 提出关联新视角,轻量 pace(2s)防刷屏,最多 3 轮自动回拉
   - **等待模式(WAIT)**:Agent 追问用户,阻塞等用户回答(不自主推进)
   - 模式判断:Phase C 用文本标记(`[[DIVERGE]]`/`[[ASK_USER]]`),Phase D 迁移为 ReactAgent 工具调用
3. **用户驱动为主**:收敛模式下无用户消息时阻塞等待(`queue.take()`),而非自主推进;仅发散模式自主推进
4. **PASS 不触发收束**:去掉 `convergePassCount` 机制。Agent PASS 只影响选人(本轮不再选该 Agent),不触发收束。全员 PASS 时转为 `discussMode=WAIT`(回到等用户),而非收束。避免误场景:Agent 暂时没想法 ≠ 讨论结束,应等用户补充而非强行收束
5. **Agent 提议收束 + 用户确认**:Agent 输出 `[[CONCLUDE]]` 不直接收束,而是转为 `discussMode=CONCLUDE_PROPOSED`,WS 推送确认提示,由用户最终决定:
   - **用户确认** -> 正式收束(conclude)
   - **用户拒绝** -> 回到 `discussMode=CONVERGE` 继续讨论,Agent 上下文注入"用户认为还需讨论"
   - **超时 5 分钟** -> 自动收束(兜底,防用户离开导致卡死)
   - **用户主动要求结束**(MessageRouter 分类为 CONCLUDE)-> 直接收束(不需要再确认)
   - **熔断**(`maxRounds` 达上限)-> 直接收束(不需要再确认)
   - 价值:用户拥有收束决定权,画像数据质量更高(用户确认=有价值的讨论)
6. **追溯建题、回填**等业务逻辑保留,封装在对应 NodeAction 内;**信号折叠(`fold`)移除**(消息已入库,从 DB 拉完整上下文,折叠多余且有信息丢失漏洞)
7. **领域事件保留**:每个节点完成后发 `MessageSent`/`TopicCreated`/`TopicClosed` 等事件
8. **流式推送**:SAA 的 `compiled.stream()` 返回 `Flux<NodeOutput>`,在 infra 层转换为 WS 推送
9. **条件边评估器**:`buildConditionEvaluator(condition)` 解析简单条件表达式(如 `discussMode == 'CONVERGE'`)
10. **协作标记扩展**:`COLLABORATION_PROTOCOL` 新增 `[[DIVERGE]]`/`[[ASK_USER]]` 标记,`[[CONCLUDE]]` 语义改为"提议收束"(用户确认后才正式收束),`StreamMarkerGuard` 扩展过滤;Phase D 所有标记符迁移为 ReactAgent 工具调用
11. **消息上下文优化(标签+选择性摘要)**:用"观点摘要列表 + 近期窗口"替代"全量 200 条原文窗口":
   - 每条消息入库时打标签(规则判定零 LLM),VIEWPOINT 标签异步调 LLM 一次调用判断是否有观点+摘要
   - NOISE(规则+LLM双重判定无观点)不进上下文,信息密度提升 40 倍(10 万 token -> ~2500 token)
   - 观点可检索(`findViewpointsByTopicId`),支持按标签过滤
12. **话题级用户表现画像**:每次 TopicClosed 分析用户在该话题下的表现(薄弱点/亮点/建议),话题重启时回溯历史注入 Agent 上下文,让 Agent 针对性引导用户提升;与全局画像互补(全局=用户风格,话题级=用户能力成长轨迹)

### 6.5 验证步骤

1. `GroupChatFlowDefinition` 单测:验证节点和边定义正确
2. `SaaWorkflow` 集成测试:验证 StateGraph 编译执行
3. 群聊全流程行为:闲聊/讨论/收束/沉淀
4. **收敛+发散模型验证**:
   - 用户提问后 Agent 立即响应(无 5-15s 延迟)
   - Agent 输出 `[[DIVERGE]]` 标记后,轻量 pace(2s)自主推进
   - 发散达 3 轮自动回拉收敛模式
   - Agent 输出 `[[ASK_USER]]` 标记后,阻塞等待用户回答
5. **PASS 不触发收束验证**:
   - Agent 输出 `[[PASS]]` 后,该 Agent 本轮不再被选,但不触发收束
   - 全员 PASS 后转为 `discussMode=WAIT`(阻塞等用户),而非收束
6. **Agent 提议收束 + 用户确认验证**:
   - Agent 输出 `[[CONCLUDE]]` 后,`discussMode=CONCLUDE_PROPOSED`,WS 推送 `CONCLUDE_PROPOSED` 事件
   - 不直接收束,前端展示确认提示
   - 用户确认 -> 正式收束(Topic CLOSED)
   - 用户拒绝 -> 回到 `discussMode=CONVERGE` 继续讨论
   - 5 分钟无回应 -> 自动收束(兜底)
   - 用户主动要求结束 / `maxRounds` 熔断 -> 直接收束(不需确认)
7. DiscussionEngine 从 ~900 行降至 ~200 行
8. 流程级 prompt 从 Nacos 加载,热更新生效
9. 追溯建题、回填等业务逻辑正常;信号折叠已移除,用户连发消息从 DB 拉完整上下文
10. WS 推送行为不变(前端无感)
11. `StreamMarkerGuard` 正确过滤 `[[DIVERGE]]` / `[[ASK_USER]]` 新标记
12. **消息上下文优化验证**:
    - VIEWPOINT 标签的消息异步调 LLM,有观点则生成 viewpoint,无观点则改标 NOISE
    - 明显噪音(纯标点/<5字)规则判定为 NOISE,LLM 判定无观点也改标 NOISE,不进入观点列表
    - DiscussNode 上下文包含"观点摘要列表 + 近期 8 条原文",不含 NOISE 消息
    - 50 轮讨论后上下文 token < 5000(对比现状 ~10 万)
12. **话题级用户表现画像验证**:
    - TopicClosed 后 `user_topic_profile` 有记录(理解程度/薄弱点/亮点/建议)
    - 话题重启时 EnsureTopicNode 回溯历史,`userHistoryHint` 注入 state
    - DiscussNode 的 system prompt 包含"用户上次薄弱点"
    - Agent 发言体现针对性引导(非泛泛而谈)
13. **讨论状态实时推送验证**:
    - `advance()` 返回后推送 `TOPIC_STATUS` WS 事件,包含 discussMode/topicTitle/divergeRounds/restartHint
    - 前端 DiscussionStatus 横幅实时更新状态标签(CONVERGE/DIVERGE/WAIT/CONCLUDE_PROPOSED)
    - DIVERGE 模式展示发散进度条(N/M 轮)
    - CONCLUDE_PROPOSED 展示"确认结束"/"继续讨论"按钮,点击后发送消息触发后端处理
    - restartHint 非空时展示话题重启提示条

### 6.6 不做什么

- 不引入 ReactAgent(Phase D)
- 不引入 Toolkit(Phase D)
- 不引入 RAG(Phase E)
- WorkNode 为占位实现(Phase D 完善)

### 6.7 回退方案

`DiscussionFlowService` 可降级为旧 `DiscussionEngine` 实现(保留旧代码,用 `@ConditionalOnProperty` 切换)。

---

## 七、Phase D:Agent 加 Toolkit + ReAct 能力

### 7.1 目标

用 SAA `ReactAgent` 替代纯 LLM 调用,Agent 拥有工具调用 + Hook 干预能力。

### 7.2 依赖引入

**文件**:[dingRing-infrastructure/pom.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/pom.xml)

```xml
<!-- SAA Agent Framework(ReactAgent + Hook + Interceptor) -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-agent-framework</artifactId>
</dependency>
<!-- SAA AgentScope 集成(可选,如需 AgentScope 特有功能) -->
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-agentscope</artifactId>
</dependency>
```

### 7.3 改动范围

#### 7.3.1 domain 层(新增业务端口)

**新增文件**:`dingRing-domain/src/main/java/com/dingring/domain/service/AgentSpeakerService.java`

```java
package com.dingring.domain.service;

import com.dingring.domain.agent.Agent;
import java.util.List;

/**
 * Agent 发言服务端口。
 * infrastructure 层用 SAA ReactAgent 实现。
 */
public interface AgentSpeakerService {

    /**
     * Agent 发言(带工具调用能力)。
     * @param agent 领域 Agent 实体(LLM 配置)
     * @param systemPrompt 系统提示词
     * @param messages 对话历史
     * @param tools 工具集名称(按场景注入)
     * @return Agent 发言结果
     */
    AgentResult call(Agent agent, String systemPrompt, List<LlmService.ChatTurn> messages, List<String> tools);

    record AgentResult(String content, boolean hasToolCalls, List<String> toolCallSummary) {}
}
```

#### 7.3.2 infrastructure 层(Agentic 能力)

**目录结构**:`dingRing-infrastructure/src/main/java/com/dingring/infrastructure/agent/`

```
agent/
├── runtime/
│   ├── SaaReactAgentFactory.java      # 按 Agent 配置构建 SAA ReactAgent
│   └── AgentSpeakerServiceImpl.java   # 实现 AgentSpeakerService
├── tool/
│   ├── KnowledgeSearchTool.java       # @Tool 知识检索(Phase E RagService)
│   ├── UserProfileQueryTool.java      # @Tool 用户画像查询
│   └── TopicHistoryTool.java          # @Tool 历史主题查询
├── hook/
│   ├── MemoryInjectionHook.java       # ModelHook,注入群记忆
│   ├── ProfileInjectionHook.java      # ModelHook,注入用户画像
│   ├── StreamMarkerHook.java          # ModelHook,过滤 PASS/CONCLUDE 标记
│   └── ProfanityFilterHook.java       # ToolInterceptor,敏感词拦截
└── prompt/
    └── AgentPromptLoader.java          # 能力级 prompt 加载器(从 Nacos 加载 Tool description / Hook prompt)
```

**新增文件**:`agent/runtime/SaaReactAgentFactory.java`

```java
package com.dingring.infrastructure.agent.runtime;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.dingring.domain.agent.Agent;
import org.springframework.stereotype.Component;

/**
 * 按 Agent 领域实体配置构建 SAA ReactAgent。
 * maxIters 按场景:讨论=3 / 工作=15。
 */
@Component
public class SaaReactAgentFactory {

    private final SaaModelFactory modelFactory;
    private final ToolRegistry toolRegistry;

    /**
     * 构建讨论场景 Agent(maxIters=3,轻量工具)
     */
    public ReactAgent buildDiscussAgent(Agent domainAgent, String systemPrompt) {
        return ReactAgent.builder()
            .name(domainAgent.getName())
            .description(domainAgent.getDescription())
            .model(modelFactory.buildChatModel(domainAgent, null))
            .systemPrompt(systemPrompt)
            .maxIters(3)  // 讨论场景不深陷工具循环
            // .hooks(memoryInjectionHook, profileInjectionHook, streamMarkerHook)
            .build();
    }

    /**
     * 构建工作场景 Agent(maxIters=15,通用工具集)
     */
    public ReactAgent buildWorkAgent(Agent domainAgent, String systemPrompt) {
        return ReactAgent.builder()
            .name(domainAgent.getName())
            .model(modelFactory.buildChatModel(domainAgent, null))
            .systemPrompt(systemPrompt)
            .maxIters(15)
            // .tools(readFileTool, writeFileTool, searchCodeTool, ...)
            // .hooks(profanityFilterHook)
            .build();
    }
}
```

> **注意**:SAA `ReactAgent.Builder` 的 `maxIters` 需要确认 API。SAA ReactAgent 内部将 ReAct 循环编译为 StateGraph(`agent_model` -> `agent_tool` 条件边),`maxIters` 对应循环次数限制。可能通过 `CompileConfig` 或 Hook(`ModelCallLimitHook`)实现。实现时需查阅 SAA 源码确认。

**新增文件**:`agent/runtime/AgentSpeakerServiceImpl.java`

```java
package com.dingring.infrastructure.agent.runtime;

import com.dingring.domain.service.AgentSpeakerService;
import com.dingring.domain.service.LlmService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class AgentSpeakerServiceImpl implements AgentSpeakerService {

    private final SaaReactAgentFactory agentFactory;

    @Override
    public AgentResult call(Agent agent, String systemPrompt,
                           List<LlmService.ChatTurn> messages, List<String> tools) {
        ReactAgent reactAgent = agentFactory.buildDiscussAgent(agent, systemPrompt);
        // 转换 ChatTurn -> Spring AI Message
        // reactAgent.call(messages) -> AssistantMessage
        // 提取 content + toolCallSummary
        return new AgentResult(content, hasToolCalls, toolCallSummary);
    }
}
```

**新增工具实现**(示例:KnowledgeSearchTool):

```java
package com.dingring.infrastructure.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeSearchTool {

    private final RagService ragService;  // Phase E 实现

    @Tool(description = "搜索知识库,返回与查询相关的知识段落")
    public String searchKnowledge(
        @ToolParam(description = "搜索查询") String query,
        @ToolParam(description = "群组ID") Long groupId
    ) {
        return ragService.retrieve(query, groupId);
    }
}
```

**新增 Hook 实现**(示例:MemoryInjectionHook):

```java
package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.stereotype.Component;

/**
 * 在模型调用前注入群记忆。
 * 替代 ContextBuilder 的群记忆拼接逻辑。
 */
@Component
public class MemoryInjectionHook extends ModelHook {

    private final MemoryService memoryService;

    @Override
    public CompletableFuture<Map<String, Object>> beforeModel(OverAllState state, RunnableConfig config) {
        Long groupId = state.value("groupId", null);
        if (groupId != null) {
            String memory = memoryService.retrieveMemory(groupId);
            // 将记忆注入到 state 的 messages 中
            // 或通过 systemPrompt 追加
        }
        return CompletableFuture.completedFuture(Map.of());
    }
}
```

#### 7.3.3 app 层(节点调用 AgentSpeakerService)

**改造文件**:各 agent node(Phase C 创建的 `ChatNode` / `DiscussNode` / `ConcludeNode` 等)

```java
// ChatNode 改造前(Phase C):
String response = llmService.chat(agent, systemPrompt, turns);

// ChatNode 改造后(Phase D):
AgentResult result = agentSpeakerService.call(agent, systemPrompt, turns, List.of("UserProfileQuery"));
String response = result.content();
```

**新增文件**:`WorkNode` 完善(Phase C 占位 -> Phase D 完整实现)

```java
@Component("workHandler")
public class WorkNode implements NodeAction {

    private final AgentSpeakerService agentSpeakerService;
    private final GroupBroadcastService broadcastService;

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String task = state.value("input", "");
        Long groupId = state.value("groupId", null);

        // 构建工作 Agent(maxIters=15, 通用工具集)
        Agent workAgent = resolveWorkAgent(groupId);
        String systemPrompt = buildWorkPrompt(task);

        // HITL: 推送任务开始通知到群聊
        broadcastService.broadcast(groupId, "WORK_START", Map.of("task", task));

        AgentResult result = agentSpeakerService.call(workAgent, systemPrompt,
            List.of(LlmService.ChatTurn.user(task)),
            List.of("ReadFile", "WriteFile", "SearchCode", "ExecuteCommand", "Git"));

        // 工作产出沉淀
        return Map.of("workResult", result.content());
    }
}
```

### 7.4 关键设计决策

1. **SAA ReactAgent 为主**:使用 `spring-ai-alibaba-agent-framework` 的 `ReactAgent`,非 AgentScope 的 `ReActAgent`
2. **SAA Hook/Interceptor**:使用 `ModelHook` / `AgentHook` / `ToolInterceptor`,非 AgentScope 废弃的 Hook
3. **maxIters 控制**:讨论=3(不深陷循环),工作=15(深度 ReAct)
4. **Toolkit 按场景注入**:闲聊=画像查询,讨论=知识检索,收束=历史主题,工作=通用工具集
5. **AgentScope starter 作为补充**:如需 AgentScope 特有功能(如 MultiAgentFormatter)再引入
6. **领域事件**:SAA ReactAgent 事件通过 `AgentEventTranslator` 转换为领域事件

### 7.5 验证步骤

1. Agent 发言时主动调用 `KnowledgeSearchTool` 检索知识
2. `MemoryInjectionHook` 注入群记忆到 Agent 上下文
3. `StreamMarkerHook` 过滤 PASS/CONCLUDE 标记
4. ReAct 循环 maxIters=3 限制生效(讨论场景)
5. 不同节点注入不同工具集
6. WorkNode 可执行简单任务(如读文件)

### 7.6 不做什么

- 不引入 SKILL 热加载(Phase F)
- 不引入 Supervisor 模式(Phase F)
- 不引入 PermissionEngine
- 不引入完整 HITL(只做自动拦截)

### 7.7 实施记录(2026-08-08 完成)

**落地范围**:基础设施 + 节点改造 + Hook + 工具,一步到位全做。

| 项 | 方案设计 | 实际落地 | 差异说明 |
|---|---|---|---|
| 依赖 | `spring-ai-alibaba-agent-framework` + `spring-ai-alibaba-starter-agentscope` | 仅 `spring-ai-alibaba-agent-framework` | AgentScope starter 未引入,SAA 自有 ReactAgent 已满足需求(见 7.4 决策1) |
| ReAct 循环上限 | `maxIters` | `CompileConfig.recursionLimit` | SAA 1.1.2.3 API 实际为 recursionLimit,讨论=3/工作=15 不变 |
| AgentSpeakerService 签名 | `call(agent, systemPrompt, messages, List<String> tools)` | `call(agent, systemPrompt, messages, ToolSet toolSet, Map<String,Object> context)` | 用 ToolSet 枚举替代 List<String>,避免魔法字符串;新增 context 供 Hook 读取 groupId/userId/speakerAgentId |
| ContextBuilder 动态拼接 | 迁移到 Hook(7.3.4 节) | 已迁移:MemoryInjectionHook/ProfileInjectionHook/GroupRosterHook | ContextBuilder 构造函数移除 memoryService/profileService 依赖,只保留 messageRepository |
| 工具实现 | 3 个 @Tool | UserProfileQueryTool/TopicHistoryTool/KnowledgeSearchTool(占位) | KnowledgeSearchTool 待 Phase E RagService 实现后激活 |

**新增文件**:
- domain: `AgentSpeakerService.java`(端口 + ToolSet 枚举 + AgentResult record)
- infrastructure/runtime: `SaaReactAgentFactory.java`、`AgentSpeakerServiceImpl.java`
- infrastructure/agent/hook: `MemoryInjectionHook.java`、`ProfileInjectionHook.java`、`GroupRosterHook.java`
- infrastructure/agent/tool: `UserProfileQueryTool.java`、`TopicHistoryTool.java`、`KnowledgeSearchTool.java`

**改造文件**:
- app: `ContextBuilder.java`(移除动态拼接)、`ChatNode.java`/`DiscussNode.java`/`ConcludeNode.java`(迁移到 AgentSpeakerService)、`WorkNode.java`(完善深度 ReAct)
- infrastructure: `pom.xml`(引入 agent-framework)

**测试**:
- `ContextBuilderTest` 重写:移除 members 参数 + 删除迁移到 Hook 的 roster/memory 断言,保留 7 tests
- 新增 3 个 Hook 测试:MemoryInjectionHookTest(3)、ProfileInjectionHookTest(3)、GroupRosterHookTest(4) = 10 tests
- 全量验证:`mvn clean test` 7 模块 SUCCESS,Tests run: 302, Failures: 0, Errors: 0, Skipped: 0

**关键技术点**:
- Hook 注入方式:`beforeModel` 返回 `Map.of("messages", new SystemMessage(...))`,SAA 图引擎按 AppendStrategy 追加到 state 的 messages 列表
- OverAllState 虽为 final 类,但 `OverAllState(Map)` 构造可用真实实例,Hook 测试无需 mock 框架类
- CompileConfig.recursionLimit 替代 maxIters(SAA 1.1.2.3 API 变更)

---

## 八、Phase E:RAG 用 SAA VectorStore

### 8.1 目标

落地已批准的 [RAG 设计文档](file:///Users/Zhuanz/IdeaProjects/DingRingJ/docs/superpowers/specs/2026-08-03-rag-knowledge-base-design.md),基于 SAA VectorStore + EmbeddingModel + LlmReranker。

### 8.2 改动范围(概要)

- `dingRing-domain`:新增 `RagService` 端口(`retrieve(query, groupId) -> String`)、`Reranker` 端口
- `dingRing-infrastructure`:`SaaRagService`(PgVectorStore + EmbeddingModel)、`LlmReranker`、`DocumentIngestionPipeline`
- `dingRing-infrastructure`:`VectorDataSourceConfig`(PostgreSQL 第二数据源)
- `dingRing-adapter`:`KbController`(文档上传 REST)
- `dingRing-infrastructure/agent/hook`:`RagInjectionHook`(ModelHook,Agent 发言时自动检索)
- Nacos `prompt-config.json`:新增 `rag-rerank` prompt 模板

### 8.3 关键决策

- SAA 继承 Spring AI 的 VectorStore 生态,直接用 `PgVectorStore`
- 检索流程:向量召回 Top-20 + LLM 重排 Top-5
- 容错:任何环节失败不阻塞群聊主流程
- 双层过滤:全局知识 + 群专属知识

### 8.4 验证

- 上传 PDF -> 切片入库 -> 群聊提问 -> 检索注入 -> Agent 发言引用知识
- LLM 重排失败时降级为纯向量 Top-5

---

## 九、Phase F:SKILL 配置模块 + WorkNode Supervisor 增强

### 9.1 目标

SKILL 配置模块管理 Agent 技能(工具组 + prompt),支持热加载;WorkNode 增强 Supervisor 模式。

### 9.2 改动范围(概要)

#### SKILL 配置模块
- `dingRing-domain`:`Skill` 实体、`SkillRepository` 端口、`SkillLoaderService` 端口
- `dingRing-infrastructure`:`SkillRepositoryImpl`(MyBatis)、`SkillToolkitFactory`(按 Skill 配置构建 Toolkit)、`SkillHotReloader`(Nacos 监听)
- `dingRing-app`:`SkillAppService`(CRUD)
- `dingRing-adapter`:`SkillController`(REST)
- Nacos:`skill-config.json`

#### WorkNode Supervisor 增强
- 使用 SAA `AgentTool.getFunctionToolCallback(agent)` 将子 Agent 包装为工具
- `SupervisorAgentFactory`:构建 Supervisor Agent,注册子 Agent 作为工具
- `WorkProgressBroadcastHook`:子 Agent 工具调用后推送进度到群聊
- 新增 WS 消息类型:`WORK_PROGRESS` / `WORK_RESULT` / `WORK_CONFIRM_REQUEST`

### 9.3 关键 API(SAA)

```java
// SAA AgentTool:将 ReactAgent 包装为工具
ToolCallback calendarTool = AgentTool.getFunctionToolCallback(calendarAgent);

// Supervisor Agent 注册子 Agent 作为工具
ReactAgent supervisor = ReactAgent.builder()
    .name("work-supervisor")
    .model(model)
    .tools(
        AgentTool.getFunctionToolCallback(codeReaderAgent),
        AgentTool.getFunctionToolCallback(codeWriterAgent),
        AgentTool.getFunctionToolCallback(testerAgent)
    )
    .maxIters(15)
    .build();
```

---

## 十、风险与缓解

| 风险 | 缓解 |
|---|---|
| Spring Boot 3.4->3.5 breaking changes | Phase 0 先单独升级,全量回归测试 |
| Spring AI 1.0->1.1 API 变更 | Phase 0 验证 `OpenAiChatModel`/`OpenAiApi` builder API |
| SAA `resolveUrl` 兼容性 | Phase A 优先验证 StepFun/CloudBase 双重版本号 |
| StateGraph 条件边表达式解析 | 自定义简单表达式解析器(仅支持 `==` / `>=` / `<`),不引入 SpEL |
| 讨论态 loop 边导致死循环 | `maxRounds` 熔断 + 全员 PASS 转 WAIT(阻塞等用户) |
| ReactAgent maxIters API 不确定 | Phase D 实现时查阅 SAA 源码 `ReactAgent.Builder` 确认 |
| AgentScope starter 与 SAA agent-framework 冲突 | 以 SAA agent-framework 为主,starter 仅补充 |
| Phase C 重构破坏现有行为 | 分小步替换,每个 NodeAction 行为对齐现有逻辑 |

---

## 十一、回退方案汇总

| Phase | 回退方式 |
|---|---|
| Phase 0 | Git revert |
| Phase A | `dingring.llm.mock=true` 降级为 MockLlmService(离线模式) |
| Phase B | `GroupBroadcastService` 降级为直接 `chatPusher`(保留旧实现) |
| Phase C | `DiscussionFlowService` 降级为旧 `DiscussionEngine`(`@ConditionalOnProperty` 切换) |
| Phase D | `AgentSpeakerService` 降级为纯 LLM 调用(不注入 Toolkit) |
| Phase E | RAG 失败不阻塞主流程(容错设计) |
| Phase F | WorkNode 降级为单 Agent(不用 Supervisor) |

---

## 十二、实施顺序与依赖关系

```
Phase 0 (版本升级)
  ↓ (必须先完成)
Phase A (SAA Model + Nacos Prompt)
  ↓ (依赖 Phase A 的 Model 层和 Prompt 基础设施)
Phase B (群聊消息广播统一)
  ↓ (依赖 Phase B 的 GroupBroadcastService)
Phase C (StateGraph Workflow 重构)  ← 核心 Phase
  ↓ (依赖 Phase C 的 NodeAction 架构)
Phase D (ReactAgent + Toolkit)
  ↓ (依赖 Phase D 的 AgentSpeakerService)
Phase E (RAG VectorStore)
  ↓ (可与 Phase D 并行,但 KnowledgeSearchTool 依赖 Phase E)
Phase F (SKILL + Supervisor)
```

**建议**:Phase 0-C 串行执行(每步验证),Phase D-E 可并行,Phase F 最后。

---

## 十三、编码规范

### 注释要求

所有新增/改造的代码,注释必须详细,包含**设计要点**,降低 review 压力:

- **类级注释**:说明该类的职责、在架构中的位置(domain/app/infra 哪层)、与哪些组件协作、对应方案的哪个 Phase/小节
- **方法级注释**:说明业务意图(为什么这么做,不只是做了什么)、关键设计决策、边界条件、降级策略
- **关键逻辑注释**:对非直觉的判断/循环/状态变更,标注原因和方案依据(如"参见 6.3.6 消息标签设计")

### 命名规范

方法名和变量名必须自解释,对不直观的命名附加注释说明命名意图:

- **方法名**:体现业务语义而非技术操作。如 `advance()`(推进到下一个停顿点)而非 `executeGraph()`(执行图)
- **变量名**:体现业务含义。如 `discussMode`(讨论模式)而非 `state`(状态)
- **OverAllState 的 key**:统一用业务术语,在 domain 层定义常量类集中管理,避免散落字符串

```java
/**
 * OverAllState 的 key 常量,集中管理避免拼写不一致。
 * 所有 NodeAction 读写 state 时必须引用此类,不硬编码字符串。
 */
public final class StateKeys {
    public static final String GROUP_ID = "groupId";              // 群 ID
    public static final String TOPIC_ID = "topicId";              // 当前话题 ID
    public static final String DISCUSS_MODE = "discussMode";      // 讨论模式:CONVERGE/DIVERGE/WAIT/CONCLUDE_PROPOSED/CONCLUDE
    public static final String DIVERGE_ROUNDS = "divergeRounds";  // 发散轮次计数(达 maxDivergeRounds 回拉)
    public static final String MESSAGES = "messages";             // 近期消息窗口(AppendStrategy)
    public static final String USER_HISTORY_HINT = "userHistoryHint";  // 话题重启时的历史表现提示(给 Agent)
    public static final String RESTART_HINT = "restartHint";      // 话题重启提示(给前端)
    public static final String CONCLUDED = "concluded";           // 是否已收束
    private StateKeys() {}
}
```

**命名示例**:

| 命名 | 含义 | 注释要求 |
|---|---|---|
| `advance()` | 推进流程到下一个停顿点(WAIT/CONCLUDE_PROPOSED/END) | 方法注释说明"可能需要多次调用" |
| `paceForMode()` | 根据讨论模式决定发言间隔 | 方法注释说明各模式的 pace 策略 |
| `discussMode` | 讨论模式枚举 | 字段注释列出所有可选值 |
| `onMessageSaved()` | 消息入库后的回调(打标签+触发摘要) | 方法注释说明同步/异步分界 |
| `isObviousNoise()` | 规则判断是否明显噪音(纯标点/<5字) | 方法注释说明"零 LLM 调用" |
| `formatHistoryHint()` | 格式化话题历史表现为 Agent 可读的提示文本 | 方法注释说明"多条记录=进步轨迹" |

**示例**:

```java
/**
 * 消息标签器:每条消息入库时打标签,零 LLM 调用。
 * <p>设计要点(方案 6.3.6):
 * - 用户消息永远标记为 KEY,原文即观点,不摘要
 * - Agent 短回复(纯标点/<5字)标记为 NOISE,不进入 Agent 上下文,减少噪音
 * - 其他 Agent 发言标记为 VIEWPOINT,由 MessageSummaryHandler 异步调 LLM 判断是否有观点并摘要
 * - 带协作标记的发言([[DIVERGE]]/[[ASK_USER]])单独分类,标记即观点,不需额外摘要
 * <p>协作关系:被 DiscussNode 调用(onMessageSaved),产出 tag 存入 group_message.tag 列
 */
@Component
public class MessageTagger {

    /**
     * 对消息打标签。
     * <p>判定优先级:用户消息 > 协作标记 > 内容长度。
     * 无 LLM 调用,纯规则判断,可同步执行不阻塞主流程。
     */
    public MessageTag tag(GroupMessage msg) { ... }
}
```
