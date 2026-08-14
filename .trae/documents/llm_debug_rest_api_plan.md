# 暴露 LLM 调试 REST API 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在应用内部暴露一组仅用于调试的 `/api/debug/llm/**` REST 接口，Postman 可直接提交「Agent 配置 + systemPrompt + turns」请求，应用内部调用 `SpringAiLlmService#chat`（含带 CallOptions 的 4 参重载）和 `#chatStream` 产生真实 LLM 调用，将完整响应文本、耗时、截断长度等信息返回。

**Architecture:** 新增一个 `@RestController` + 配套 Request/Response DTO，落在 adapter 层，通过 `@ConditionalOnProperty("dingring.debug.llm.enabled")` 控制 Bean 是否注册，prod 环境默认不开启不会被意外访问。Controller 只做两件事：1) 把 HTTP Body 映射成 `Agent` + `List<ChatTurn>` + `CallOptions`；2) 调用 `LlmService` 后把返回值 + 耗时等包装成统一 `ApiResponse<T>` 返回。流式走同步等待完成后一次性返回（避免 Postman 对 SSE 的处理差异），等价于你在生产里看到的最终文本，同时保证 EventAspect 切面和 `dingring.logging.llm-prompt-enabled` 日志配置生效，行为与真实调用 100% 一致。

**Tech Stack:** Spring Boot 3.4 / Spring MVC / Jakarta Validation / fastjson 1.x（复用现有依赖，零新增）

---

## Repo research conclusion

- REST Controller 都在 `dingRing-adapter/src/main/java/com/dingring/adapter/rest/`：`AgentController`、`TopicController` 等，统一前缀 `/api/**`，返回 `ApiResponse<T>`
- 全局异常处理在 `GlobalExceptionHandler`，`BizException` 会被转为 4xx/5xx + `ApiResponse.fail`
- Request/Response DTO 约定路径：`dingRing-app/src/main/java/com/dingring/app/dto/{request|response}/`
- `LlmService` 接口在 `dingRing-domain/src/main/java/com/dingring/domain/service/LlmService.java`：
  - `String chat(Agent, String systemPrompt, List<ChatTurn>)`
  - `String chat(Agent, String systemPrompt, List<ChatTurn>, CallOptions)` ← 用户实际想测的 4 参重载
  - `String chatStream(Agent, String systemPrompt, List<ChatTurn>, Consumer<String> onDelta)`
- `Agent` 在 `dingRing-domain/.../agent/Agent.java`：字段含 name/baseUrl/apiKey/modelName/temperature()/maxTokens()（temperature/maxTokens 从 feature Map 读取）
- 条件装配参考：`SpringAiLlmService` 使用了 `@ConditionalOnProperty(name="dingring.llm.mock", havingValue="false", matchIfMissing=true)`

## Files and modules to be edited

| 类型 | 路径 | 说明 |
|------|------|------|
| Create | `dingRing-app/src/main/java/com/dingring/app/dto/test/LlmDebugChatRequest.java` | `/chat` 与 `/chat-stream` 共用的 Request DTO，单独放 test 子包与业务 DTO 隔离 |
| Create | `dingRing-app/src/main/java/com/dingring/app/dto/test/LlmDebugChatResponse.java` | Response DTO，同上 |
| Create | `dingRing-adapter/src/main/java/com/dingring/adapter/rest/TestController.java` | 调试 Controller，条件装配，`/api/test/llm/**` 前缀，两个 POST 接口 |
| Modify | `start/src/main/resources/application.yml` | 增加 `dingring.debug.llm.enabled: true`（dev 默认开） |
| Modify | `start/src/main/resources/application-prod.yml` | 显式 `dingring.debug.llm.enabled: false`，避免 prod 误启用 |

## Steps

### Task 1: Request/Response DTO

