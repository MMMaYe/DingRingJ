# 工作流 Node 类 Bean 名改为类名首字母小写（Spring 默认）

## Summary

将 `dingRing-app/.../workflow/node/` 下 9 个 Node 类的 `@Component("xxxHandler")` 显式 Bean 名改为 `@Component`（让其采用 Spring 默认 Bean 名——类简单名首字母小写），并同步修改 `SaaWorkflow` 中通过 `nodeRegistry.getHandler("xxxHandler")` 按名查找的 9 处字符串字面量。

## Current State Analysis（基于 Phase 1 探查）

### Node 类清单（包 `com.dingring.app.workflow.node`）

| 类名                   | 当前注解                                  | 当前 Bean 名             | 目标 Bean 名（Spring 默认：类名首字母小写） |
| -------------------- | ------------------------------------- | --------------------- | ---------------------------- |
| `PreprocessNode`     | `@Component("preprocessHandler")`     | preprocessHandler     | `preprocessNode`             |
| `IntentClassifyNode` | `@Component("intentClassifyHandler")` | intentClassifyHandler | `intentClassifyNode`         |
| `ChatNode`           | `@Component("chatHandler")`           | chatHandler           | `chatNode`                   |
| `EnsureTopicNode`    | `@Component("ensureTopicHandler")`    | ensureTopicHandler    | `ensureTopicNode`            |
| `DiscussNode`        | `@Component("discussHandler")`        | discussHandler        | `discussNode`                |
| `ConcludeNode`       | `@Component("concludeHandler")`       | concludeHandler       | `concludeNode`               |
| `SedimentNode`       | `@Component("sedimentHandler")`       | sedimentHandler       | `sedimentNode`               |
| `ProfileExtractNode` | `@Component("profileExtractHandler")` | profileExtractHandler | `profileExtractNode`         |
| `WorkNode`           | `@Component("workHandler")`           | workHandler           | `workNode`                   |

所有节点 `implements com.alibaba.cloud.ai.graph.action.NodeAction`，统一叠加 `@Slf4j` + `@RequiredArgsConstructor`。

### 注册与解析机制（无需改动）

* 注册中枢：[`NodeHandlerRegistry.java`](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/NodeHandlerRegistry.java) — `@Component`，构造器入参 `Map<String, NodeAction> handlers`，Spring 自动聚合所有 NodeAction Bean，**key = Bean 名**。`getHandler(String name)` 按 Bean 名查找，找不到则抛 `IllegalStateException`。此处无需改动。

* `WorkflowConfig.java` 与节点注册无关，不涉及。

### 唯一引用 Bean 名的运行代码

全仓仅 [`SaaWorkflow.java`](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/SaaWorkflow.java) 在 `@PostConstruct init()`（第 127–144 行）以字符串字面量调用 `nodeRegistry.getHandler("xxxHandler")` 共 9 处。图节点 ID（`preprocess` / `intent-classify` / 等）与这些 Bean 名是两套独立字符串，**只有传入 getHandler 的字符串需要改**，`graph.addNode(...)` 第一个参数（图节点 ID）不动。

未发现 `@Qualifier` / `getBean` / 显式名-类映射表等其他按名引用方式。

## Proposed Changes

### 1. 修改 9 个 Node 类的 `@Component` 注解

把 `@Component("xxxHandler")` 中的显式名去掉，改为 `@Component`。其余注解（`@Slf4j`、`@RequiredArgsConstructor`）和类体保持不变。逐文件操作：

* `workflow/node/PreprocessNode.java#34`: `@Component("preprocessHandler")` → `@Component`

* `workflow/node/IntentClassifyNode.java#37`: `@Component("intentClassifyHandler")` → `@Component`

* `workflow/node/ChatNode.java#55`: `@Component("chatHandler")` → `@Component`

* `workflow/node/EnsureTopicNode.java#49`: `@Component("ensureTopicHandler")` → `@Component`

* `workflow/node/DiscussNode.java#61`: `@Component("discussHandler")` → `@Component`

* `workflow/node/ConcludeNode.java#48`: `@Component("concludeHandler")` → `@Component`

* `workflow/node/SedimentNode.java#27`: `@Component("sedimentHandler")` → `@Component`

