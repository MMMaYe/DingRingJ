# LLM JSON 约束 + 推理日志 + 小模型路由判定 实施计划

## Summary

三项改进：
1. **JSON 输出约束**：在 `CallOptions` 中新增 `Boolean jsonMode` 字段，3 个 JSON 类调用点（路由判定/主持人决策/知识卡片）启用 `response_format=JSON_OBJECT`，自然语言场景不动
2. **推理过程日志**：Spring AI 从 1.0.0-M6 升级到 1.0.0 GA，`CallOptions` 新增 `logReasoning` 开关，开启后用 `LogHelper` 打印 `reasoning_content`
3. **路由判定小模型**：推荐 `glm-4-flash`（火山方舟，免费/极速/非推理），Agent 表新建记录

## Current State Analysis

### LLM 调用点全景（7 处）

| # | 调用点 | 期望格式 | 当前 CallOptions | 文件 |
|---|--------|----------|------------------|------|
| 1 | MessageRouter.route() | JSON | `(0.0, 4096, 15s)` | `dingRing-app/.../orchestrator/MessageRouter.java:70` |
| 2 | ModeratorService.decide() | JSON | 无（默认 temp=1.0） | `dingRing-app/.../orchestrator/ModeratorService.java:81` |
| 3 | CardEventHandler | JSON 数组 | 无（默认 temp=1.0） | `dingRing-app/.../event/CardEventHandler.java:64` |
| 4 | DiscussionEngine.chatWithRetry() | 自然语言 | 无 | `dingRing-app/.../orchestrator/DiscussionEngine.java:721` |
| 5 | ChatOrchestrator.chatWithRetry() | 自然语言 Markdown | 无 | `dingRing-app/.../orchestrator/ChatOrchestrator.java:236` |
| 6 | SimpleProfileService.extractProfile() | 自然语言纯文本 | 无 | `dingRing-infrastructure/.../memory/SimpleProfileService.java:50` |
| 7 | TestController | 开发者自定义 | 请求体传入 | `dingRing-adapter/.../rest/TestController.java` |

### 当前 CallOptions 定义

```java
// LlmService.java:61-69
record CallOptions(Double temperature, Integer maxTokens, Long readTimeoutSeconds) {}
```

### 当前 Spring AI 版本

```xml
<!-- pom.xml:32 -->
<spring-ai.version>1.0.0-M6</spring-ai.version>
```

### 关键问题

1. `ResponseFormat` 已 import 但未使用（`SpringAiLlmService.java:19`）
2. M6 的 `AssistantMessage` 无 `reasoningContent` 字段，推理模型（glm-5.2）的 `reasoning_content` 被丢弃
3. ModeratorService 和 CardEventHandler 期望 JSON 但未传 CallOptions，默认 temperature=1.0 有判定抖动风险
4. 路由判定用 glm-5.2（推理模型），reasoning 吃光 maxTokens 导致 content 为空

## Proposed Changes

### Task 1: 升级 Spring AI 到 1.0.0 GA

**Files:**
- Modify: `pom.xml`（根 POM，改版本号）
- Modify: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SpringAiLlmService.java`（适配 API 变化）

**Steps:**

- [ ] **Step 1: 改版本号**

`pom.xml` 第 32 行：
```xml
<!-- 改前 -->
<spring-ai.version>1.0.0-M6</spring-ai.version>
<!-- 改后 -->
<spring-ai.version>1.0.0</spring-ai.version>
```

- [ ] **Step 2: 编译验证 + 适配 API breaking change**

M6 → GA 可能有以下变化需要适配：
- `OpenAiChatOptions.builder()` API 可能微调
- `ChatResponse.getResult().getOutput().getText()` 可能改为 `getContent()`
- `AssistantMessage` 新增 `getReasoningContent()` 方法（这是我们要的）

编译后逐个修复报错，确保 `SpringAiLlmService` 编译通过。

- [ ] **Step 3: 验证 chat() 和 chatStream() 功能正常**

启动应用，用 TestController 的 `/api/test/llm/chat` 接口发一次调用，确认 GA 版本能正常调用 LLM。

---

### Task 2: 扩展 CallOptions（jsonMode + logReasoning）

**Files:**
- Modify: `dingRing-domain/src/main/java/com/dingring/domain/service/LlmService.java`

**Steps:**

- [ ] **Step 1: 扩展 CallOptions record**

```java
/**
 * 单次调用参数覆盖。字段为 null（或超时 ≤0）表示沿用 Agent 配置/全局默认。
 *
 * @param temperature        采样温度（分类任务建议 0）
 * @param maxTokens          生成上限（分类输出为小 JSON，建议限小）
 * @param readTimeoutSeconds 读超时秒数（短超时避免卡死调用方线程）
 * @param jsonMode           是否强制 JSON 输出（API 层 response_format=json_object）
 * @param logReasoning       是否打印推理过程（推理模型的 reasoning_content，非推理模型无效果）
 */