**Files:**
- Create: `dingRing-app/src/main/java/com/dingring/app/dto/test/LlmDebugChatRequest.java`
- Create: `dingRing-app/src/main/java/com/dingring/app/dto/test/LlmDebugChatResponse.java`

- [ ] **Step 1: 写 LlmDebugChatRequest DTO**

结构如下（直接作为完整文件内容使用）：

```java
package com.dingring.app.dto.test;

import com.dingring.domain.service.LlmService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * LLM 调试接口请求（/chat 与 /chat-stream 共用）。
 * 直接把 Agent 的 baseUrl/apiKey/modelName 放在请求体里，不依赖 DB 里已有的 Agent 记录，
 * 这样 Postman 里用生产参数复制粘贴即可发起真实调用，不用额外改 DB。
 */
@Data
public class LlmDebugChatRequest {

    /** Agent 花名（仅用于日志和响应回显，允许留空用默认值） */
    private String agentName;

    /** LLM 端点，例如 https://api.siliconflow.cn/v1 */
    @NotBlank(message = "baseUrl 不能为空")
    private String baseUrl;

    @NotBlank(message = "apiKey 不能为空")
    private String apiKey;

    @NotBlank(message = "modelName 不能为空")
    private String modelName;

    /** Agent 默认采样温度（可被 override.temperature 覆盖） */
    private Double temperature;

    /** Agent 默认 maxTokens（可被 override.maxTokens 覆盖） */
    private Integer maxTokens;

    /** 系统提示词（对应 chat 方法第二个参数） */
    private String systemPrompt;

    /** 对话消息（时间升序，role=USER/ASSISTANT） */
    @NotEmpty(message = "messages 不能为空")
    @Valid
    private List<Turn> messages;

    /**
     * 单次调用参数覆盖（对应 LlmService.CallOptions）。
     * 不传则完全沿用上面的 temperature/maxTokens + 全局默认读超时。
     */
    private OverrideOptions override;

    @Data
    public static class Turn {
        @NotBlank(message = "role 不能为空（USER / ASSISTANT）")
        private String role;
        @NotBlank(message = "content 不能为空")
        private String content;

        public LlmService.ChatTurn toChatTurn() {
            if ("ASSISTANT".equalsIgnoreCase(role)) {
                return LlmService.ChatTurn.assistant(content);
            }
            return LlmService.ChatTurn.user(content);
        }
    }

    @Data
    public static class OverrideOptions {
        /** 覆盖 temperature（分类任务建议 0） */
        private Double temperature;
        /** 覆盖 maxTokens（分类输出 JSON 建议限小） */
        private Integer maxTokens;
        /** 覆盖读超时秒数（短任务建议 15） */
        private Long readTimeoutSeconds;

        public LlmService.CallOptions toCallOptions() {
            return new LlmService.CallOptions(temperature, maxTokens, readTimeoutSeconds);
        }
    }
}
```

- [ ] **Step 2: 写 LlmDebugChatResponse DTO**

```java
package com.dingring.app.dto.test;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 调试接口响应：返回最终文本 + 调试辅助信息，不影响业务本身。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmDebugChatResponse {
    /** LLM 返回的完整文本（已经 trim 过） */
    private String content;
    /** 调用耗时毫秒（从进入 chat/chatStream 到返回的 wall-clock 时间） */
    private long durationMs;
    /** 返回文本的字符长度（用于快速判断是否被截断/空响应） */
    private int length;
    /** 流式模式下：收到的 delta 块数量（非流式返回 0） */
    private int deltaCount;
    /** 流式模式下：所有 delta 块按顺序拼接（非流式为 null）—— 和 content 字段等价，仅用于排查拼接异常 */
    private List<String> deltas;
}
```

---

### Task 2: TestController

**Files:**
- Create: `dingRing-adapter/src/main/java/com/dingring/adapter/rest/TestController.java`

- [ ] **Step 1: 创建 Controller 文件**

