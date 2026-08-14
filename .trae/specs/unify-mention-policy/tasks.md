# Tasks

## Task 1: SpeakerScheduler 提及评分重构（前置，无依赖）
- [x] 移除 `MENTION_SCORE=1000` 短路逻辑，改为 `MENTION_BOOST=800` 大权重加分（`MENTION_SCORE + BASE_SCORE` 语义调整，仍参与统一排序）
- [x] `reasonOf` 保留 `MENTIONED` 标识
- [x] 更新 SpeakerSchedulerTest：单提及得 800 分且为最高；双提及按其余因子决胜；非提及成员不受影响

## Task 2: ChatNode 选人重构（依赖 Task 1）
- [x] 删除 `selectSpeaker` 硬选分支，统一调用 `speakerScheduler.rank(members, ctx)` 取首位
- [x] `speakOnce` 增加级联：首位发言失败/空内容时按评分顺延下一位候选，全部失败才广播 ALL_AGENTS_FAILED
- [x] 新建 ChatNodeTest：@提及者优先发言；被 @ 者空内容时顺延次高者且不报错；无提及时评分最高者发言

## Task 3: DISCUSS 被 @ 者"保证一次发言权"（依赖 Task 1）
- [x] StateKeys 新增 `MENTION_HANDLED = "mentionHandled"`
- [x] DiscussNode：仅当 `mentionId != null && !mentionHandled` 时从 `passedAgentIds` 豁免一次；本轮结束写 `MENTION_HANDLED=true`（SPOKE/PASSED 均消费）
- [x] DiscussionEngine：GroupState 新增 `mentionHandled` 字段；`advanceFlow` 透传（新用户消息含提及时重置 false，无提及透传原值）；`advanceAuto` 透传原值；`updateRuntimeState` 回写
- [x] 新建 DiscussNodeTest：豁免一次（第一轮被 @ 者可发言）；豁免消费后第二轮不再豁免；无提及行为不变

## Task 4: 回归验证
- [x] 运行 `mvn -q test -pl dingRing-app -am` 全量单测通过
- [x] 确认 WorkNodeTest（硬指派）不受影响

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
