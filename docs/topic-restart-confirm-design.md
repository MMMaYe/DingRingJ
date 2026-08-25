# 话题重开确认改造方案（Topic Restart Confirmation）

> 状态：设计稿，待评审
> 关联代码：`EnsureTopicNode` / `DiscussionEngine` / `SaaWorkflow` / `Topic` / `WsMessageDispatcher`
> 前序分析：标题与历史话题重复时，当前系统静默加时间后缀建新题（精确撞车）或仅注入参考提示（语义相似），无任何用户交互。本方案补齐"询问用户是否重开历史话题"的产品能力。

## 1. 背景与目标

### 1.1 现状

| 场景 | 现有行为 | 问题 |
|---|---|---|
| 拟定标题与历史话题**精确重复**（撞 (group_id, title) 唯一约束） | 加 `·MMdd-HHmm` 时间后缀再建新题（`EnsureTopicNode` DuplicateKeyException 分支） | 用户无感知，话题名带噪音后缀；历史沉淀被"平行重启" |
| 拟定标题与历史话题**语义相似**（向量检索命中 ≥0.75） | 建新题，历史结论/画像作为参考注入（restartHint + userHistoryHint） | 单向参考，历史讨论上下文（消息归属）不续接 |

### 1.2 目标行为

用户消息触发建题、且拟定标题命中历史话题时：

```
检测到重复 -> 广播重开提议（候选话题卡片：标题/结论摘要/消息数/关闭时间 + [重开] [新建] 按钮）
  ├─ 用户点[重开]  -> CLOSED->IN_PROGRESS，近期无话题消息回填归位，原始消息重新驱动讨论
  ├─ 用户点[新建]  -> 建新题（沿用现有撞车兜底逻辑）
  ├─ 用户直接打字  -> 视为隐式放弃：提议撤销，新消息走正常流程
  └─ 超时(默认5分钟) -> 等同[新建]（触发消息不能被吞，必须得到应答）
```

### 1.3 非目标

- 不做"多候选选择"（v1 只提议相似度最高的 1 个）
- 不做跨群话题重开（候选限定本群 CLOSED 话题）
- 不做 ARCHIVED 话题重开（只读语义保持）

## 2. 关键设计决策

| # | 决策 | 理由 |
|---|---|---|
| D1 | **确认走专用 WS 指令 `RESTART_TOPIC`，编译为带类型的 UserSignal 入队**，不走 CONCLUDE_PROPOSED 的"普通消息+意图分类判语义"模式 | 重开是二元决策，赌意图分类不可靠；且提议期间**无活跃话题**，意图分类会对确认消息再产出拟定标题，与确认语义冲突。入队而非直接调用，保持群串行语义（runLoop 阻塞在 poll 时正好接住） |
| D2 | **超时默认 = 新建**，不是静默放弃 | 触发提议的那条用户消息尚未被应答；超时吞消息是行为倒退。默认走现有行为（建新题）保证兜底 |
| D3 | **触发条件收敛**：精确撞标题 OR 向量相似度 ≥ 0.9（高于现有 0.75 参考阈值），且候选必须本群 + CLOSED，只取 Top-1 | 0.75 阈值下的"相似"大量属于"主题相关而非同一话题"，按此弹确认会打扰；精确重复则必然弹 |
| D4 | **重开抑制是一次性的**：仅"解决提议的那一次 advance"（超时/拒绝/隐式放弃）携带 `skipRestartCheck`，之后恢复检测 | 防隐式放弃循环（用户不点按钮直接打字→放弃→该消息又触发同一提议）。之后用户再聊出同标题会再次被问，属可接受打扰，后续可加抑制计数 |
| D5 | **旧结论版本化**：reopen 时把 conclusion/closedAt/总结人 stash 进 `feature.historyConclusions[]`，当前字段置空；再次收束正常覆写 | 重开话题二次收束会覆盖 conclusion，不版本化则第一轮沉淀丢失；feature JSON 已有先例（concludedByAgentId） |
| D6 | **回填复用 backfill 机制，边界改为候选话题的 closedAt**（现状是群内最后关闭话题的 closedAt） | 重开的可能是旧话题，回填边界必须跟着候选走；消息筛选仍是"无 topic_id 的近期消息"，不会偷走已归属后续话题的消息 |
| D7 | **检测/回填/提示格式化从 EnsureTopicNode 提取为 `TopicBacktrackService`** | reopen 路径（引擎侧）与建题路径（节点侧）共用回填与回溯；同时顺手修一个潜在问题：语义召回候选应过滤只留 CLOSED（现状未过滤，活跃话题也可能被当"历史"注入） |

