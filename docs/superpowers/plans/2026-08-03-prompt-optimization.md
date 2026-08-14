# 提示词常量优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 根据《提示词优化方案》文档，对 `PromptConstants.java` 中 5 个提示词常量进行质量和格式优化，控制 LLM 输出长度、提升格式遵循度、统一结论结构，不改变下游 Java 逻辑。

**Architecture:** 单文件修改，仅变更提示词字符串常量。下游 `String.format` 注入点、`[[CONCLUDE]]`/`[[PASS]]` 检测、JSON 解析等契约保持不变，所有测试断言依赖的关键词全部保留。

**Tech Stack:** Java 文本块（Text Blocks）、String.format 占位符、JUnit 5 单元测试、Maven 构建

---

## File Structure

### Files

| 操作 | 路径 | 说明 |
|------|------|------|
| Modify | `dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java` | 修改 5 个常量：`CHAT_BASE`、`COLLABORATION_PROTOCOL`、`CONCLUSION_STAR`、`USER_PROFILE_EXTRACT`、`INTENT_CLASSIFIER`；`GROUP_MEMBERS_HEADER`、`HOST_DECISION`、`KNOWLEDGE_EXTRACT` 不变 |
| Test (run) | `dingRing-app/src/test/java/com/dingring/app/orchestrator/ContextBuilderTest.java` | 运行，验证 `"你的花名是「老王」"`、`"群成员名单"`、`"STAR 框架"` 断言不受影响 |
| Test (run) | `dingRing-app/src/test/java/com/dingring/app/orchestrator/DiscussionEngineTest.java` | 运行，验证 `[[CONCLUDE]]`/`[[PASS]]` 检测逻辑不受影响 |
| Test (run) | `dingRing-app/src/test/java/com/dingring/app/orchestrator/MessageRouterTest.java` | 运行，验证意图路由 JSON 解析（用 mock LLM，不依赖提示词内容） |
| Test (run) | `dingRing-infrastructure/src/test/java/com/dingring/infrastructure/llm/MockLlmServiceTest.java` | 运行，验证 MockLlmService 的 `"STAR"` 关键字检测 |

---

## Task 1: 修改 CHAT_BASE — 加发言长度与格式约束

**Files:**
- Modify: `dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java:15-16`
- Test: `dingRing-app/src/test/java/com/dingring/app/orchestrator/ContextBuilderTest.java`

### 变更说明

当前值（行 15-16）：
```java
public static final String CHAT_BASE = """
        你正在参与一个多人群聊讨论，你的花名是「%s」。历史消息以「花名: 内容」形式给出。请直接输出你的发言内容，不要重复花名前缀，保持简洁聚焦，与前面的讨论衔接。""";
```

优化目标：
1. 保留 `"你的花名是「%s」"` 格式（ContextBuilderTest L163 断言依赖）
2. 拆分为结构化的"发言要求"列表，LLM 遵循度更高
3. 硬限制：3-5 句话（约 100-200 字）
4. 允许 Markdown 基本格式，禁止标题（#）

- [ ] **Step 1: 用 Edit 工具替换 CHAT_BASE 文本块**

将第 15-16 行的 `CHAT_BASE` 常量值替换为：

```java
    /** 通用对话 system prompt（%s = agentName） */
    public static final String CHAT_BASE = """
            你正在参与一个多人群聊讨论，你的花名是「%s」。历史消息以「花名: 内容」形式给出。
            发言要求：
            - 直接输出你的发言内容，不要重复花名前缀
            - 每次发言 3-5 句话（约 100-200 字），像真实群聊一样简短聚焦
            - 可用 Markdown 加粗、列表等基本格式，但不要用标题（#）
            - 与前面的讨论自然衔接，不要重复已有观点""";
```

- [ ] **Step 2: 单独编译 dingRing-common 模块**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn compile -pl dingRing-common -q 2>&1 | tail -5
```
Expected: 无错误信息，退出码 0

- [ ] **Step 3: 跑 ContextBuilderTest 验证下游依赖不破坏**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn test -pl dingRing-app -Dtest=ContextBuilderTest -DfailIfNoTests=false -q 2>&1 | tail -20
```
Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`

---

## Task 2: 修改 COLLABORATION_PROTOCOL — 加 few-shot 示例

**Files:**
- Modify: `dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java:23-26`
- Test: `dingRing-app/src/test/java/com/dingring/app/orchestrator/DiscussionEngineTest.java`

### 变更说明

当前值（行 23-26）：
```java
public static final String COLLABORATION_PROTOCOL = """
        协作协议：
        1. 如果你认为当前主题已经讨论充分、可以收尾总结，请在本次发言的末尾另起一行输出标记 [[CONCLUDE]]（仅在确实认为可以结束时输出，其他情况绝不要提及或输出该标记）。
        2. 如果你对当前讨论没有新的观点或补充，请只输出 [[PASS]]（不要输出其他任何内容）；有实质内容时绝不要输出该标记。不要为了发言而发言，重复已有观点不如 [[PASS]]。""";