record CallOptions(Double temperature, Integer maxTokens, Long readTimeoutSeconds,
                   Boolean jsonMode, Boolean logReasoning) {

    /** 向后兼容：3 参构造器，jsonMode=false, logReasoning=false */
    public CallOptions(Double temperature, Integer maxTokens, Long readTimeoutSeconds) {
        this(temperature, maxTokens, readTimeoutSeconds, false, false);
    }
}
```

注意：保留 3 参构造器，现有调用点（DiscussionEngine / ChatOrchestrator / SimpleProfileService）不用改。domain 层不依赖 Spring AI 的 ResponseFormat。

---

### Task 3: buildChatModel 消费 jsonMode + logReasoning

**Files:**
- Modify: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/llm/SpringAiLlmService.java`

**Steps:**

- [ ] **Step 1: buildChatModel 里根据 jsonMode 设置 ResponseFormat**

在 `buildChatModel()` 方法的 `OpenAiChatOptions.builder()` 链中：

```java
OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
        .model(agent.getModelName())
        .temperature(temperature)
        .maxTokens(maxTokens);

// JSON 模式：API 层面强制输出合法 JSON
if (options != null && Boolean.TRUE.equals(options.jsonMode())) {
    builder.responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT));
}

OpenAiChatOptions chatOptions = builder.build();
```

- [ ] **Step 2: chat() 方法里根据 logReasoning 打印推理过程**

在 `chat()` 方法拿到 `ChatResponse` 后（GA 版本的 `AssistantMessage` 有 `getReasoningContent()`）：

```java
ChatResponse response = chatModel.call(new Prompt(aiMessages));
String text = response.getResult().getOutput().getText();

// GA 版本支持推理模型（如 glm-5.2）的 reasoning_content 字段
String reasoning = null;
try {
    reasoning = response.getResult().getOutput().getReasoningContent();
} catch (NoSuchMethodError ignored) {
    // 非 GA 版本兜底（理论上不会走到这里，因为已升级）
}

boolean shouldLogReasoning = options != null && Boolean.TRUE.equals(options.logReasoning());
if (shouldLogReasoning && reasoning != null && !reasoning.isBlank()) {
    LogHelper.printLog(SpringAiLlmService.class, "SpringAiLlmService.chat",
            "CHAT_REASONING", "推理过程",
            "agent={} model={}\n推理内容:\n{}",
            agent.getName(), agent.getModelName(), reasoning);
}
```

- [ ] **Step 3: chatStream() 同理处理 logReasoning**

在 `chatStream()` 的 `forEach` 里，每个 delta response 可能也带 reasoning_content（流式分块）。在 delta 处理后补打：

```java
// 流式模式下 reasoning_content 也会分块推送，累积后一次性打印
if (shouldLogReasoning) {
    String deltaReasoning = ... // 从 response 里提取 reasoning_content delta
    reasoningBuilder.append(deltaReasoning);
}
// 流结束后一次性打印
if (shouldLogReasoning && !reasoningBuilder.isEmpty()) {
    LogHelper.printLog(..., "STREAM_REASONING", "推理过程(流式)", ...);
}
```

注意：流式 reasoning_content 的字段名和分块方式需要实测 GA 版本行为，先实现非流式的，流式作为进阶。

---

### Task 4: 3 个 JSON 调用点传 jsonMode=true + logReasoning

**Files:**
- Modify: `dingRing-app/src/main/java/com/dingring/app/orchestrator/MessageRouter.java`
- Modify: `dingRing-app/src/main/java/com/dingring/app/orchestrator/ModeratorService.java`
- Modify: `dingRing-app/src/main/java/com/dingring/app/event/CardEventHandler.java`

