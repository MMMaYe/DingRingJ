# LlmService 与 AgentSpeakerService 合并实施记录

> 日期：2026-08-14

## 结论

DingRing 的 LLM 调用统一由 `LlmService` 提供。删除 `AgentSpeakerService` 接口及 `AgentSpeakerServiceImpl` 实现；无工具确定性任务与带工具 ReactAgent 调用均使用 `LlmService.chat` 重载。

## 目标接口

```java
String chat(Agent, String, List<ChatTurn>);
String chat(Agent, String, List<ChatTurn>, CallOptions);
AgentResult chat(Agent, String, List<ChatTurn>, ToolSet, Map<String, Object>);
String chatStream(Agent, String, List<ChatTurn>, Consumer<String>);
AgentResult chatStream(Agent, String, List<ChatTurn>, ToolSet, Map<String, Object>, Consumer<String>);
```

参数列表不同，不依赖返回类型进行重载。`AgentResult` 与 `ToolSet` 归入 `LlmService`。

## 实现

- `ReactAgentLlmService` 同时承担两条路径：
  - 无工具 `chat`：通过 `SaaModelFactory` 构建无工具 ReactAgent，保留 `CallOptions`；
  - 带工具 `chat`：通过 `SaaReactAgentFactory` 构建 ReactAgent，保留 ToolSet、Hook、context 与 recursionLimit。
- 两条 stream 路径均保留当前非流式回退语义：非空结果整体回调一次。
- 带工具异常统一转换为 `BizException(ErrorCode.LLM_API_ERROR)`。

## 消费方迁移

以下业务组件统一注入 `LlmService` 并调用带工具 `chat`：

- `ChatNode`
- `DiscussNode`
- `WorkNode`
- `ConclusionService`

`SaaReactAgentFactory`、`SystemMessageMergeHook` 及相关测试的旧类型引用已同步迁移。

## Mock 策略

`MockLlmService` 标记为 `@Deprecated`。它继续支持无工具 `chat`/`chatStream`；带工具重载明确抛出 `UnsupportedOperationException`，不伪造 Agentic 工具执行能力。因此 `dingring.llm.mock=true` 仅适用于确定性 LLM 调用，不是完整工作流的离线实现。

## 验证

- `mvn -pl dingRing-app -am test -Dsurefire.failIfNoSpecifiedTests=false`：通过
- `mvn -pl dingRing-infrastructure -am test -Dsurefire.failIfNoSpecifiedTests=false`：通过
- Java 源码中已无 `AgentSpeakerService` / `AgentSpeakerServiceImpl` 引用
- ReactAgent 工厂回归测试通过，确认带工具路径仍保留 Hook、ToolSet 和 recursionLimit 行为