## 3. 技术方案

### 3.1 总体链路

```
用户消息 X ──> ChatOrchestrator.onUserMessage ──> 入库广播 + UserSignal(MESSAGE) 入队
                                                          │
                                              runLoop: advanceFlow(X)
                                                          │
                                IntentClassifyNode: DISCUSS(拟定标题T)
                                                          │
                       EnsureTopicNode: 达建题门槛 ── 是 ──> RestartCandidate 检测(D7)
                                                          │
                                          命中(D3) ────────────────┐ 未命中 -> 现有建题流程
                                                          │        │
                                        写 discussMode=RESTART_PROPOSED │
                                        + restartCandidateTopicId     │
                                                          │        │
                       SaaWorkflow ensure-topic 条件边: RESTART_PROPOSED -> END
                                                          │
                    runLoop 信号分支: mode=RESTART_PROPOSED -> handleRestartProposed
                                       │ 广播 TOPIC_RESTART_PROPOSED + TOPIC_STATUS 横幅
                                       │ poll(restartConfirmTimeoutMs) ── 阻塞等决策
        ┌───────────────┬───────────────┴───────────────┬──────────────────┐
   RESTART_ACCEPT   RESTART_REJECT     普通消息(隐式放弃)        超时
        │                │                   │                   │
   reopen(D5)+回填   advanceResume     advanceResume(该消息)   advanceResume(原始X)
   + 广播TOPIC_      (原始X, skip)     (skip=true)           (原始X, skip)
     RESTARTED           │                   │                   │
   + 原始X重新入队        └───────────────────┴───────────────────┘
                                        │
                          正常图执行：ensure-topic(skip) 建新题 -> discuss
```

`advanceResume(groupId, signal, state)`：与 `advanceFlow` 同构，额外携带 `SKIP_RESTART_CHECK=true` 入参（即 D4 的一次性抑制）。

### 3.2 领域层（dingRing-domain）

**Topic.java** —— 新增重开转换与结论版本化：

```java
/** 重开：CLOSED -> IN_PROGRESS；旧结论版本化保存至 feature.historyConclusions */
public void reopen() {
    transitTo(TopicStatus.IN_PROGRESS);
    if (conclusion != null && !conclusion.isBlank()) {
        if (feature == null) feature = new HashMap<>();
        List<Map<String, Object>> history = (List<Map<String, Object>>) feature
                .computeIfAbsent(HISTORY_CONCLUSIONS_KEY, k -> new ArrayList<>());
        Map<String, Object> entry = new HashMap<>();
        entry.put("conclusion", conclusion);
        entry.put("closedAt", closedAt == null ? null : closedAt.toString());
        entry.put("concludedByAgentId", concludedByAgentId().orElse(null));
        history.add(entry);          // 追加，按次序累积
        conclusion = null;
        closedAt = null;
    }
}
private static final String HISTORY_CONCLUSIONS_KEY = "historyConclusions";

/** 历史结论（重开话题的既往沉淀，UI/回溯用） */
@SuppressWarnings("unchecked")
public List<Map<String, Object>> historyConclusions() { ... }
```

**TopicStatus.java** —— 状态机加一条边：