要点：
- `@RestController` + `@RequestMapping("/api/test/llm")`
- 加 `@ConditionalOnProperty(name = "dingring.debug.llm.enabled", havingValue = "true")`：prod 不配/配 false 时这个 Bean 根本不会注册，对外也没有路由
- 构造器注入 `LlmService`
- 两个 POST 方法：`POST /chat` 和 `POST /chat-stream`

完整代码如下：

```java
package com.dingring.adapter.rest;

import com.dingring.app.dto.test.LlmDebugChatRequest;
import com.dingring.app.dto.test.LlmDebugChatResponse;
import com.dingring.common.response.ApiResponse;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LLM 调试专用 REST API。
 *
 * <p>目的：通过 Postman（任何 HTTP 客户端）直接构造请求，产生真实的 LLM 调用，
 * 用于排查：API Key 是否有效、baseUrl 解析是否正确、temperature/maxTokens 覆盖是否生效、
 * EventAspect 日志是否完整、流式 delta 是否按预期输出等。
 *
 * <p>安全：仅当配置 {@code dingring.debug.llm.enabled=true} 时该控制器才注册。
 * 生产配置必须置为 false（见 application-prod.yml）。
 */
@RestController
@RequestMapping("/api/test/llm")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.debug.llm.enabled", havingValue = "true")
public class TestController {

    private final LlmService llmService;

    /**
     * 对应 {@link LlmService#chat(Agent, String, java.util.List, LlmService.CallOptions)}。
     * 4 参重载（带 CallOptions）是你实际想要测试的方法——请求里 override 字段非空就用它。
     */
    @PostMapping("/chat")
    public ApiResponse<LlmDebugChatResponse> chat(@Valid @RequestBody LlmDebugChatRequest req) {
        Agent agent = toAgent(req);
        String systemPrompt = req.getSystemPrompt();
        List<LlmService.ChatTurn> turns = req.getMessages().stream()
                .map(LlmDebugChatRequest.Turn::toChatTurn)
                .collect(Collectors.toList());
        LlmService.CallOptions options = req.getOverride() == null
                ? null : req.getOverride().toCallOptions();

        long start = System.currentTimeMillis();
        String content = (options == null)
                ? llmService.chat(agent, systemPrompt, turns)
                : llmService.chat(agent, systemPrompt, turns, options);
        long cost = System.currentTimeMillis() - start;

        return ApiResponse.ok(LlmDebugChatResponse.builder()
                .content(content)
                .durationMs(cost)
                .length(content == null ? 0 : content.length())
                .build());
    }

    /**
     * 对应 {@link LlmService#chatStream(Agent, String, java.util.List, java.util.function.Consumer)}。
     * 为了兼容 Postman（不用 SSE 才能直接看完整结果），这里同步等流式结束再一次性返回，
     * 同时把每个 delta 块单独收集在 deltas 字段里，方便你对照打印/前端流式渲染是否有漏块。
     */
    @PostMapping("/chat-stream")
    public ApiResponse<LlmDebugChatResponse> chatStream(@Valid @RequestBody LlmDebugChatRequest req) {
        Agent agent = toAgent(req);
        String systemPrompt = req.getSystemPrompt();
        List<LlmService.ChatTurn> turns = req.getMessages().stream()
                .map(LlmDebugChatRequest.Turn::toChatTurn)
                .collect(Collectors.toList());

        List<String> deltas = new ArrayList<>();
        long start = System.currentTimeMillis();
        String content = llmService.chatStream(agent, systemPrompt, turns, deltas::add);
        long cost = System.currentTimeMillis() - start;

        return ApiResponse.ok(LlmDebugChatResponse.builder()
                .content(content)
                .durationMs(cost)
                .length(content == null ? 0 : content.length())
                .deltaCount(deltas.size())
                .deltas(deltas)
                .build());
    }

    /**
     * 把请求体里的 baseUrl/apiKey/modelName/temperature/maxTokens 装配成临时 Agent 对象。
     * 不落库，仅用于调用 LlmService（该接口按 Agent 字段读配置，不查 DB）。
     */
    private Agent toAgent(LlmDebugChatRequest req) {
        Agent a = new Agent();
        // id/name 仅用于 LogHelper 里"agent={}"日志展示，不影响 API 调用本身
        a.setId(-1L);
        a.setName(req.getAgentName() == null ? "debug" : req.getAgentName());
        a.setBaseUrl(req.getBaseUrl());
        a.setApiKey(req.getApiKey());
        a.setModelName(req.getModelName());
        // temperature()/maxTokens() 两个方法读的是 feature 里的对应 key，所以要放进 feature Map
        java.util.Map<String, Object> feature = new java.util.HashMap<>();
        if (req.getTemperature() != null) {
            feature.put("temperature", req.getTemperature());
        }
        if (req.getMaxTokens() != null) {
            feature.put("maxTokens", req.getMaxTokens());
        }
        if (!feature.isEmpty()) {
            a.setFeature(feature);
        }
        return a;
    }
}
```