```

优化目标：
1. 保留 `[[CONCLUDE]]` / `[[PASS]]` 标记原文（DiscussionEngine.speakOnce 中的 `content.contains()` 检测、`PASS_MARKER`/`CONCLUDE_MARKER` 常量不变）
2. 在每个规则后加 few-shot 示例，提升格式遵循度
3. 强调"仅在发言末尾另起一行"和"只输出 [[PASS]] 不含其他内容"的精确格式

- [ ] **Step 1: 用 Edit 工具替换 COLLABORATION_PROTOCOL 文本块**

将第 23-26 行的 `COLLABORATION_PROTOCOL` 常量值替换为：

```java
    /** 协作协议：自主收束 + 跳过本轮 */
    public static final String COLLABORATION_PROTOCOL = """
            协作协议（严格遵守）：

            1. 认为讨论已充分、可以总结时，在发言末尾另起一行，精确输出标记 [[CONCLUDE]]
               示例：
               我觉得可以收尾了，大家的观点基本对齐。
               [[CONCLUDE]]
               其他情况绝不要输出或提及该标记。

            2. 对当前讨论没有新观点时，只输出 [[PASS]]，不输出任何其他内容
               示例：[[PASS]]
               有实质内容时绝不要输出该标记。不要为了发言而发言。""";
```

- [ ] **Step 2: 编译验证**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn compile -pl dingRing-common -q 2>&1 | tail -5
```
Expected: 无错误信息，退出码 0

- [ ] **Step 3: 跑 DiscussionEngineTest 验证标记检测不受影响**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn test -pl dingRing-app -Dtest=DiscussionEngineTest -DfailIfNoTests=false -q 2>&1 | tail -20
```
Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`

---

## Task 3: 修改 CONCLUSION_STAR — 加结构模板与长度限制

**Files:**
- Modify: `dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java:29-30`
- Test: `dingRing-app/src/test/java/com/dingring/app/orchestrator/ContextBuilderTest.java`
- Test: `dingRing-infrastructure/src/test/java/com/dingring/infrastructure/llm/MockLlmServiceTest.java`

### 变更说明

当前值（行 29-30）：
```java
public static final String CONCLUSION_STAR = """
        你被推选为本次群讨论的总结者。请针对主题「%s」，基于完整讨论记录，用 STAR 框架（Situation/Task/Action/Result）输出 Markdown 格式的讨论结论，并对各成员观点做简要点评。""";
```

优化目标：
1. 保留 `"STAR 框架"` 关键词（ContextBuilderTest L224、MockLlmService 的 STAR 检测依赖）
2. 给出明确的 `## Situation`、`## Task`、`## Action`、`## Result` 四段结构模板
3. Action 段要求标注发言人花名
4. 全文限制在 500 字以内

- [ ] **Step 1: 用 Edit 工具替换 CONCLUSION_STAR 文本块**

将第 29-30 行的 `CONCLUSION_STAR` 常量值替换为：

```java
    /** STAR 总结（%s = topicTitle） */
    public static final String CONCLUSION_STAR = """
            你被推选为本次群讨论的总结者。请针对主题「%s」，基于完整讨论记录，用 STAR 框架输出 Markdown 格式的讨论结论。

            格式要求：
            ## Situation（背景）
            一段话概述讨论的起因和背景。

            ## Task（任务）
            讨论要解决的核心问题。

            ## Action（行动）
            用列表归纳各方观点（标注发言人花名）：
            - **花名**：观点摘要
            - **花名**：观点摘要

            ## Result（结论）
            达成的共识、未解决的分歧、后续建议。

            全文控制在 500 字以内。""";
```

- [ ] **Step 2: 编译验证**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn compile -pl dingRing-common -q 2>&1 | tail -5
```
Expected: 无错误信息，退出码 0

- [ ] **Step 3: 跑 ContextBuilderTest 验证"STAR 框架"关键词仍在**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn test -pl dingRing-app -Dtest=ContextBuilderTest -DfailIfNoTests=false -q 2>&1 | tail -20
```
Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 4: 跑 MockLlmServiceTest**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn test -pl dingRing-infrastructure -Dtest=MockLlmServiceTest -DfailIfNoTests=false -q 2>&1 | tail -20
```
Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`

---

## Task 4: 修改 USER_PROFILE_EXTRACT — 加格式示例

**Files:**
- Modify: `dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java:70-71`

### 变更说明