```
IN_PROGRESS --触发结束--> CONCLUDING --成功--> CLOSED --归档--> ARCHIVED
      ^                        |                  |
      +------结论失败回退-------+                  +--重开(本方案)--> IN_PROGRESS
```

```java
case CLOSED -> target == ARCHIVED || target == IN_PROGRESS;
```

**TopicRepository** —— 新增：

```java
/** 群内指定标题的已关闭话题（重开候选精确检测用，唯一约束保证至多一条） */
Optional<Topic> findClosedByGroupIdAndTitle(Long groupId, String title);
```

（infrastructure 层补对应 mapper SQL。）

### 3.3 常量与规则（dingRing-common / domain）

**StateKeys** 追加：

```java
public static final String MODE_RESTART_PROPOSED = "RESTART_PROPOSED"; // 重开提议：等用户决策
public static final String RESTART_CANDIDATE_TOPIC_ID = "restartCandidateTopicId"; // 重开候选话题
public static final String SKIP_RESTART_CHECK = "skipRestartCheck";   // 一次性重开检测抑制
```

**WsConstants** 追加：

```java
/* Client -> Server */
public static final String RESTART_TOPIC = "RESTART_TOPIC";       // {topicId, accept}
/* Server -> Client */
public static final String TOPIC_RESTART_PROPOSED = "TOPIC_RESTART_PROPOSED"; // 重开提议卡片
public static final String TOPIC_RESTARTED = "TOPIC_RESTARTED";               // 重开成功
```

**DiscussionRules** 追加两个字段（同步更新 start 模块的装配与 `application.yml`）：

```java
long restartConfirmTimeoutMs,      // 重开提议确认超时（默认 300000=5分钟，超时按新建处理）
double restartSimilarityThreshold  // 重开触发的向量相似度阈值（默认 0.9）
```

（`SaaWorkflow.advance` 现有 rules 合并 inputs 的机制照搬：`restartSimilarityThreshold` 经 inputs 透传给节点。）

### 3.4 提取 TopicBacktrackService（D7）

从 `EnsureTopicNode` 迁出以下能力，`EnsureTopicNode` 与引擎重开路径共用（签名示意）：

```java
@Service
public class TopicBacktrackService {

    /** 重开候选检测：先精确（DB），未命中再语义（向量，阈值取 rules.restartSimilarityThreshold）。
     *  候选必须属于本群且状态为 CLOSED；向量异常按无命中处理，不阻塞建题。 */
    Optional<Topic> findRestartCandidate(Long groupId, String proposedTitle);

    /** 语义回溯（迁移自 backtrackTopic）：相似历史画像+结论；新增只保留 CLOSED 候选的过滤 */
    TopicBacktrack backtrack(Topic topic, String originalTitle);

    /** 回填（迁移自 backfillChatMessages）：boundary 参数化——建题传群内最后关闭时间，重开传候选 closedAt */
    int backfillChatMessages(Long groupId, Long topicId, int limit, LocalDateTime boundary);

    /** 历史提示格式化（迁移自 formatHistoryHint） */
    String formatHistoryHint(List<UserTopicProfile> history, List<String> relatedConclusions);
}
```

### 3.5 EnsureTopicNode 改造

在建题门槛判定通过之后、`topicRepository.save` **之前**插入检测：

```java
// 达门槛且未被一次性抑制（SKIP_RESTART_CHECK 来自 inputs，D4）
if (!state.value(StateKeys.SKIP_RESTART_CHECK, false)) {
    Optional<Topic> candidate = backtrackService.findRestartCandidate(groupId, topicTitle);
    if (candidate.isPresent()) {
        result.put(StateKeys.DISCUSS_MODE, StateKeys.MODE_RESTART_PROPOSED);
        result.put(StateKeys.RESTART_CANDIDATE_TOPIC_ID, candidate.get().getId());
        result.put(StateKeys.ENSURE_SUCCESS, true);       // 语义：流程正常，只是停在等决策
        result.put(StateKeys.LOW_DISCUSS_STREAK, 0);
        // 不建题、不回填、不广播 TOPIC_CREATED
        return result;
    }
}
// …… 现有建题流程不变（撞车加后缀逻辑保留，成为 skip 分支下的兜底）
```