---

### Task 3: 配置文件

**Files:**
- Modify: `start/src/main/resources/application.yml`
- Modify: `start/src/main/resources/application-prod.yml`

- [ ] **Step 1: 给 application.yml 加开关**

在文件末尾（`dingring` 节点下）追加：

```yaml
dingring:
  # （其他已有配置保持不动）...
  debug:
    llm:
      # dev 环境开启调试接口；prod 务必在 application-prod.yml 中关闭
      enabled: true
```

- [ ] **Step 2: application-prod.yml 显式关掉**

在 `application-prod.yml` 末尾追加：

```yaml
dingring:
  debug:
    llm:
      enabled: false
```

---

### Task 4: 验证

- [ ] **Step 1: 编译项目**

Run: `mvn compile -q`

Expected: BUILD SUCCESS（没有编译错误；`@ConditionalOnProperty` 需要的是 spring-boot-autoconfigure，已随父 POM 引入，无缺依赖）

- [ ] **Step 2: 启动应用 + 用 curl 打 /chat**

Run:
```bash
# 先启动（你平时的启动命令）
mvn spring-boot:run -pl start
# 另一个终端：用你在日志里贴的小林配置做一次最小调用
curl -X POST http://localhost:8080/api/debug/llm/chat \
  -H "Content-Type: application/json" \
  -d '{
    "agentName": "小林",
    "baseUrl": "https://api.siliconflow.cn/v1",
    "apiKey": "sk-XXX-REPLACE-WITH-REAL-KEY",
    "modelName": "deepseek-ai/DeepSeek-V4-Flash",
    "temperature": 0.7,
    "maxTokens": 256,
    "systemPrompt": "你是一位谦逊的应届生，请用一句话回答。",
    "messages": [{"role":"USER","content":"什么是 Spring AOP？"}],
    "override": {"maxTokens": 128, "readTimeoutSeconds": 15}
  }'
```

Expected（示例）:
```json
{
  "success": true,
  "errorCode": null,
  "message": "操作成功",
  "data": {
    "content": "Spring AOP 是一种通过在运行期织入横切逻辑（如日志、事务）的机制，用来解耦非业务代码。",
    "durationMs": 2147,
    "length": 41,
    "deltaCount": 0,
    "deltas": null
  }
}
```

同时观察 `logs/`：
1. EventAspect 应打印 `request=...` 跟 `result=...` 两条日志（4 参 chat 重载的 @Event 会被触发），且 request 里 List<ChatTurn> 已正确显示 `{"role":"USER","content":"..."}`（不再是 {}）
2. `LogHelper.printLog` 应输出 `STREAM_PROMPT / STREAM_RESPONSE` 或 `CHAT_PROMPT / CHAT_RESPONSE`（取决于方法）

