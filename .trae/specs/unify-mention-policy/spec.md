# 统一 @提及 策略重设计 Spec

## Why
当前"被 @ 即无条件最高优先级"在 CHAT/WORK/DISCUSS 三类意图中各自硬编码生效，存在以下问题：

- **CHAT**：[ChatNode.selectSpeaker](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/ChatNode.java#L155-L189) 硬选 `mentionedAgentIds.get(0)`，完全绕过调度评分；且被 @ 者发言失败/空内容时整条闲聊直接广播 ALL_AGENTS_FAILED，其他成员无法接话。
- **评分**：[SpeakerScheduler](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/SpeakerScheduler.java#L54-L69) 中 `MENTION_SCORE=1000` 短路，回复加分(500)/轮次均衡/随机扰动全部失效，评分机制在提及场景下形同虚设。
- **DISCUSS**：[DiscussNode](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/DiscussNode.java#L136-L142) 将被 @ 者无条件从 `passedAgentIds` 移除，**永不过 PASS、每轮保底发言**，可能垄断讨论。
- 三处提及逻辑分散且互相不一致，难以统一调整与演进。

## 设计决策：为什么不合并且把逻辑统一到"提及策略"

用户提问：*CHAT/WORK/DISCUSS 是否需要合并成一种、统称 WORK？*

**结论：不合并意图，但统一"提及策略"（即本 spec）。**

- 合并的合理性：三者的共同点是"用户请求 → Agent 应答"，且确实存在意图误判问题（如 @阿源 画图被判 CHAT）。
- 不宜合并的理由：
  1. **执行语义不同**：CHAT 是单次应答 + 闲聊缓冲画像；DISCUSS 是多轮话题、轮转均衡、PASS/收束/终止器、完整话题生命周期（前端依赖讨论态状态机）；WORK 是深度 ReAct 工具调用 + Supervisor 编排。
  2. **用户可见产物不同**：话题（topic）是持久化讨论容器，任务（work）是一次性产出；合并将丢失 DISCUSS 的轮次、收束确认等交互语义。
  3. 合并是大型重构，破坏现有状态机、前端与既有测试。
- 因此：保留三类意图，把"@提及 语义"收敛为一套统一规则矩阵（见下），避免"被 @ 就无条件最高优先级"。

## 统一 @提及 策略矩阵

| 意图 | 语义 | 实现载体 |
|---|---|---|
| WORK | **硬指派**：被 @ 者即执行者（不校验能力，用户指定为准） | WorkNode.pickWorkAgent（已实现，无需改动） |
| CHAT | **大权重加分**：+800 参与统一评分，通常优先；发言失败/空内容时按评分顺延其他成员，不垄断、不整条报错 | SpeakerScheduler.MENTION_BOOST + ChatNode 顺延 |
| DISCUSS | **保证一次发言权**：被 @ 者豁免一次 PASS（必有一次发言机会），本轮结束即消费豁免，回归正常轮转 | DiscussNode + 新状态键 mentionHandled |

## What Changes
- [SpeakerScheduler.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/SpeakerScheduler.java)：移除 `MENTION_SCORE=1000` 短路，改为 `MENTION_BOOST=800` 大权重加分，仍走统一评分排序（多提及时按回复/均衡/随机等其余因子决胜，而非盲取首个）。
- [ChatNode.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/ChatNode.java)：删除硬选分支，统一走 `speakerScheduler.rank`；发言失败/空内容时按评分顺延下一个候选（级联），被 @ 者不垄断发言。
- [DiscussNode.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/DiscussNode.java)：仅当 `mentionHandled=false` 时对被 @ 者豁免一次 PASS；本轮结束写 `MENTION_HANDLED=true` 消费豁免，后续轮次回归正常轮转。
- [StateKeys.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-domain/src/main/java/com/dingring/domain/workflow/StateKeys.java)：新增 `MENTION_HANDLED = "mentionHandled"`。
- [DiscussionEngine.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/orchestrator/DiscussionEngine.java)：GroupState 新增 `mentionHandled` 运行时字段；`advanceFlow` 透传（新用户消息含提及时重置 false）；`updateRuntimeState` 回写；`advanceAuto` 透传原值。
- [WorkNode.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/workflow/node/WorkNode.java)：保持硬指派，不改。
- 测试：更新 SpeakerSchedulerTest；新建 ChatNodeTest / DiscussNodeTest；回归现有测试。

## Impact
- Affected specs：群聊应答（CHAT）、讨论推进（DISCUSS）、任务执行（WORK）的提及路由语义。
- Affected code：`dingRing-app` 的 orchestrator（SpeakerScheduler / DiscussionEngine）+ workflow node（ChatNode / DiscussNode）；`dingRing-domain` 的 StateKeys；对应测试。

## ADDED Requirements
### Requirement: CHAT 被 @ 者"大权重加分 + 顺延"
系统 SHALL 对被 @ 者施加 +800 大权重加分参与统一调度，且当被 @ 者发言失败或返回空内容时，按评分顺延下一位候选发言而非整条报错。

#### Scenario: 被 @ 者正常回答
- **WHEN** 用户消息含 @灰原（CHAT 意图）
- **THEN** 灰原以 +800 加分获得最高分并发言

#### Scenario: 被 @ 者无话可说
- **WHEN** 被 @ 者返回空内容/失败
- **THEN** 系统按评分顺延次高者回答，且不广播 ALL_AGENTS_FAILED

### Requirement: DISCUSS 被 @ 者"保证一次发言权"
系统 SHALL 在被 @ 者尚未消费豁免（`mentionHandled=false`）时，将其从 `passedAgentIds` 中豁免一次；本轮结束（无论其 SPOKE 或 PASSED）即消费豁免，后续轮次按正常轮转逻辑处理。

#### Scenario: 讨论中 @ 某成员补充
- **WHEN** 讨论推进中用户 @阿源，且阿源已在本话题 PASS
- **THEN** 阿源本轮被豁免、重新获得一次发言机会

#### Scenario: 豁免已消费
- **WHEN** 阿源已完成其豁免轮（mentionHandled=true）
- **THEN** 后续轮次不再豁免，按正常 PASS/轮转逻辑处理

### Requirement: WORK 被 @ 者硬指派（保持）
系统 SHALL 在被 @ 时，以被 @ 的群内成员为任务执行者（首个命中），无有效提及时回退群首。

#### Scenario: @ 某人执行任务
- **WHEN** 用户 @阿源 并下达 WORK 任务
- **THEN** 阿源为执行者（若 Supervisor 模式则阿源为编排者）

## MODIFIED Requirements
### Requirement: SpeakerScheduler 提及评分（修改）
被 @ 者不再短路得 1000 分，而是得 `MENTION_BOOST=800` 大权重加分并参与统一评分排序；多提及场景按其余评分因子（引用/轮次均衡/随机）决胜。

## REMOVED Requirements
### Requirement: DISCUSS 被 @ 者永不过 PASS
**Reason**：被 @ 者每轮保底可发言，可能垄断讨论，违背"提及仅是一次点名"的语义。
**Migration**：改为"保证一次发言权"（见 ADDED Requirements 第二条）。
