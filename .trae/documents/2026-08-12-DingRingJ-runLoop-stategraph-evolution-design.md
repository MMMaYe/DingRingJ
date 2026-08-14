# DingRingJ runLoop 图内化改造技术方案（B1 方向）

> 日期：2026-08-12
> 范围：`DiscussionEngine.runLoop` + `SaaWorkflow` 的 StateGraph 改造
> 目标：简化 runLoop（退化为信号分拣器），借用 SAA 图能力实现状态持久化与 tool 异常断点恢复

---

## 1. 背景与目标

### 1.1 现状问题

- `DiscussionEngine.runLoop`（[DiscussionEngine.java L158-L223](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java#L158-L223)）承担三类职责：每群串行化、跨时间状态机、事件驱动等待。其中"等待"用 `queue.take()`/`queue.poll(timeout)` 阻塞实现，阻塞逻辑（WAIT 无限期、CONCLUDE_PROPOSED 5 分钟）与调度逻辑耦合。
- 运行时状态（`discussMode/passedAgentIds/lowDiscussStreak/chatBuffer/divergeRounds/mentionHandled`）由引擎 `GroupState` 持有，每次 `advance` 经 `advanceFlow`/`advanceAuto` 透传进图、再从 `updateRuntimeState` 收回——**状态绕行**。
- StateGraph 的 checkpoint 被空 `SaverConfig` 禁用（[SaaWorkflow.java L203-L212](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/SaaWorkflow.java#L203-L212)），图执行是"独立无状态"的。每次 `advance` 从 START 全量跑到 END。
- **tool 异常无恢复能力**：图执行中途异常（如 ReactAgent 内工具调用失败导致进程/执行中断）后，无法从断点续上，只能整体重来。

### 1.2 目标

1. **runLoop 退化**：删除状态绕行（`advanceFlow` 透传、`updateRuntimeState` 回填），WAIT/CONCLUDE_PROPOSED 的阻塞等待改为图中断 + 恢复。
2. **状态图内化**：全部运行时状态并入图 state，checkpoint 按 `threadId="group-{groupId}"` 持久化，成为会话唯一真相源。
3. **tool 异常可续**：借助 checkpoint + `updateState` + resume 机制，跨调用/崩溃后可恢复执行。
4. **保留**：每群串行语义、FLOW_EVENT 前端流程条、TopicStatus 广播、DIVERGE 自主推进节奏。

### 1.3 技术前提（SAA 1.1.2.3 已验证）

| 能力 | 依据 |
|---|---|
| 中断钩子 | [InterruptableAction.java L25-L35](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/action/InterruptableAction.java#L25-L35)：`interrupt → apply → interruptAfter` 三点式 |
| 中断节点声明 | [CompileConfig.java L222-L237](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/CompileConfig.java#L222-L237)：`interruptsBefore/interruptsAfter` |
| 中断停止执行 | [NodeExecutor.java L170-L190](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/executor/NodeExecutor.java#L170-L190)：`interruptAfter` 返回 metadata → 合并 state → 建 checkpoint → 返回 `GraphResponse.done` |
| resume 恢复 | [CompiledGraph.updateState L309-L328](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/CompiledGraph.java#L309-L328)：合并 values + 指定 asNode；[MainGraphExecutor L86-L94](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/executor/MainGraphExecutor.java#L86-L94)：`getResumeFromAndReset` 从断点继续 |
| 初始态合并 | [CompiledGraph.getInitialState L461-L467](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/CompiledGraph.java#L461-L467)：checkpoint 残留 + 新 inputs 合并 |
| 节点挂中断 | [AsyncNodeActionWithConfig L55-L87](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/action/AsyncNodeActionWithConfig.java#L55-L87)：`InterruptableAsyncNodeActionWrapper` 自动包装 |
| 会话恢复标记 | [RunnableConfig L397-L410](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/RunnableConfig.java#L397-L410)：`addHumanFeedback`/`resume()` |

---

## 2. 总体架构

```
用户消息 → onUserSignal ─┬─ 有中断 checkpoint? ─> updateState(config,{INPUT},asNode) → stream resume
                         └─ 无 ──────────────> stream(inputs) 新启动一轮
                                                        │
   图执行 ── MODE_WAIT ──> interruptAfter 中断 ──> checkpoint 存档 ──> 返回 metadata
                                                        │
   用户回答 ───────────────────────────── resume 注入 INPUT ──> discuss 继续（不再重分类）
```

**核心变化对照**

| 维度 | 现状（1.1.2.3 禁 checkpoint） | 目标（B1） |
|---|---|---|
| 运行时状态 | `GroupState` 内存持有，透传进出图 | 并入图 state，checkpoint 按 `threadId="group-{groupId}"` 保持 |
| WAIT 等待 | `queue.take()` 阻塞 | 图中断 + resume |
| CONCLUDE_PROPOSED | `queue.poll(5min)` 阻塞 | 图中断 + 引擎侧定时兜底 |
| 状态回填 | `updateRuntimeState` 6 字段 | 删除 |
| 透传样板 | `advanceFlow`/`advanceAuto` | 删除 |
| 崩溃恢复 | 无 | checkpoint + 固定 threadId 恢复 |

---

## 3. 图结构改造（SaaWorkflow）

### 3.1 节点与边

保留现有 9 节点，改 **discuss 条件边**：WAIT/CONCLUDE_PROPOSED 不再去 END，而是**回绕到 discuss 自身**（自环），并声明中断点：

```
START → preprocess → intent-classify ─(条件边)─┬─ CHAT → chat ─(needProfile?)─→ profile-extract → END
                                              ├─ WORK → work → sediment → END
                                              ├─ CONCLUDE → conclude → sediment → END
                                              └─ DISCUSS → ensure-topic ─(成功?)─→ discuss ─┐
                                                                    └─ 失败 → chat ────────┤
    discuss ─(条件边: discussMode)─┬─ CONVERGE/DIVERGE → END                    │
                                  ├─ WAIT ────────────→ discuss ←───────────────┘ 回绕 + 中断点
                                  ├─ CONCLUDE_PROPOSED → discuss ←──────────────┘ 回绕 + 中断点
                                  └─ CONCLUDE → conclude → sediment → END
```

### 3.2 中断声明（编译期）

```java
compiledGraph = graph.compile(CompileConfig.builder()
        .saverConfig(new SaverConfig().register(MemorySaver.builder().build()))
        .interruptsAfter("discuss")   // 声明中断点：discuss 执行后允许中断
        .build());
```

### 3.3 discuss 节点实现 InterruptableAction

DiscussNode 由 `AsyncNodeAction` 改为 `AsyncNodeActionWithConfig` + 实现 `InterruptableAction`：

```java
@Override
public Optional<InterruptionMetadata> interruptAfter(String nodeId, OverAllState state,
        Map<String, Object> actionResult, RunnableConfig config) {
    String mode = state.value(StateKeys.DISCUSS_MODE, "");
    if (StateKeys.MODE_WAIT.equals(mode) || StateKeys.MODE_CONCLUDE_PROPOSED.equals(mode)) {
        return Optional.of(InterruptionMetadata.builder(nodeId, state)
                .metadata(Map.of("awaiting", mode))   // 借用 metadata 存自定义标记（字段是工具审批专用，我们只用 map）
                .build());
    }
    return Optional.empty();
}
```

> 注意：[InterruptionMetadata.java L38-L55](file:///tmp/saa1123/src/com/alibaba/cloud/ai/graph/action/InterruptionMetadata.java#L38-L55) 的 `ToolFeedback`/`toolsAutomaticallyApproved` 字段面向工具审批，我们**借用其 metadata map** 存自定义标记，不依赖工具字段。resume 用 `updateState(asNode)` 指定注入点。

### 3.4 resume 流程（断点继续讨论）

```java
// 中断后：advance 返回的 NodeOutput 末元素是 InterruptionMetadata（node()=="discuss"）
// 引擎检测到中断 → 记录 threadId，退出本轮执行，等待外部消息

// 用户回答到达：
RunnableConfig resumeConfig = compiledGraph.updateState(
        RunnableConfig.builder().threadId("group-" + groupId).build(),
        Map.of(StateKeys.INPUT, signal.content(),
               StateKeys.MENTIONED_AGENT_IDS, signal.mentionedAgentIds(),
               StateKeys.REPLIED_TO_AGENT_ID, signal.repliedToAgentId()),
        "discuss");   // asNode：从 discuss 继续
// 再 stream 恢复执行（getInitialState 自动合并 checkpoint 残留 + 新 inputs）
List<NodeOutput> outputs = compiledGraph.stream(Map.of(), resumeConfig)
        .doOnNext(no -> broadcastFlowEvent(groupId, no, lastNodeTs))
        .collectList().block();
```

> 语义确认：WAIT 后用户回答**直接注入 discuss 继续**，不重新意图分类（与用户决策一致）。讨论上下文（话题、消息历史、计数器）全在 checkpoint 里。

---

## 4. checkpoint 配置策略

| 项 | 配置 | 说明 |
|---|---|---|
| Saver | `MemorySaver` 起步 | 单实例可恢复；跨进程换 `PostgresSaver`/`RedisSaver` |
| threadId | 固定 `"group-{groupId}"` | 会话级——崩溃后同会话恢复的前提 |
| KeyStrategy | 除 `MESSAGES` 外全部 `ReplaceStrategy`；`MESSAGES` 用 `AppendStrategy(false)` | resume 时新 INPUT 幂等覆盖残留，杜绝误路由（bug2 的根因是固定 threadId+残留合并，此处合并是特性） |
| releaseThread | **不启用**（默认 false） | 启用会在正常完成后清空 checkpoint，恢复失效 |
| 异常路径 | tool 节点 try-catch 把错误作为 observation 注入 resume | 避免 error 分支残留半截状态 |
| checkpoint 生命周期 | 会话结束（CONCLUDE 落到 `concluded=true`）后显式 `saver.release(config)` | 防内存泄漏 |

---

## 5. runLoop 退化形态

### 5.1 结构

```java
private void runLoop(Long groupId, GroupState state) {
    while (true) {
        Optional<Topic> active = topicRepository.findActiveByGroupId(groupId).filter(Topic::isInProgress);
        boolean hasInterruptedRun = hasPendingInterrupt(groupId);   // 有中断 checkpoint？

        // 1) 有中断：等用户消息（不阻塞图，只等信号）
        if (hasInterruptedRun) {
            UserSignal signal = state.queue.poll(awaitModeTimeout(state), MILLISECONDS);
            if (signal == null) continue;          // 超时兜底（CONCLUDE_PROPOSED 5 分钟）见 §6
            DiscussionFlowResult result = resumeFlow(groupId, signal, state);  // updateState + stream
            updateAndBroadcast(groupId, state, result);
            if (result.concluded()) return;
            continue;
        }

        // 2) 无中断：按 mode 决定 poll 超时
        long timeout = paceForMode(state, active.isPresent());
        UserSignal signal = active.isPresent()
                ? state.queue.poll(timeout, MILLISECONDS)
                : state.queue.poll();

        if (signal != null) {
            DiscussionFlowResult result = advanceFlow(groupId, signal, state);
            updateAndBroadcast(groupId, state, result);
            if (result.concluded() || hasPendingInterrupt(groupId)) continue;  // 中断了 → 回顶部等消息
            continue;
        }
        // 无信号 + DIVERGE → 自主推进；否则退出等唤醒
        if (active.isPresent() && StateKeys.MODE_DIVERGE.equals(state.discussMode)) {
            DiscussionFlowResult result = advanceAuto(groupId, state);
            updateAndBroadcast(groupId, state, result);
            continue;
        }
        return;
    }
}
```

### 5.2 删除项

- `updateRuntimeState`（状态已入图，advance 返回的 state 即最新）；
- `advanceFlow`/`advanceAuto` 里的运行时状态透传（`PASSED_AGENT_IDS/LOW_DISCUSS_STREAK/CHAT_BUFFER/DIVERGE_ROUNDS/MENTION_HANDLED`）——改从 checkpoint 读；
- `handleConcludeProposed` 的阻塞段（广播保留，等待改为中断 + 定时兜底）；
- `GroupState` 中 `discussMode` 之外的可变字段（状态入图后仅保留 `queue/running` 串行性基础）。

### 5.3 保留项

- `onUserSignal`/`wake`/`execute`/`safeRun`（每群串行执行器）；
- `pushTopicStatus`（TOPIC_STATUS 广播）；
- `paceForMode`（DIVERGE 节奏）；
- `runConcludeFlow`（外部触发收束）。

---

## 6. CONCLUDE_PROPOSED 超时兜底

图中断后引擎不阻塞，5 分钟确认超时用**定时任务**实现：

```java
// 中断返回 CONCLUDE_PROPOSED 时注册：
timeoutRegistry.schedule(groupId, topicId, rules.concludeConfirmTimeoutMs(), () -> {
    // 到点检查：该 group 仍处于 CONCLUDE_PROPOSED 且用户未确认 → 自动收束
    if (!hasPendingInterrupt(groupId) || !isConcludeProposed(groupId)) return;
    runConcludeFlow(topicId, groupId, "TIMEOUT", concluderAgentId);
});
```

- 用户确认消息到达 → 走 resume → 完成时取消定时器；
- 兜底语义与现状一致（5 分钟超时自动收束）。

---

## 7. tool 异常续上机制

### 7.1 单次执行内异常（ReAct 自愈，无需 checkpoint）

工具失败作为 observation 喂回 LLM 重试/换工具——现有 ReactAgent 子图已具备，不改。

### 7.2 跨调用/进程中断恢复（checkpoint 恢复）

```
图执行到 tool 节点 → checkpoint 存档（nodeId+state+nextNodeId）
   ↓ 进程崩溃 / 异常中断
恢复：同 threadId "group-{groupId}" 再次 stream → getInitialState 合并残留
  → 从断点继续；或用 updateState(config, {工具结果}, "toolNode") 注入修复值续跑
```

### 7.3 会话结束清理

`concluded=true` 后显式 `saver.release(config)`，释放该群 checkpoint 与线程，防泄漏。

---

## 8. 迁移步骤（渐进，每步可验证）

| 步骤 | 内容 | 验证 |
|---|---|---|
| **P0** | 编译图：注册 `MemorySaver` + `interruptsAfter("discuss")`；固定 `threadId="group-{groupId}"`；先不改 runLoop | 现有 361 测试全绿；确认 checkpoint 启用后 `advance` 语义不变（初始无残留） |
| **P1** | 运行时状态并入图 state：删除 `advanceFlow`/`advanceAuto` 透传、`updateRuntimeState` 回填；`GroupState` 仅留串行基础 | 群聊各模式（闲聊/DISCUSS/WORK/CONCLUDE）回归 |
| **P2** | DiscussNode 实现 `InterruptableAction`，WAIT 图中断；runLoop WAIT 分支改 resume | WAIT → 用户回答 → 续上，消息不丢、不重分类 |
| **P3** | CONCLUDE_PROPOSED 中断 + 定时兜底；删除阻塞段 | 5 分钟超时自动收束；用户确认走 resume |
| **P4** | 会话结束 `saver.release`；异常路径 try-catch 注入 | 长时间运行无泄漏；tool 异常可续 |
| **P5** | （可选）MemorySaver → PostgresSaver/RedisSaver | 跨实例/重启恢复 |

---

## 9. 测试策略

- **新增**：`SaaWorkflowResumeTest`——模拟 WAIT 中断 → `updateState` 注入 → resume 断言从 discuss 继续（不经过 intent-classify）；
- **新增**：`DiscussionEngineInterruptTest`——用户消息触发 WAIT → 引擎进入等待 → 回答 → 结果正确；
- **新增**：`ConcludeProposedTimeoutTest`——缩短 confirm 超时，断言定时自动收束；
- **新增**：`CheckpointRecoveryTest`——执行中途异常后同 threadId 重跑，断言从断点恢复；
- **回归**：全部既有测试（尤其 `SaaStateResidueReproTest`——本方案要求该"bug"成为特性，测试语义需更新为"残留合并符合预期"）；
- **性能**：DIVERGE 高频推进下 checkpoint 写入无显著开销（MemorySaver 内存写）。

---

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| **bug2 复发形态**（残留合并误路由） | 全部 key `ReplaceStrategy` 幂等覆盖；`MESSAGES` 独立 Append；P1 先行验证合并语义 |
| **InterruptionMetadata 是工具审批专用** | 只用其 metadata map，不碰工具字段；resume 用 `updateState(asNode)` 而非 humanFeedback |
| **updateState 后 resume 语义边界**（1.1.2.3 的 `getResumeFromAndReset` 以 interruptBeforeEdge 为前提） | P2 用最小场景先验证 WAIT resume；若边决策不符预期，改用 `RunnableConfig.nextNode("discuss")` + `resume()` 显式指定恢复节点 |
| **checkpoint 内存泄漏**（MemorySaver 每群一条链） | P4 会话结束显式 `release`；上限用 `checkPointId` 裁剪历史 |
| **DIVERGE 高频自主推进**累积 checkpoint | 每轮推进后视需要裁剪；或该轮不落 checkpoint（临时禁用） |
| **状态入图后 GroupState 语义变化** | `mentionHandled`/`divergeRounds` 等改为图 state 读写，引擎只做调度，职责更清 |

---

## 11. 涉及文件清单

| 文件 | 改动 |
|---|---|
| [SaaWorkflow.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/workflow/SaaWorkflow.java) | 注册 Saver + `interruptsAfter`；discuss 条件边回绕；advance 支持 resume 分支 |
| [DiscussNode.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/DiscussNode.java) | 实现 `InterruptableAction.interruptAfter` |
| [DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java) | runLoop 退化、resume 分支、删除状态绕行 |
| `DiscussionFlowService`/`DiscussionFlowResult` | advance 签名扩展（threadId/config 透传）、中断状态返回 |
| 新增 `ConcludeTimeoutRegistry` | CONCLUDE_PROPOSED 定时兜底 |
| 新增测试 | §9 所列 4 个测试类 + 更新 `SaaStateResidueReproTest` |