当前值（行 70-71）：
```java
public static final String USER_PROFILE_EXTRACT = """
        你是用户画像分析师。请基于「已有画像」与「最近一段群聊对话」，输出更新后的用户画像。关注用户的表达习惯、情绪基调、决策偏好、思考方式等长期稳定特征，输出 3-6 条简短要点（纯文本，每行一条，以 - 开头），不要输出任何其他内容。注意：画像是跨群的全局特征，对话来自某一个群，不要把该群特定的讨论话题当作用户的长期特征。""";
```

优化目标：
1. 加明确的格式锚定：两行 `- 第一条`、`- 第二条` 示例
2. 明确"不要输出任何其他内容"
3. 保留 3-6 条的数量要求

- [ ] **Step 1: 用 Edit 工具替换 USER_PROFILE_EXTRACT 文本块**

将第 70-71 行的 `USER_PROFILE_EXTRACT` 常量值替换为：

```java
    /** 用户画像提炼 */
    public static final String USER_PROFILE_EXTRACT = """
            你是用户画像分析师。请基于「已有画像」与「最近一段群聊对话」，输出更新后的用户画像。
            关注用户的表达习惯、情绪基调、决策偏好、思考方式等长期稳定特征。
            画像是跨群的全局特征，不要把特定群的讨论话题当作用户的长期特征。

            严格按以下格式输出 3-6 条，不要输出任何其他内容：
            - 第一条特征描述
            - 第二条特征描述
            - ...""";
```

- [ ] **Step 2: 编译验证**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn compile -pl dingRing-common -q 2>&1 | tail -5
```
Expected: 无错误信息，退出码 0

---

## Task 5: 修改 INTENT_CLASSIFIER — 加边界示例

**Files:**
- Modify: `dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java:35-53`
- Test: `dingRing-app/src/test/java/com/dingring/app/orchestrator/MessageRouterTest.java`

### 变更说明

当前值（行 47-51）的参考示例部分：
```
            参考示例：
            - "哈哈哈是的" → {"intent":"CHAT","topicTitle":"","confidence":"HIGH"}
            - "我们项目该用 Redis 还是本地缓存？" → {"intent":"DISCUSS","topicTitle":"缓存方案选型","confidence":"HIGH"}
            - "最近总睡不好，大家有什么改善睡眠的办法吗" → {"intent":"DISCUSS","topicTitle":"睡眠质量改善方法","confidence":"HIGH"}
            - "那就先这样吧，帮我总结下结论" → {"intent":"CONCLUDE","topicTitle":"","confidence":"HIGH"}
```

优化目标：
在现有 4 个 HIGH 置信度示例后，追加 2 个 LOW 置信度边界示例，帮助 LLM 掌握 HIGH/LOW 的判定边界：
- `方案有什么问题吗` → DISCUSS + LOW（随口追问，可讨论可不讨论）
- `我最近在学 Go，感觉挺有意思的` → CHAT + LOW（话题性闲聊，不含求助/提问）

- [ ] **Step 1: 用 Edit 工具在 INTENT_CLASSIFIER 的参考示例部分追加 2 个边界案例**

找到现有示例最后一行（`- "那就先这样吧，帮我总结下结论" → ...`），在其后（JSON 格式要求之前）追加两行新示例：

替换的 old_string（完整的参考示例 + JSON 输出要求段落）：

```
            参考示例：
            - "哈哈哈是的" → {"intent":"CHAT","topicTitle":"","confidence":"HIGH"}
            - "我们项目该用 Redis 还是本地缓存？" → {"intent":"DISCUSS","topicTitle":"缓存方案选型","confidence":"HIGH"}
            - "最近总睡不好，大家有什么改善睡眠的办法吗" → {"intent":"DISCUSS","topicTitle":"睡眠质量改善方法","confidence":"HIGH"}
            - "那就先这样吧，帮我总结下结论" → {"intent":"CONCLUDE","topicTitle":"","confidence":"HIGH"}
            严格输出一行 JSON，不要输出任何其他内容，格式：
            {"intent": "CHAT|DISCUSS|CONCLUDE", "topicTitle": "按上述规则拟定的话题标题，非 DISCUSS 为空字符串", "confidence": "HIGH|LOW"}
```

替换后的 new_string：

```
            参考示例：
            - "哈哈哈是的" → {"intent":"CHAT","topicTitle":"","confidence":"HIGH"}
            - "我们项目该用 Redis 还是本地缓存？" → {"intent":"DISCUSS","topicTitle":"缓存方案选型","confidence":"HIGH"}
            - "最近总睡不好，大家有什么改善睡眠的办法吗" → {"intent":"DISCUSS","topicTitle":"睡眠质量改善方法","confidence":"HIGH"}
            - "那就先这样吧，帮我总结下结论" → {"intent":"CONCLUDE","topicTitle":"","confidence":"HIGH"}
            - "这个方案有什么问题吗" → {"intent":"DISCUSS","topicTitle":"方案问题分析","confidence":"LOW"}
            - "我最近在学 Go，感觉挺有意思的" → {"intent":"CHAT","topicTitle":"","confidence":"LOW"}
            严格输出一行 JSON，不要输出任何其他内容，格式：
            {"intent": "CHAT|DISCUSS|CONCLUDE", "topicTitle": "按上述规则拟定的话题标题，非 DISCUSS 为空字符串", "confidence": "HIGH|LOW"}