注意：语义召回候选的过滤（本群 + CLOSED）在 `findRestartCandidate` 内部完成，同时反哺 `backtrack`（修掉活跃话题被当历史注入的潜在问题）。

### 3.6 SaaWorkflow 图改造

ensure-topic 条件边由二分支变三分支：

```java
graph.addConditionalEdges("ensure-topic",
        AsyncEdgeAction.edge_async(state ->
                StateKeys.MODE_RESTART_PROPOSED.equals(state.value(StateKeys.DISCUSS_MODE, ""))
                        ? "RESTART_PROPOSED"
                        : (state.value(StateKeys.ENSURE_SUCCESS, false) ? "DISCUSS" : "CHAT")),
        Map.of(
                "DISCUSS", "discuss",
                "CHAT", "chat",
                "RESTART_PROPOSED", StateGraph.END   // 图停在"等用户决策"，与 WAIT/CONCLUDE_PROPOSED 同构
        ));
```

KeyStrategyFactory 注册新键（均 ReplaceStrategy）：`RESTART_CANDIDATE_TOPIC_ID`、`SKIP_RESTART_CHECK`；`OBSERVABLE_STATE_KEYS` 增加 `RESTART_CANDIDATE_TOPIC_ID`（前端流程条可见）。

### 3.7 DiscussionEngine 改造（核心增量）

**UserSignal 增加类型**（向后兼容，默认 MESSAGE）：

```java
public enum SignalKind { MESSAGE, RESTART_ACCEPT, RESTART_REJECT }

public record UserSignal(SignalKind kind, Long userId, String content,
                         List<Long> mentionedAgentIds, Long repliedToAgentId) {
    public static UserSignal message(Long userId, String content,
                                     List<Long> mentionedAgentIds, Long repliedToAgentId) { ... }
}
```

`onUserSignal` 不变；新增入口供接收域调用：

```java
/** 重开决策信号入队（WS RESTART_TOPIC 指令翻译而来） */
public void onRestartDecision(Long groupId, boolean accept) {
    stateOf(groupId).queue.offer(accept ? UserSignal.restartAccept(...) : UserSignal.restartReject(...));
    wake(groupId);
}
```

**GroupState 增加**：`PendingRestart pendingRestart`（loop 线程私有，无并发问题）

```java
private record PendingRestart(Long candidateTopicId, UserSignal origin) {}
```

**runLoop 信号分支**（advanceFlow 之后）：

```java
if (StateKeys.MODE_RESTART_PROPOSED.equals(mode)) {
    state.pendingRestart = new PendingRestart(
            result.stateValue(StateKeys.RESTART_CANDIDATE_TOPIC_ID, null), signal);
    if (handleRestartProposed(groupId, state)) return;   // 与 handleConcludeProposed 同构
    continue;
}
```

**主循环信号分支的类型守卫**：收到 RESTART_ACCEPT/REJECT 但 `pendingRestart == null`（超时后迟到的按钮、引擎重启后的残留）时，记日志丢弃，`continue`，不进 advanceFlow。

**handleRestartProposed**（镜像 `handleConcludeProposed`）：