**Steps:**

- [ ] **Step 1: MessageRouter.route() — 传 jsonMode=true + logReasoning=true**

```java
// 改前
new LlmService.CallOptions(ROUTE_TEMPERATURE, ROUTE_MAX_TOKENS, routeTimeoutSeconds)
// 改后
new LlmService.CallOptions(ROUTE_TEMPERATURE, ROUTE_MAX_TOKENS, routeTimeoutSeconds,
        true, true)  // jsonMode=true, logReasoning=true
```

同时把 `ROUTE_MAX_TOKENS` 从 4096 调小到 1024（小模型 + JSON 输出不需要这么多）。

- [ ] **Step 2: ModeratorService.decide() — 传 CallOptions(jsonMode=true, temp=0.0)**

当前未传 CallOptions（默认 temp=1.0），改为：

```java
// 改前
String raw = llmService.chat(moderatorAgent, PromptConstants.HOST_DECISION, turns);
// 改后
String raw = llmService.chat(moderatorAgent, PromptConstants.HOST_DECISION, turns,
        new LlmService.CallOptions(0.0, 1024, 15L,
                true, false));  // jsonMode=true, logReasoning=false
```

修复 temperature=1.0 的判定抖动风险。

- [ ] **Step 3: CardEventHandler — 传 CallOptions(jsonMode=true, temp=0.0)**

```java
// 改前
String raw = llmService.chat(agent, PromptConstants.KNOWLEDGE_EXTRACT, turns);
// 改后
String raw = llmService.chat(agent, PromptConstants.KNOWLEDGE_EXTRACT, turns,
        new LlmService.CallOptions(0.0, 2048, null,
                true, false));  // jsonMode=true, logReasoning=false
```

知识卡片提取可能输出较长 JSON 数组，maxTokens 保留 2048。

---

### Task 5: 路由判定小模型 — glm-4-flash

**Files:**
- Modify: 数据库 Agent 表（新建记录）

**Steps:**

- [ ] **Step 1: 确认 glm-4-flash 在火山方舟的可用性**

用 TestController `/api/test/llm/chat` 发一次测试调用：
```json
{
  "agentName": "路由判定器",
  "baseUrl": "https://ark.cn-beijing.volces.com/api/coding/v3",
  "apiKey": "ark-5ea6b45a-caf8-41b5-b444-afd8108077ef-8f5d1",
  "modelName": "glm-4-flash",
  "systemPrompt": "你是群聊意图分类器。严格输出一行 JSON。",
  "messages": [{"role":"USER","content":"用户消息：看重要性，如果要求时效性，我肯定不会杀"}],
  "override": {"temperature": 0.0, "maxTokens": 256, "readTimeoutSeconds": 15}
}
```

验证：
- 模型可调用（非 404/400）
- content 非空（非推理模型，不会出现 reasoning 吃光 token）
- 返回的是合法 JSON

- [ ] **Step 2: 在 Agent 表新建路由判定器记录**

用 MySQL MCP 工具执行 INSERT（需用户确认）：

```sql
INSERT INTO agent (name, description, system_prompt, base_url, api_key, model_name, call_type, feature, create_time, update_time)
VALUES (
  '路由判定器',
  '意图分类专用Agent，使用小模型快速判定',
  '',  -- 路由判定不使用 Agent 自带 systemPrompt，由 MessageRouter 传入
  'https://ark.cn-beijing.volces.com/api/coding/v3',
  'ark-5ea6b45a-caf8-41b5-b444-afd8108077ef-8f5d1',
  'glm-4-flash',
  'API',
  '{"temperature": 0.0, "maxTokens": 1024}',
  NOW(),
  NOW()
);
```

- [ ] **Step 3: 验证 MessageRouter 能正确使用新 Agent**

确认 `MessageRouter.route()` 接收的 `judge` Agent 是新建的 `glm-4-flash` 记录，而非老王的 `glm-5.2`。需要检查 `judge` Agent 是怎么注入的（可能是在 DiscussionEngine 或 ChatOrchestrator 里从 DB 读取的）。

---