- [ ] **Step 3: 打 /chat-stream 并验证 deltas 字段**

上面同一请求换 path: `/api/test/llm/chat-stream`（其余不变）。Expected: 返回里 `deltaCount > 0`，`content` 等于 `String.join("", deltas)`。

---

## Potential dependencies or considerations

1. **`@ConditionalOnProperty` 的 matchIfMissing 语义**：计划里只写 `havingValue = "true"`（没有配默认 matchIfMissing，默认 false），所以 application-prod.yml 不配就等于 false，很安全；只在 application.yml/dev 里显式配 true 才开。
2. **不要误把生产 Agent 存在库里当模板**：直接把 baseUrl/apiKey/modelName 放在请求体里，是你选择 Postman 方式的核心诉求——请求里填啥就用啥，完全不依赖 DB；`id=-1L/name=debug` 只用于日志回显，绝不落库。
3. **API Key 明文日志**：你的 Agent 对象里 `apiKey` 字段会被 EventAspect 打印出来（正是你之前日志里看到的）。如果你不希望把真实 Key 打到日志里，方案有二：
   - a) 在 Agent#getApiKey 上注解一个 `@JSONField(serialize=false)`（影响整个项目所有 @Event 日志，风险大，不推荐）
   - b) 在本调试接口里请求体单独留一个开关（可在后续迭代加，不在本次范围）—— 默认按现状保留，你在生产调试时自己权衡；
   - 我们采用现状 + 明确风险提示：**真实 Key 会被 EventAspect 切面请求日志记录，请避免在公开/多人共享的日志采集系统里运行**。
4. **流式不做 SSE**：Postman 对 SSE 的支持不如对普通 JSON 直观，这里实现为"同步等全部 delta 返回后一次性打包给你"，本质等同于 chat，额外给你 delta 列表方便对照，不影响 LLM 内部仍走 chatStream 分支（这保证了 EventAspect 上 `CHAT_TO_LLM` 流式的那个注解逻辑、`LogHelper` 里的 `STREAM_PROMPT` 分支都会被触发）。
5. **Override 为 null 时回退 chat(3 参)**：chat(3 参) 内部默认调用 chat(4 参) 且 options=null，直接走 chat(4 参) 也行，为更贴近"4 参重载被触发"的语义，代码里显式分支：override 为 null 调 3 参，非空调 4 参。
6. **Bean Validation Group 不需要**：调试接口只有一种提交方式，create/update 不分，因此不引入 Group。

## Risk handling

| 风险 | 影响 | 处理 |
|------|------|------|
| prod 误开调试接口被外网扫到 | 真金白银扣 token + 泄露 Key | `@ConditionalOnProperty` + application-prod.yml 显式 false；返回结构不把 apiKey 回显；仅 `localhost:8080` 监听（生产环境一般都有网关/Ingress 路径白名单） |
| 超长 systemPrompt 导致日志截断 | EventAspect 之前不做截断（项目约束），日志文件暴涨 | 这是项目硬约束"方法参数日志不做截断"，保持现状；真要调试时手动改 systemPrompt 长度即可 |
| override.temperature / feature.temperature 不一致 | 同一次请求存在两份温度参数，调 LLM 时实际取的是 options 的那份还是 agent 的？ | `SpringAiLlmService.buildChatModel` 方法第一行：`options != null && options.temperature() != null ? options.temperature() : agent.temperature()`，语义一致：**单次覆盖优先**，本计划严格复用这条语义 |
| baseUrl 尾部斜杠 / 重复路径 | 已在 `SpringAiLlmService.resolveUrl` 里修过，本计划不重复实现 | 请求直接透传给 buildChatModel，保持使用同一套解析逻辑 |
| curl/Postman 连不上（CORS、端口等） | 调试失败 | 调试接口只走本机 HTTP；如果要跨前端调用，沿用项目全局 CORS 配置，本计划不额外开 cors 配置 |