```java
private boolean handleRestartProposed(Long groupId, GroupState state) throws InterruptedException {
    // 1. 广播提议卡片 + 横幅状态
    Topic candidate = topicRepository.findById(state.pendingRestart.candidateTopicId()).orElse(null);
    //    candidate 为空/已非 CLOSED（如已被归档）：按"新建"处理（见边界 E5）
    groupBroadcastService.broadcast(groupId, WsConstants.TOPIC_RESTART_PROPOSED, Map.of(
            "groupId", groupId, "candidateTopicId", ..., "candidateTitle", ...,
            "closedAt", ..., "messageCount", ..., "conclusionPreview", /*截断200字*/ ...,
            "proposedTitle", /*本次拟定标题*/, "timeoutMs", rules.restartConfirmTimeoutMs()));
    groupBroadcastService.broadcast(groupId, WsConstants.TOPIC_STATUS, Map.of(
            "groupId", groupId, "discussMode", StateKeys.MODE_RESTART_PROPOSED, ...));

    // 2. 阻塞等决策（与 CONCLUDE_PROPOSED 相同：群执行器被占用，属既有取舍）
    UserSignal decision = state.queue.poll(rules.restartConfirmTimeoutMs(), TimeUnit.MILLISECONDS);

    // 3-a. 接受：重开 + 回填 + 广播 + 原始消息重新入队
    if (decision != null && decision.kind() == RESTART_ACCEPT) {
        reopenTopic(state, candidate);          // 见下
        state.queue.offer(state.pendingRestart.origin());  // 原始 X 重新驱动讨论
        state.pendingRestart = null;
        return false;                            // 回主循环，poll 立即取到 X
    }
    // 3-b. 拒绝 / 隐式放弃（普通消息）/ 超时：一次性抑制建新题
    UserSignal resume = (decision != null && decision.kind() == SignalKind.MESSAGE)
            ? decision                                   // 隐式放弃：用户的新消息直接续流程
            : state.pendingRestart.origin();             // 拒绝/超时：原始 X 续流程
    state.pendingRestart = null;
    state.autoRounds = 0;
    state.queue.clear();
    DiscussionFlowResult r = advanceResume(groupId, resume, state /*, skipRestartCheck=true*/);
    updateRuntimeState(state, r);  pushTopicStatus(groupId, r);
    return r.concluded();
}

private void reopenTopic(GroupState state, Topic candidate) {
    candidate.reopen();                                     // CLOSED->IN_PROGRESS + 结论版本化(D5)
    if (!topicRepository.update(candidate))                 // 乐观锁失败 -> 按新建降级（E5）
        { /* 降级 advanceResume */ }
    backtrackService.backfillChatMessages(candidate.getChatGroupId(), candidate.getId(),
            rules.backfillLimit(), candidate.getClosedAt() /*注意：reopen 已置空，需在 reopen 前取*/);
    groupBroadcastService.broadcast(candidate.getChatGroupId(), WsConstants.TOPIC_RESTARTED, Map.of(
            "groupId", ..., "topicId", candidate.getId(), "title", candidate.getTitle(),
            "restartHint", "已重新开启话题「" + candidate.getTitle() + "」，继续上次的讨论"));
    groupBroadcastService.broadcast(..., WsConstants.TOPIC_STATUS_CHANGED, Map.of(
            "status", "IN_PROGRESS", "previousStatus", "CLOSED", ...));
    eventPublisher.publish(new TopicReopened(candidate.getId(), candidate.getChatGroupId(),
            candidate.getTitle()));   // 新领域事件（观测/后续扩展用）
}
```

> 实现注意：`reopen()` 会清空 `closedAt`，回填边界需在调用 reopen 前先取出。

**advanceFlow 改造**：抽 `advanceFlow(groupId, signal, state, boolean skipRestartCheck)`，现有调用传 false；`skipRestartCheck=true` 时 inputs 多放 `SKIP_RESTART_CHECK`。

### 3.8 接收域与 WS 入口

**ChatOrchestrator** 增加入口（与 onUserMessage 平行，纯翻译不碰状态）：

```java
/** 重开决策（WS RESTART_TOPIC 指令）：投递带类型信号给引擎 */
public void onRestartDecision(Long groupId, Long candidateTopicId, boolean accept) {
    discussionEngine.onRestartDecision(groupId, accept);   // topicId 校验在引擎 loop 线程内做
}
```