* `workflow/node/ProfileExtractNode.java#38`: `@Component("profileExtractHandler")` → `@Component`

* `workflow/node/WorkNode.java#51`: `@Component("workHandler")` → `@Component`

**Why**：去掉显式名后 Spring 默认 Bean 名即类简单名首字母小写，正是目标命名。

### 2. 修改 `SaaWorkflow.init()` 中 9 处 `getHandler` 字面量

[`SaaWorkflow.java#L127-L144`](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/SaaWorkflow.java#L127-L144)：把传给 `nodeRegistry.getHandler(...)` 的字符串按上表从 `xxxHandler` 改为 `xxxNode`。只改 `getHandler(...)` 的入参，不动 `graph.addNode("图节点ID", ...)` 第一个参数。

完整对应（9 行）：

```java
graph.addNode("preprocess",      AsyncNodeAction.node_async(nodeRegistry.getHandler("preprocessNode")));
graph.addNode("intent-classify", AsyncNodeAction.node_async(nodeRegistry.getHandler("intentClassifyNode")));
graph.addNode("chat",            AsyncNodeAction.node_async(nodeRegistry.getHandler("chatNode")));
graph.addNode("ensure-topic",    AsyncNodeAction.node_async(nodeRegistry.getHandler("ensureTopicNode")));
graph.addNode("discuss",         AsyncNodeAction.node_async(nodeRegistry.getHandler("discussNode")));
graph.addNode("conclude",         AsyncNodeAction.node_async(nodeRegistry.getHandler("concludeNode")));
graph.addNode("sediment",         AsyncNodeAction.node_async(nodeRegistry.getHandler("sedimentNode")));
graph.addNode("profile-extract", AsyncNodeAction.node_async(nodeRegistry.getHandler("profileExtractNode")));
graph.addNode("work",            AsyncNodeAction.node_async(nodeRegistry.getHandler("workNode")));
```

### 3. `NodeHandlerRegistry` 无需改动

注册机制依赖 Spring 聚合 `Map<String, NodeAction>`，Bean 名变化会自动反映到 Map 的 key，无需修改代码。其日志（打印 `handlers.keySet()`）会自然显示新的 Bean 名集合，无需改注释。

## Assumptions & Decisions

* **不创建新文件、不删除文件**：仅修改现有 9 个 Node 类 + SaaWorkflow 共 10 个文件。

* **不重命名类**：类名不变，仅改 Bean 名。`PreprocessNode` 等类名已含 `Node` 后缀，与新 Bean 名 `preprocessNode` 语义一致，无需调整。

* **不更新 NodeHandlerRegistry 的注释/javadoc**：其中"按 Bean 名查找 NodeAction 实现"仍准确，无 Bean 名硬编码。

* **不清理 SaaWorkflow 中图节点 ID 与 Bean 名的双命名**：本次仅对齐 Bean 名到 Spring 默认，不改图节点 ID（`preprocess` / `intent-classify` 等），避免扩大改动范围。

* **不更新历史文档**：`.trae/documents/` 下的旧规划/学习文档描述非运行代码，不在本次范围。

* Spring 默认 Bean 名规则：类简单名首字母小写，对首字母大写后续接大写的情况（如 `IntentClassifyNode`）也仅首字母小写为 `intentClassifyNode`，与原 `intentClassifyHandler` 仅后缀不同，统一替换为 `Node` 后缀。

## Verification steps

1. **静态检查**：对 10 个文件执行 `mvn -q -pl dingRing-app,dingRing-infrastructure compile`（或 IDE 编译），确认无编译错误。
2. **启动校验**：启动应用，确认日志 `NodeHandlerRegistry 初始化完成，已注册节点: {...}` 中 key 集合为 9 个 `xxxNode` 而非 `xxxHandler`。
3. **功能验证**：触发一次群聊流程（`SaaWorkflow.advance`），确认不再抛 `IllegalStateException: 未找到节点处理器: xxxHandler`，且 9 个节点（preprocess→intent-classify→chat/ensure-topic/discuss/work/conclude/sediment/profile-extract）均按原图边正常路由。
4. **回归**：确认收束流程（`runConcludeFlow`）和工作流程仍正常，无新增 NPE。