```

- [ ] **Step 2: 编译验证**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn compile -pl dingRing-common -q 2>&1 | tail -5
```
Expected: 无错误信息，退出码 0

- [ ] **Step 3: 跑 MessageRouterTest 验证 JSON 解析不受影响**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn test -pl dingRing-app -Dtest=MessageRouterTest -DfailIfNoTests=false -q 2>&1 | tail -20
```
Expected: `Tests run: N, Failures: 0, Errors: 0, Skipped: 0`

---

## Task 6: 全量编译与下游模块测试

**Files:** (run only)

- [ ] **Step 1: 全量编译，确保所有依赖 PromptConstants 的模块都能编译通过**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn compile -pl dingRing-common,dingRing-domain,dingRing-infrastructure,dingRing-app,dingRing-adapter,start -am -q 2>&1 | tail -10
```
Expected: 无错误信息，退出码 0

- [ ] **Step 2: 一次性运行所有关联模块的单元测试**

Run:
```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ && mvn test -pl dingRing-app,dingRing-infrastructure -Dtest=ContextBuilderTest,DiscussionEngineTest,MessageRouterTest,MockLlmServiceTest -DfailIfNoTests=false -q 2>&1 | tail -30
```
Expected: 所有列出的测试类中，`Tests run: N, Failures: 0, Errors: 0, Skipped: 0`

- [ ] **Step 3: 提交变更（仅当所有测试通过）**

```bash
cd /Users/Zhuanz/IdeaProjects/DingRingJ
git add dingRing-common/src/main/java/com/dingring/common/constant/PromptConstants.java
git commit -m "feat(prompt): 优化 5 个 LLM 提示词常量的质量与格式

- CHAT_BASE: 拆分为结构化发言要求，硬限制 3-5 句话（100-200 字），允许 Markdown 基本格式
- COLLABORATION_PROTOCOL: 为 [[CONCLUDE]] / [[PASS]] 加 few-shot 示例，提升格式遵循度
- CONCLUSION_STAR: 给出 ## Situation/Task/Action/Result 明确结构模板，限制 500 字以内
- USER_PROFILE_EXTRACT: 加格式锚定示例，统一输出为 - 前缀列表
- INTENT_CLASSIFIER: 追加 2 个 LOW 置信度边界示例，稳定 HIGH/LOW 判定边界

保留所有下游契约：花名格式、群成员名单、STAR 框架关键词、标记字符串、JSON 输出契约。"
```

---

## Self-Review Checklist

**1. Spec coverage:**

| 方案要求 | 对应任务 |
|---|---|
| 优化 1：CHAT_BASE 加长度和格式约束 | Task 1 |
| 优化 2：COLLABORATION_PROTOCOL 加 few-shot | Task 2 |
| 优化 3：CONCLUSION_STAR 加结构模板+500字限制 | Task 3 |
| 优化 4：USER_PROFILE_EXTRACT 加格式示例 | Task 4 |
| 优化 5：INTENT_CLASSIFIER 加边界示例 | Task 5 |
| 验证：mvn compile | Task 6.1 |
| 验证：ContextBuilderTest | Task 1.3, Task 3.3 |
| 验证：DiscussionEngineTest | Task 2.3 |
| 验证：MessageRouterTest | Task 5.3 |
| 验证：MockLlmServiceTest | Task 3.4 |
| 仅改 1 个文件 | 全部 Task 均只操作 PromptConstants.java |
| 保留"你的花名是「%s」" | Task 1 替换内容中明确保留 |
| 保留"STAR 框架" | Task 3 替换内容中明确保留 |
| 保留"群成员名单" | GROUP_MEMBERS_HEADER 未改动 |
| [[CONCLUDE]] / [[PASS]] 字符串不变 | Task 2 中标记原文保留 |

**2. Placeholder scan:**
- 无 TBD / TODO / "类似 Task N" 等占位符
- 每个代码步骤都给出了完整的 old_string/new_string 或目标代码
- 每个运行步骤给出了完整命令和期望输出

**3. Type consistency:**
- 所有常量均为 `public static final String`，类型不变
- 格式占位符 `%s` 出现位置与当前一致：CHAT_BASE 1 个、CONCLUSION_STAR 1 个
- 下游 `ContextBuilder.buildSystemPrompt()` 的 `String.format(CHAT_BASE, agentName)`、`ContextBuilder.buildForConclusion()` 的 `String.format(CONCLUSION_STAR, topicTitle)` 调用不变

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-03-prompt-optimization.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