**WsMessageDispatcher** 增加分支：

```java
case WsConstants.RESTART_TOPIC -> chatOrchestrator.onRestartDecision(
        groupId,
        data.path("topicId").asLong(),
        data.path("accept").asBoolean(false));
```

> topicId 不匹配 pendingRestart 的迟到/错位指令由引擎侧守卫（3.7）吞掉并记日志，不回错误给前端（卡片早已过期，前端也会自行清理）。

### 3.9 WS 契约（前端改造要点）

| 方向 | 消息 | 载荷 | 说明 |
|---|---|---|---|
| S->C | `TOPIC_RESTART_PROPOSED` | `{groupId, candidateTopicId, candidateTitle, closedAt, messageCount, conclusionPreview, proposedTitle, timeoutMs}` | 渲染重开提议卡片（结论摘要 + [重开]/[新建] 按钮），复用 CONCLUDE_PROPOSED 横幅样式 |
| C->S | `RESTART_TOPIC` | `{topicId, accept: boolean}` | 按钮点击时发送 |
| S->C | `TOPIC_RESTARTED` | `{groupId, topicId, title, restartHint}` | 重开成功，卡片消失，横幅切回 IN_PROGRESS |
| S->C | `TOPIC_STATUS` | `discussMode=RESTART_PROPOSED` | 横幅进入提议态（与 CONCLUDE_PROPOSED 的双广播模式一致） |

前端清理时机：收到 `TOPIC_RESTARTED` 或 `TOPIC_CREATED`（新建路径）即移除提议卡片；超时由服务端兜底处理并广播后续事件，前端无需本地计时器（`timeoutMs` 仅用于进度展示，可选）。

## 4. 边界场景清单

| # | 场景 | 处理 |
|---|---|---|
| E1 | 提议窗口内用户连发多条普通消息 | poll 取到第一条即隐式放弃并 advanceResume(该消息)，其余由信号分支 `queue.clear()` 折叠（既有行为） |
| E2 | 迟到的 RESTART_TOPIC 指令（超时后点击） | 主循环类型守卫：pendingRestart 为空则记日志丢弃 |
| E3 | 引擎重启导致 pendingRestart 丢失 | 同 E2 守卫；内存态丢失与 passedAgentIds 等运行时状态同属既有单实例取舍 |
| E4 | 接受时候选话题已被并发归档（CLOSED->ARCHIVED） | `reopen()` 状态机抛 BizException，引擎捕获后按"新建"降级（advanceResume） |
| E5 | reopen 乐观锁冲突 | 同 E4，按"新建"降级 |
| E6 | 隐式放弃后用户消息意图为 CHAT（不建题） | 提议自然终结；后续再聊出同标题会再次提议（D4 一次性抑制的边界，接受） |
| E7 | 接受重开后原始消息 X 重新走完整图 | 会再次调用意图分类（多一次 LLM 调用）；正确性无影响，优化项见 §7 |
| E8 | 重开的话题再次收束 | conclusion 覆写当前字段，旧结论在 `feature.historyConclusions` 中保留（D5）；`TopicClosed` 事件/卡片按新一轮正常生成 |
| E9 | 建题并发（两个信号几乎同时达门槛） | 群串行执行器天然串行；第二个 advance 时话题已存在，EnsureTopicNode 走"已有活跃话题跳过"分支 |
| E10 | 语义候选是活跃话题或他群话题 | `findRestartCandidate` 过滤（本群 + CLOSED），不构成候选 |

## 5. 实施步骤

按依赖序分五步，每步可独立编译验证：

**Step 1 领域层**
- `TopicStatus.canTransitTo` 加 CLOSED->IN_PROGRESS；`Topic.reopen()` + `historyConclusions()`
- `TopicRepository.findClosedByGroupIdAndTitle` + infra mapper
- 单测：状态机转移矩阵、reopen 的结论 stash（空结论/带 feature 已有键/多次重开累积）

