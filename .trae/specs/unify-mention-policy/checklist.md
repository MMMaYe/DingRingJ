# Checklist

- [x] SpeakerScheduler：移除提及短路，被 @ 者得 800 大权重加分并参与统一排序，多提及按其余因子决胜
- [x] ChatNode：删除硬选分支，统一走评分；被 @ 者发言失败/空内容时按评分顺延下一位候选，不再整条 ALL_AGENTS_FAILED
- [x] DiscussNode：被 @ 者仅在 `mentionHandled=false` 时豁免一次 PASS，本轮结束消费豁免，后续回归正常轮转
- [x] DiscussionEngine：`mentionHandled` 运行时字段随轮次透传/回写，新提及重置为 false
- [x] WorkNode 硬指派行为保持（被 @ 者即执行者/编排者）
- [x] SpeakerSchedulerTest / ChatNodeTest / DiscussNodeTest 覆盖新语义，全量 `mvn test` 通过（157 个测试，0 失败）