### Task 6: TestController 调试接口适配新 CallOptions

**Files:**
- Modify: `dingRing-adapter/src/main/java/com/dingring/adapter/rest/TestController.java`
- Modify: `dingRing-app/src/main/java/com/dingring/app/dto/test/LlmDebugChatRequest.java`

**Steps:**

- [ ] **Step 1: LlmDebugChatRequest 加 jsonMode 和 logReasoning 字段**

```java
/** 是否强制 JSON 输出（response_format=json_object） */
private Boolean jsonMode;  // 默认 false

/** 是否打印推理过程 */
private Boolean logReasoning; // 默认 false
```

- [ ] **Step 2: TestController.toCallOptions() 组装新字段**

```java
private LlmService.CallOptions toCallOptions(LlmDebugChatRequest req) {
    if (req.getOverride() == null && req.getJsonMode() == null && req.getLogReasoning() == null) {
        return null;
    }
    LlmDebugChatRequest.OverrideOptions ov = req.getOverride();
    return new LlmService.CallOptions(
        ov == null ? null : ov.getTemperature(),
        ov == null ? null : ov.getMaxTokens(),
        ov == null ? null : ov.getReadTimeoutSeconds(),
        Boolean.TRUE.equals(req.getJsonMode()),      // jsonMode
        Boolean.TRUE.equals(req.getLogReasoning())   // logReasoning
    );
}
```

---

## Assumptions & Decisions

| 决策项 | 结论 | 理由 |
|--------|------|------|
| JSON 强制范围 | 只对 3 个 JSON 调用点 | 自然语言场景（群聊发言/结论/画像）强转 JSON 会破坏流式渲染、浪费 token |
| CallOptions 扩展方式 | Boolean jsonMode + Boolean logReasoning | domain 层不依赖 Spring AI 的 ResponseFormat 类，最简洁 |
| Spring AI 版本 | 1.0.0-M6 → 1.0.0 GA | GA 版 AssistantMessage 新增 reasoningContent，API 基本兼容 M6 |
| 不升 2.0.0 | 需 Spring Boot 4.0+ + Java 21+，架构级重写 | 本次任务范围不包含框架大升级 |
| 路由判定模型 | glm-4-flash（火山方舟） | 免费/极速/非推理模型/已有 API Key |
| ModeratorService 修复 | 额外传 CallOptions(0.0, 1024, 15s, jsonMode=true, false) | 修复当前 temp=1.0 的判定抖动风险 |
| ROUTE_MAX_TOKENS | 4096 → 1024 | 小模型 + JSON 输出不需要 4096 |
| 3 参构造器保留 | 现有调用点不改 | 向后兼容，DiscussionEngine/ChatOrchestrator/SimpleProfileService 保持 TEXT 模式 |

## Verification Steps

1. **编译验证**：`mvn compile -q` 全模块编译通过
2. **Spring AI GA 兼容性**：启动应用，确认无 Bean 创建异常 / 自动配置冲突
3. **JSON 模式验证**：用 TestController `/api/test/llm/chat` 传 `outputFormat=JSON`，确认 response 里 content 是合法 JSON（非 markdown 包裹、非前后杂文）
4. **推理日志验证**：用 TestController 传 `logReasoning=true` + glm-5.2 模型，确认日志里出现 `CHAT_REASONING` 条目
5. **小模型验证**：用 TestController 传 `modelName=glm-4-flash`，确认 content 非空、返回合法 JSON
6. **路由判定端到端**：发一条群聊消息，确认 MessageRouter 日志显示使用 `glm-4-flash` 模型、返回合法 JSON、路由结果正确

## Risk Handling

| 风险 | 影响 | 处理 |
|------|------|------|
| Spring AI M6→GA 有 breaking change | 编译失败 | 逐个修复编译错误，GA 的 API 变化主要是新增字段为主 |
| glm-4-flash 不支持 response_format | JSON 模式无效 | Step 1 先验证；不支持则退回提示词约束 |
| GA 版 AssistantMessage API 与预期不符 | getReasoningContent() 不存在 | 用 try-catch + 反射兜底 |
| ModeratorService 传 CallOptions 后行为变化 | 主持人决策结果分布变化 | temp 从 1.0→0.0 会让输出更确定性，属于正向修复 |