**Step 2 常量与规则**
- StateKeys / WsConstants / DiscussionRules 新增；start 模块装配 + application.yml 默认值
- `SaaWorkflow`：新键 KeyStrategy、ensure-topic 三分支条件边、OBSERVABLE_STATE_KEYS

**Step 3 TopicBacktrackService 提取**
- 迁 `backtrackTopic` / `backfillChatMessages` / `formatHistoryHint`，签名按 §3.4
- 新增 `findRestartCandidate`（精确 DB 优先，向量兜底，CLOSED 过滤）
- `EnsureTopicNode` 改为委托调用 + 插入 RESTART_PROPOSED 早退分支
- 单测：exact 命中 / 语义命中 / 双未命中 / 向量异常回退 / SKIP_RESTART_CHECK 短路 / 活跃话题排除

**Step 4 引擎与接收域**
- UserSignal 类型化 + `onRestartDecision` 入口
- GroupState.pendingRestart、runLoop 信号分支接 handleRestartProposed、类型守卫
- `advanceFlow` 重载 skipRestartCheck；`reopenTopic`（含 E4/E5 降级）
- ChatOrchestrator.onRestartDecision + WsMessageDispatcher 分支 + TopicReopened 事件
- 单测：四决策分支（accept/reject/implicit/timeout）+ E2/E4/E5 守卫（queue 用可注入时钟/直接构造信号）

**Step 5 前端契约与联调**
- 提议卡片、按钮指令、横幅状态、卡片清理时机
- 全链路验收（§6）

## 6. 验收标准

1. 用户在无活跃话题时发送与历史话题同标题的讨论消息 → 收到 `TOPIC_RESTART_PROPOSED`，含结论摘要与两个按钮；
2. 点[重开] → 收到 `TOPIC_RESTARTED`，topic 状态 IN_PROGRESS，关闭后的无归属消息已挂入该话题，原始消息触发 Agent 接续发言（含历史画像提示）；
3. 点[新建] / 超时 / 直接打字 → 建新题（撞标题时带时间后缀），触发消息得到应答，提议卡片消失；
4. 重开后再次收束 → 新结论正常生成与广播，`feature.historyConclusions` 保留前次结论；
5. 观测：ENSURE_TOPIC 日志可见候选检测与决策结果；`TopicReopened` 事件可追溯；
6. 回归：无重复标题的正常建题、CONCLUDE_PROPOSED 确认流、闲聊回退路径行为不变。

## 7. 风险与后续优化

| 项 | 说明 | 缓解/后续 |
|---|---|---|
| 群执行器占用 | 提议窗口阻塞 poll（与 WAIT/CONCLUDE_PROPOSED 同类既有取舍） | 沿用；执行器饥饿问题另行统一治理 |
| 双重意图分类 | accept 路径原始 X 重走完整图，多一次路由 LLM 调用（E7） | 后续：重入队时预设 `INTENT=DISCUSS`（复用 advanceAuto 的预设机制） |
| 提议打扰 | 一次性抑制后再次同标题仍会询问（E6） | 若线上反馈吵，加"本话题标题已拒绝"的会话级抑制计数 |
| 重开 kickoff 提示 | 重开后首轮发言的 userHistoryHint 丢失（ensure-topic 因活跃话题跳过，不产出 hint） | 后续：GroupState 增加一次性 `pendingKickoffHint`，由 reopenTopic 写入、advanceFlow 注入（复用 TopicBacktrackService.formatHistoryHint） |
| 向量库残留 | 重开话题的向量条目仍在库（对后续检索是噪音，虽被 CLOSED 过滤兜住） | 后续：TopicVectorService 增加 deleteTopic，reopen 时清理 |
| 结论历史 UI | feature.historyConclusions 暂无展示入口 | 后续：结论接口聚合返回版本列表 |
