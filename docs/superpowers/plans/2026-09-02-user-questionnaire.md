# 用户问卷画像采集 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增静态结构化问卷（13 题、6 维度），作为用户画像主数据源——答案存新表 `user_questionnaire`（事实源），模板拼接生成画像文本写入 `user_profile` 新版本（消费视图），复用现有 `ProfileInjectionHook` 注入链路让所有 AI 同事感知用户背景。

**Architecture:** DDD 分层（adapter → app → domain ← infrastructure）。题目 schema 定义在 domain 层作为前后端唯一契约源（`QuestionnaireDefinition`）；提交时单事务双写答案事实源与画像消费视图；校验失败抛 `ParamException`（HTTP 400）。前端新增「个人画像」设置页，4 步向导按 schema 动态渲染。

**Tech Stack:** Java 21 / Spring Boot 3.5 / MyBatis（XML Mapper + JsonMapTypeHandler）/ MySQL；React 19 + TypeScript + Vite；JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-02-user-questionnaire-design.md`

---

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `dingRing-domain/src/main/java/com/dingring/domain/user/QuestionnaireDefinition.java` | Create | 题目 schema 唯一契约源：选项枚举 + 13 题定义 + 答案校验 |
| `dingRing-domain/src/main/java/com/dingring/domain/user/QuestionnaireProfileAssembler.java` | Create | 答案 → 画像文本模板拼接 |
| `dingRing-domain/src/main/java/com/dingring/domain/user/UserQuestionnaire.java` | Create | 问卷答案实体（版本化） |
| `dingRing-domain/src/main/java/com/dingring/domain/user/UserQuestionnaireRepository.java` | Create | 仓储端口 |
| `dingRing-domain/src/test/java/com/dingring/domain/user/QuestionnaireDefinitionTest.java` | Create | 校验与 schema 结构测试 |
| `dingRing-domain/src/test/java/com/dingring/domain/user/QuestionnaireProfileAssemblerTest.java` | Create | 画像文本拼接测试 |
| `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/mapper/UserQuestionnaireMapper.java` | Create | MyBatis Mapper 接口 |
| `dingRing-infrastructure/src/main/resources/mapper/UserQuestionnaireMapper.xml` | Create | SQL 与结果映射 |
| `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/repository/UserQuestionnaireRepositoryImpl.java` | Create | 仓储实现（版本化写回） |
| `start/src/main/resources/schema.sql` | Modify | 新增 `user_questionnaire` 建表 DDL |
| `dingRing-app/src/main/java/com/dingring/app/dto/request/SubmitQuestionnaireRequest.java` | Create | 提交请求体 |
| `dingRing-app/src/main/java/com/dingring/app/dto/response/QuestionnaireDTO.java` | Create | schema + 答案响应 DTO |
| `dingRing-app/src/main/java/com/dingring/app/service/QuestionnaireAppService.java` | Create | 编排：校验 → 事务内双写 |
| `dingRing-app/src/test/java/com/dingring/app/service/QuestionnaireAppServiceTest.java` | Create | 应用服务测试 |
| `dingRing-adapter/src/main/java/com/dingring/adapter/rest/QuestionnaireController.java` | Create | REST 接口 |
| `dingRing-adapter/src/test/java/com/dingring/adapter/rest/QuestionnaireControllerTest.java` | Create | REST API 测试 |
| `frontend/src/api.ts` | Modify | 问卷 API 封装与类型 |
| `frontend/src/pages/Profile/index.tsx` | Create | 个人画像问卷页（4 步向导） |
| `frontend/src/pages/Profile/style.css` | Create | 问卷页样式 |
| `frontend/src/App.tsx` | Modify | 注册 `/profile` 路由 |
| `frontend/src/components/Sidebar.tsx` | Modify | 侧边栏新增入口 |
| `docs/wiki/data-model.md`、`docs/wiki/rest-api.md` | Modify | 补充新表与新接口文档 |

**零改动确认**：`ProfileInjectionHook`、`SimpleProfileService`、`UserProfile`/`UserProfileRepository` 均不修改——问卷画像作为 `user_profile` 新版本写入后自动走既有注入链路。

**与 spec 的两处偏差（已在计划中固化）**：
1. spec 提到"新增 ErrorCode"——实际复用现有 `ParamException`（即 `ErrorCode.PARAM_INVALID`，HTTP 400），与 `GroupAppService` 等现有校验风格一致，避免冗余错误码；
2. spec 测试策略提到 Repository 集成测试——项目无 Repository 集成测试基础设施（现有测试均为纯单元测试），v1 以单元测试覆盖 + Task 8 手工端到端验证替代。

---

### Task 1: QuestionnaireDefinition —— 题目 schema 与答案校验（domain）

**Files:**
- Create: `dingRing-domain/src/main/java/com/dingring/domain/user/QuestionnaireDefinition.java`
- Test: `dingRing-domain/src/test/java/com/dingring/domain/user/QuestionnaireDefinitionTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.dingring.domain.user;

import com.dingring.common.exception.ParamException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link QuestionnaireDefinition} 答案校验与 schema 结构测试。
 */
@DisplayName("QuestionnaireDefinition 问卷定义与校验")
class QuestionnaireDefinitionTest {

    /** 一份完整合法的答案（各测试在其副本上做破坏性修改） */
    static Map<String, Object> validAnswers() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("career", "MID");
        answers.put("directions", List.of("BACKEND"));
        answers.put("techStack", "Java / Spring Boot / MySQL");
        answers.put("motivations", List.of("INTERVIEW"));
        answers.put("goalDescription", "三个月搞定 JVM 调优");
        answers.put("focusAreas", List.of("JVM", "CONCURRENCY"));
        answers.put("learningNow", "Netty 源码");
        answers.put("areaLevels", Map.of("JVM", "INTERMEDIATE", "CONCURRENCY", "ELEMENTARY"));
        answers.put("discussionDepth", "DEEP_THEORY");
        answers.put("discussionPace", "DIVERGE_THEN_CONVERGE");
        answers.put("interactionStyle", "SOCRATIC");
        answers.put("presentationForms", List.of("SOURCE_ANALYSIS", "DIAGRAM"));
        answers.put("terminologyLanguage", "MIXED");
        return answers;
    }

    @Nested
    @DisplayName("validate 答案校验")
    class Validate {

        @Test
        @DisplayName("完整合法答案通过校验")
        void validAnswersShouldPass() {
            assertThatCode(() -> QuestionnaireDefinition.validate(validAnswers()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("可选填空题缺失仍合法")
        void missingOptionalTextShouldPass() {
            Map<String, Object> answers = validAnswers();
            answers.remove("techStack");
            answers.remove("goalDescription");
            answers.remove("learningNow");
            assertThatCode(() -> QuestionnaireDefinition.validate(answers))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("答案为 null 抛出 ParamException")
        void nullAnswersShouldFail() {
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(null))
                    .isInstanceOf(ParamException.class);
        }

        @Test
        @DisplayName("缺少必答项抛出 ParamException 并提示题目名")
        void missingRequiredKeyShouldFail() {
            Map<String, Object> answers = validAnswers();
            answers.remove("career");
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(answers))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("职业身份");
        }

        @Test
        @DisplayName("单选值不在选项内抛出 ParamException")
        void illegalSingleValueShouldFail() {
            Map<String, Object> answers = validAnswers();
            answers.put("career", "CTO");
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(answers))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("职业身份");
        }

        @Test
        @DisplayName("多选含非法值或为空抛出 ParamException")
        void illegalMultiValueShouldFail() {
            Map<String, Object> answers = validAnswers();
            answers.put("directions", List.of("BACKEND", "DEVREL"));
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(answers))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("主要技术方向");

            answers.put("directions", List.of());
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(answers))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("主要技术方向");
        }

        @Test
        @DisplayName("areaLevels 与 focusAreas 不一致抛出 ParamException")
        void areaLevelsMismatchShouldFail() {
            // 少评一个已选领域
            Map<String, Object> answers = validAnswers();
            answers.put("areaLevels", Map.of("JVM", "INTERMEDIATE"));
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(answers))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("水平自评");

            // 多评一个未选领域
            answers.put("areaLevels", Map.of(
                    "JVM", "INTERMEDIATE", "CONCURRENCY", "ELEMENTARY", "REDIS", "ADVANCED"));
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(answers))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("水平自评");
        }

        @Test
        @DisplayName("未知题目 key 抛出 ParamException")
        void unknownKeyShouldFail() {
            Map<String, Object> answers = validAnswers();
            answers.put("favoriteColor", "BLUE");
            assertThatThrownBy(() -> QuestionnaireDefinition.validate(answers))
                    .isInstanceOf(ParamException.class)
                    .hasMessageContaining("favoriteColor");
        }
    }

    @Nested
    @DisplayName("questions 题目 schema")
    class Schema {

        @Test
        @DisplayName("共 13 题且 key 唯一")
        void shouldHave13UniqueQuestions() {
            List<QuestionnaireDefinition.QuestionDef> questions = QuestionnaireDefinition.questions();
            assertThat(questions).hasSize(13);
            assertThat(questions).extracting(QuestionnaireDefinition.QuestionDef::key)
                    .doesNotHaveDuplicates();
        }
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `mvn -pl dingRing-domain -am test -Dtest=QuestionnaireDefinitionTest`
Expected: COMPILATION ERROR（`QuestionnaireDefinition` 不存在）

- [ ] **Step 3: 实现 QuestionnaireDefinition**

```java
package com.dingring.domain.user;

import com.dingring.common.exception.ParamException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问卷题目定义（前后端唯一契约源）：题目 key、类型、选项枚举与中文文案。
 * <p>题目 schema 供前端动态渲染（GET /api/questionnaire），校验 {@link #validate} 与
 * 画像拼接 {@link QuestionnaireProfileAssembler} 均基于同一份定义，避免多处漂移。
 * <p>答案值统一使用枚举编码（如 MID），与中文文案解耦——文案改版不影响存量数据。
 */
public final class QuestionnaireDefinition {

    private QuestionnaireDefinition() {
    }

    /** 题目类型 */
    public enum QuestionType { SINGLE, MULTI, TEXT, AREA_LEVELS }

    /** 选项枚举公共接口：提供中文文案 */
    public interface Labelled { String label(); }

    // ---- 题目 key（画像拼接器与前端共用同一契约） ----
    public static final String KEY_CAREER = "career";
    public static final String KEY_DIRECTIONS = "directions";
    public static final String KEY_TECH_STACK = "techStack";
    public static final String KEY_MOTIVATIONS = "motivations";
    public static final String KEY_GOAL_DESCRIPTION = "goalDescription";
    public static final String KEY_FOCUS_AREAS = "focusAreas";
    public static final String KEY_LEARNING_NOW = "learningNow";
    public static final String KEY_AREA_LEVELS = "areaLevels";
    public static final String KEY_DISCUSSION_DEPTH = "discussionDepth";
    public static final String KEY_DISCUSSION_PACE = "discussionPace";
    public static final String KEY_INTERACTION_STYLE = "interactionStyle";
    public static final String KEY_PRESENTATION_FORMS = "presentationForms";
    public static final String KEY_TERMINOLOGY_LANGUAGE = "terminologyLanguage";

    // ---- 题目维度（前端分步向导按维度分组） ----
    public static final String DIM_BACKGROUND = "BACKGROUND";
    public static final String DIM_GOAL = "GOAL";
    public static final String DIM_FOCUS = "FOCUS";
    public static final String DIM_STYLE = "STYLE";
    public static final String DIM_PRESENTATION = "PRESENTATION";

    // ---- 选项枚举 ----

    public enum Career implements Labelled {
        STUDENT("在校学生"), JUNIOR("初级工程师（0-2 年）"), MID("中级工程师（3-5 年）"),
        SENIOR("高级工程师（5 年以上）"), CAREER_CHANGE("转行学习者");
        private final String label;
        Career(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Direction implements Labelled {
        BACKEND("后端"), FRONTEND("前端"), MOBILE("移动端"), DATA_AI("数据与 AI"),
        INFRA("基础设施运维"), TESTING("测试"), ARCHITECTURE("架构");
        private final String label;
        Direction(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum Motivation implements Labelled {
        INTERVIEW("面试冲刺"), WORK_PROBLEM("工作难题攻坚"), INTEREST("兴趣驱动"),
        SYSTEMATIC("系统性进阶"), CAREER_SWITCH("转行准备"), CERTIFICATION("考证认证");
        private final String label;
        Motivation(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum FocusArea implements Labelled {
        JVM("JVM"), CONCURRENCY("并发编程"), DISTRIBUTED("分布式"), DATABASE("数据库"),
        REDIS("Redis"), NETWORK("网络"), OS("操作系统"), FRAMEWORK_SOURCE("框架源码"),
        AI_LLM("AI 与 LLM"), FRONTEND_ENGINEERING("前端工程");
        private final String label;
        FocusArea(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** 水平枚举（语义对齐 UserTopicProfile.understandingLevel 的 BEGINNER/INTERMEDIATE/ADVANCED） */
    public enum Level implements Labelled {
        BEGINNER("入门"), ELEMENTARY("初级"), INTERMEDIATE("中级"), ADVANCED("高级");
        private final String label;
        Level(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum DiscussionDepth implements Labelled {
        DEEP_THEORY("深度原理派"), PRAGMATIC("实战落地派"), BALANCED("均衡");
        private final String label;
        DiscussionDepth(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum DiscussionPace implements Labelled {
        DIVERGE_THEN_CONVERGE("发散脑暴"), FAST_CONVERGE("快速收敛"), BALANCED("均衡");
        private final String label;
        DiscussionPace(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum InteractionStyle implements Labelled {
        SOCRATIC("可被追问挑战"), GUIDED("循循善诱"), DIRECT("直接给答案");
        private final String label;
        InteractionStyle(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum PresentationForm implements Labelled {
        CODE_EXAMPLE("代码示例"), ANALOGY("类比举例"), SOURCE_ANALYSIS("源码分析"),
        DIAGRAM("图解步骤"), DOC_REFERENCE("官方文档引用");
        private final String label;
        PresentationForm(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum TerminologyLanguage implements Labelled {
        CHINESE_FIRST("中文优先"), MIXED("中英混用"), ENGLISH_FIRST("英文优先");
        private final String label;
        TerminologyLanguage(String label) { this.label = label; }
        public String label() { return label; }
    }

    // ---- 题目 schema ----

    /** 选项定义（value=枚举编码，label=中文文案） */
    public record OptionDef(String value, String label) {}

    /** 题目定义（required=false 的题可缺省，当前仅 TEXT 题） */
    public record QuestionDef(String key, String label, String dimension, QuestionType type,
                              boolean required, List<OptionDef> options) {}

    private static final List<QuestionDef> QUESTIONS = List.of(
            new QuestionDef(KEY_CAREER, "你的职业身份", DIM_BACKGROUND, QuestionType.SINGLE, true, options(Career.values())),
            new QuestionDef(KEY_DIRECTIONS, "主要技术方向", DIM_BACKGROUND, QuestionType.MULTI, true, options(Direction.values())),
            new QuestionDef(KEY_TECH_STACK, "主力技术栈", DIM_BACKGROUND, QuestionType.TEXT, false, List.of()),
            new QuestionDef(KEY_MOTIVATIONS, "学习的主要目的", DIM_GOAL, QuestionType.MULTI, true, options(Motivation.values())),
            new QuestionDef(KEY_GOAL_DESCRIPTION, "一句话描述你的学习目标", DIM_GOAL, QuestionType.TEXT, false, List.of()),
            new QuestionDef(KEY_FOCUS_AREAS, "正在学/关注的方向", DIM_FOCUS, QuestionType.MULTI, true, options(FocusArea.values())),
            new QuestionDef(KEY_LEARNING_NOW, "具体正在学的内容", DIM_FOCUS, QuestionType.TEXT, false, List.of()),
            new QuestionDef(KEY_AREA_LEVELS, "各关注领域水平自评", DIM_FOCUS, QuestionType.AREA_LEVELS, true, options(Level.values())),
            new QuestionDef(KEY_DISCUSSION_DEPTH, "讨论深度", DIM_STYLE, QuestionType.SINGLE, true, options(DiscussionDepth.values())),
            new QuestionDef(KEY_DISCUSSION_PACE, "讨论节奏", DIM_STYLE, QuestionType.SINGLE, true, options(DiscussionPace.values())),
            new QuestionDef(KEY_INTERACTION_STYLE, "互动方式", DIM_STYLE, QuestionType.SINGLE, true, options(InteractionStyle.values())),
            new QuestionDef(KEY_PRESENTATION_FORMS, "偏好的讲解形式", DIM_PRESENTATION, QuestionType.MULTI, true, options(PresentationForm.values())),
            new QuestionDef(KEY_TERMINOLOGY_LANGUAGE, "术语语言习惯", DIM_PRESENTATION, QuestionType.SINGLE, true, options(TerminologyLanguage.values()))
    );

    /** 题目 schema（有序，供 GET /api/questionnaire 下发） */
    public static List<QuestionDef> questions() {
        return QUESTIONS;
    }

    /**
     * 校验问卷答案：必答项齐全、取值在选项内、areaLevels 与 focusAreas 联动一致、无未知 key。
     *
     * @throws ParamException 任一规则不满足时抛出（HTTP 400）
     */
    public static void validate(Map<String, Object> answers) {
        if (answers == null) {
            throw new ParamException("问卷答案不能为空");
        }
        for (QuestionDef q : QUESTIONS) {
            Object value = answers.get(q.key());
            if (value == null) {
                if (q.required()) {
                    throw new ParamException("问卷缺少必答项：" + q.label());
                }
                continue;
            }
            switch (q.type()) {
                case SINGLE -> requireInOptions(q, value);
                case MULTI -> requireMultiInOptions(q, value);
                case TEXT -> requireText(q, value);
                case AREA_LEVELS -> requireAreaLevels(q, value, answers);
            }
        }
        Set<String> unknownKeys = new LinkedHashSet<>(answers.keySet());
        QUESTIONS.forEach(q -> unknownKeys.remove(q.key()));
        if (!unknownKeys.isEmpty()) {
            throw new ParamException("问卷包含未知题目 key：" + String.join(", ", unknownKeys));
        }
    }

    private static void requireInOptions(QuestionDef q, Object value) {
        if (!(value instanceof String s) || !containsOption(q, s)) {
            throw new ParamException("取值不在选项内：" + q.label());
        }
    }

    private static void requireMultiInOptions(QuestionDef q, Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new ParamException("至少选择一项：" + q.label());
        }
        for (Object item : list) {
            if (!(item instanceof String s) || !containsOption(q, s)) {
                throw new ParamException("取值不在选项内：" + q.label());
            }
        }
    }

    private static void requireText(QuestionDef q, Object value) {
        if (!(value instanceof String)) {
            throw new ParamException("答案须为文本：" + q.label());
        }
    }

    /**
     * 水平自评联动校验：key 集合必须与 focusAreas 完全一致（不多不少）。
     * <p>依赖不变式：QUESTIONS 中 focusAreas 先于 areaLevels 定义，
     * 校验到达此处时 focusAreas 已通过 MULTI 校验（非空 List）。
     */
    private static void requireAreaLevels(QuestionDef q, Object value, Map<String, Object> answers) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ParamException("答案格式错误：" + q.label());
        }
        Set<?> expected = new LinkedHashSet<>((List<?>) answers.get(KEY_FOCUS_AREAS));
        if (!map.keySet().equals(expected)) {
            throw new ParamException("水平自评须覆盖且仅覆盖已选关注领域：" + q.label());
        }
        for (Object v : map.values()) {
            if (!(v instanceof String s) || !containsOption(q, s)) {
                throw new ParamException("取值不在选项内：" + q.label());
            }
        }
    }

    private static boolean containsOption(QuestionDef q, String value) {
        return q.options().stream().anyMatch(o -> o.value().equals(value));
    }

    /** 枚举 → 选项列表（value=枚举名）。嵌套枚举均实现 Labelled，强转 Enum 安全。 */
    private static List<OptionDef> options(Labelled[] values) {
        List<OptionDef> list = new ArrayList<>(values.length);
        for (Labelled v : values) {
            list.add(new OptionDef(((Enum<?>) v).name(), v.label()));
        }
        return list;
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl dingRing-domain -am test -Dtest=QuestionnaireDefinitionTest`
Expected: `Tests run: 9, Failures: 0, Errors: 0`（8 个校验用例 + 1 个 schema 用例）

- [ ] **Step 5: Commit**

```bash
git add dingRing-domain/src/main/java/com/dingring/domain/user/QuestionnaireDefinition.java \
        dingRing-domain/src/test/java/com/dingring/domain/user/QuestionnaireDefinitionTest.java
git commit -m "feat: 问卷题目定义与答案校验（前后端唯一契约源）"
```

---

### Task 2: UserQuestionnaire 实体与仓储端口（domain）

**Files:**
- Create: `dingRing-domain/src/main/java/com/dingring/domain/user/UserQuestionnaire.java`
- Create: `dingRing-domain/src/main/java/com/dingring/domain/user/UserQuestionnaireRepository.java`

纯数据实体与端口接口，无行为逻辑，不写测试（与 `UserProfile` 一致）。

- [ ] **Step 1: 写实体**

```java
package com.dingring.domain.user;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户问卷答案（表 user_questionnaire）：画像主数据的事实源。
 * <p>版本化写回：重填时标记旧记录失效、插入新记录（version+1），保留填写轨迹；
 * 消费视图（画像文本）由 QuestionnaireProfileAssembler 从当前有效版本拼接。
 */
@Data
public class UserQuestionnaire {

    /** 记录状态：有效 */
    public static final Integer STATUS_ACTIVE = 1;
    /** 记录状态：失效（历史版本） */
    public static final Integer STATUS_EXPIRED = 0;

    private Long id;
    /** 用户 ID */
    private Long userId;
    /** 答案（题目 key → 枚举编码；TEXT 题为原文，与文案解耦） */
    private Map<String, Object> answers;
    /** 填写版本号（每次重填 +1） */
    private Integer version;
    /** 记录状态：1=有效, 0=失效 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

- [ ] **Step 2: 写仓储端口**

```java
package com.dingring.domain.user;

import java.util.Optional;

/**
 * 用户问卷答案仓储接口。
 */
public interface UserQuestionnaireRepository {

    /** 查询当前有效的问卷答案（status = 1） */
    Optional<UserQuestionnaire> findValidByUserId(Long userId);

    /** 保存为新版本：将旧 status 置为 0，version 取历史最大值 +1，再插入 status=1 的新记录 */
    void saveNewVersion(UserQuestionnaire questionnaire);
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn -pl dingRing-domain -am compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add dingRing-domain/src/main/java/com/dingring/domain/user/UserQuestionnaire.java \
        dingRing-domain/src/main/java/com/dingring/domain/user/UserQuestionnaireRepository.java
git commit -m "feat: 用户问卷答案实体与仓储端口"
```

---

### Task 3: QuestionnaireProfileAssembler —— 画像文本拼接（domain）

**Files:**
- Create: `dingRing-domain/src/main/java/com/dingring/domain/user/QuestionnaireProfileAssembler.java`
- Test: `dingRing-domain/src/test/java/com/dingring/domain/user/QuestionnaireProfileAssemblerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.dingring.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QuestionnaireProfileAssembler} 画像文本拼接测试。
 */
@DisplayName("QuestionnaireProfileAssembler 画像文本拼接")
class QuestionnaireProfileAssemblerTest {

    @Test
    @DisplayName("完整答案拼接 5 行画像要点")
    void shouldAssembleFullProfile() {
        Map<String, Object> answers = QuestionnaireDefinitionTest.validAnswers();

        String text = QuestionnaireProfileAssembler.assemble(answers);

        assertThat(text).hasLineCount(5);
        assertThat(text).contains("- 背景：中级工程师（3-5 年），主要方向：后端，主力技术栈：Java / Spring Boot / MySQL");
        assertThat(text).contains("- 学习目标：面试冲刺，三个月搞定 JVM 调优");
        assertThat(text).contains("- 关注领域：JVM（中级）、并发编程（初级），正在学：Netty 源码");
        assertThat(text).contains("- 讨论偏好：深度原理派，发散脑暴，可被追问挑战");
        assertThat(text).contains("- 呈现偏好：偏好源码分析、图解步骤，术语中英混用");
    }

    @Test
    @DisplayName("可选填空为空时跳过对应片段")
    void shouldSkipBlankOptionalFragments() {
        Map<String, Object> answers = QuestionnaireDefinitionTest.validAnswers();
        answers.put("techStack", "  ");
        answers.put("goalDescription", "");
        answers.put("learningNow", null);

        String text = QuestionnaireProfileAssembler.assemble(answers);

        assertThat(text).contains("- 背景：中级工程师（3-5 年），主要方向：后端");
        assertThat(text).doesNotContain("主力技术栈");
        assertThat(text).contains("- 学习目标：面试冲刺");
        assertThat(text).doesNotContain("三个月");
        assertThat(text).doesNotContain("正在学");
    }

    @Test
    @DisplayName("多选项按定义顺序输出，与答案顺序无关")
    void shouldOutputMultiInDefinitionOrder() {
        Map<String, Object> answers = QuestionnaireDefinitionTest.validAnswers();
        answers.put("presentationForms", java.util.List.of("DIAGRAM", "SOURCE_ANALYSIS"));

        String text = QuestionnaireProfileAssembler.assemble(answers);

        assertThat(text).contains("偏好源码分析、图解步骤");
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `mvn -pl dingRing-domain -am test -Dtest=QuestionnaireProfileAssemblerTest`
Expected: COMPILATION ERROR（`QuestionnaireProfileAssembler` 不存在）

- [ ] **Step 3: 实现 Assembler**

```java
package com.dingring.domain.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 问卷答案 → 画像文本拼装器（纯模板拼接，确定性输出，零 LLM 依赖）。
 * <p>调用前提：答案已通过 {@link QuestionnaireDefinition#validate} 校验。
 * <p>生成结果作为 user_profile 新版本写入，经 ProfileInjectionHook 注入所有群的 Agent。
 */
public final class QuestionnaireProfileAssembler {

    private QuestionnaireProfileAssembler() {
    }

    public static String assemble(Map<String, Object> answers) {
        List<String> lines = new ArrayList<>();

        StringBuilder background = new StringBuilder("- 背景：")
                .append(Career.valueOf(str(answers, QuestionnaireDefinition.KEY_CAREER)).label());
        background.append("，主要方向：")
                .append(joinLabels(Direction.values(), strs(answers, QuestionnaireDefinition.KEY_DIRECTIONS)));
        String techStack = str(answers, QuestionnaireDefinition.KEY_TECH_STACK);
        if (notBlank(techStack)) {
            background.append("，主力技术栈：").append(techStack.trim());
        }
        lines.add(background.toString());

        StringBuilder goal = new StringBuilder("- 学习目标：")
                .append(joinLabels(Motivation.values(), strs(answers, QuestionnaireDefinition.KEY_MOTIVATIONS)));
        String goalDescription = str(answers, QuestionnaireDefinition.KEY_GOAL_DESCRIPTION);
        if (notBlank(goalDescription)) {
            goal.append("，").append(goalDescription.trim());
        }
        lines.add(goal.toString());

        StringBuilder focus = new StringBuilder("- 关注领域：");
        Map<?, ?> areaLevels = (Map<?, ?>) answers.get(QuestionnaireDefinition.KEY_AREA_LEVELS);
        List<String> areaParts = new ArrayList<>();
        for (Object area : (List<?>) answers.get(QuestionnaireDefinition.KEY_FOCUS_AREAS)) {
            areaParts.add(FocusArea.valueOf(String.valueOf(area)).label()
                    + "（" + Level.valueOf(String.valueOf(areaLevels.get(area))).label() + "）");
        }
        focus.append(String.join("、", areaParts));
        String learningNow = str(answers, QuestionnaireDefinition.KEY_LEARNING_NOW);
        if (notBlank(learningNow)) {
            focus.append("，正在学：").append(learningNow.trim());
        }
        lines.add(focus.toString());

        lines.add("- 讨论偏好："
                + DiscussionDepth.valueOf(str(answers, QuestionnaireDefinition.KEY_DISCUSSION_DEPTH)).label()
                + "，" + DiscussionPace.valueOf(str(answers, QuestionnaireDefinition.KEY_DISCUSSION_PACE)).label()
                + "，" + InteractionStyle.valueOf(str(answers, QuestionnaireDefinition.KEY_INTERACTION_STYLE)).label());

        lines.add("- 呈现偏好：偏好"
                + joinLabels(PresentationForm.values(), strs(answers, QuestionnaireDefinition.KEY_PRESENTATION_FORMS))
                + "，术语"
                + TerminologyLanguage.valueOf(str(answers, QuestionnaireDefinition.KEY_TERMINOLOGY_LANGUAGE)).label());

        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strs(Map<String, Object> answers, String key) {
        return (List<String>) answers.get(key);
    }

    private static String str(Map<String, Object> answers, String key) {
        return (String) answers.get(key);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** 按枚举定义顺序输出中文文案，与答案列表顺序无关（保证画像文本稳定） */
    private static String joinLabels(QuestionnaireDefinition.Labelled[] values, List<String> codes) {
        List<String> labels = new ArrayList<>();
        for (QuestionnaireDefinition.Labelled v : values) {
            if (codes.contains(((Enum<?>) v).name())) {
                labels.add(v.label());
            }
        }
        return String.join("、", labels);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl dingRing-domain -am test -Dtest=QuestionnaireProfileAssemblerTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add dingRing-domain/src/main/java/com/dingring/domain/user/QuestionnaireProfileAssembler.java \
        dingRing-domain/src/test/java/com/dingring/domain/user/QuestionnaireProfileAssemblerTest.java
git commit -m "feat: 问卷答案画像文本拼装器"
```

---

### Task 4: 持久层 —— DDL、Mapper、仓储实现（infrastructure）

**Files:**
- Modify: `start/src/main/resources/schema.sql`（在 `user_topic_profile` 建表语句之后追加）
- Create: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/mapper/UserQuestionnaireMapper.java`
- Create: `dingRing-infrastructure/src/main/resources/mapper/UserQuestionnaireMapper.xml`
- Create: `dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/repository/UserQuestionnaireRepositoryImpl.java`

- [ ] **Step 1: schema.sql 追加建表 DDL**

在 `user_topic_profile` 表 DDL 之后（`skill` 表之前）插入：

```sql
-- 用户问卷答案表（画像主数据事实源，版本化写回：重填时标记旧记录失效，插入新记录 version+1）
CREATE TABLE IF NOT EXISTS user_questionnaire (
    id          BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT   NOT NULL COMMENT '用户 ID',
    answers     TEXT     NOT NULL COMMENT '问卷答案(JSON, 题目key→枚举编码)',
    version     INT      NOT NULL DEFAULT 1 COMMENT '填写版本号(每次重填+1)',
    status      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=有效, 0=失效（历史版本）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_status (user_id, status)
);
```

- [ ] **Step 2: 写 Mapper 接口**

```java
package com.dingring.infrastructure.persistence.mapper;

import com.dingring.domain.user.UserQuestionnaire;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户问卷答案表 Mapper。
 * <p>SQL 与结果映射见 resources/mapper/UserQuestionnaireMapper.xml
 */
@Mapper
public interface UserQuestionnaireMapper {

    /** 查询当前有效的问卷答案（status = 1） */
    UserQuestionnaire findValidByUserId(Long userId);

    /** 查询指定用户历史最大版本号（无记录返回 null） */
    Integer selectMaxVersion(Long userId);

    /** 将指定用户当前有效的问卷答案置为失效（status = 0） */
    int expireByUserId(Long userId);

    /** 插入新记录 */
    int insert(UserQuestionnaire questionnaire);
}
```

- [ ] **Step 3: 写 Mapper XML**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<!-- 用户问卷答案表 Mapper：版本化写回，重填时标记旧记录失效，插入新记录 -->
<mapper namespace="com.dingring.infrastructure.persistence.mapper.UserQuestionnaireMapper">

    <resultMap id="questionnaireMap" type="com.dingring.domain.user.UserQuestionnaire">
        <id column="id" property="id"/>
        <result column="answers" property="answers"
                typeHandler="com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler"/>
    </resultMap>

    <sql id="columns">
        id, user_id, answers, version, status, create_time, update_time
    </sql>

    <select id="findValidByUserId" resultMap="questionnaireMap">
        SELECT <include refid="columns"/>
        FROM user_questionnaire
        WHERE user_id = #{userId} AND status = 1
    </select>

    <select id="selectMaxVersion" resultType="java.lang.Integer">
        SELECT MAX(version) FROM user_questionnaire WHERE user_id = #{userId}
    </select>

    <update id="expireByUserId">
        UPDATE user_questionnaire
        SET status = 0, update_time = NOW()
        WHERE user_id = #{userId} AND status = 1
    </update>

    <insert id="insert" parameterType="com.dingring.domain.user.UserQuestionnaire"
            useGeneratedKeys="true" keyProperty="id">
        INSERT INTO user_questionnaire (user_id, answers, version, status, create_time, update_time)
        VALUES (#{userId},
                #{answers, typeHandler=com.dingring.infrastructure.persistence.typehandler.JsonMapTypeHandler},
                #{version}, #{status}, #{createTime}, #{updateTime})
    </insert>

</mapper>
```

- [ ] **Step 4: 写仓储实现**

```java
package com.dingring.infrastructure.persistence.repository;

import com.dingring.domain.user.UserQuestionnaire;
import com.dingring.domain.user.UserQuestionnaireRepository;
import com.dingring.infrastructure.persistence.mapper.UserQuestionnaireMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户问卷答案仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class UserQuestionnaireRepositoryImpl implements UserQuestionnaireRepository {

    private final UserQuestionnaireMapper userQuestionnaireMapper;

    @Override
    public Optional<UserQuestionnaire> findValidByUserId(Long userId) {
        return Optional.ofNullable(userQuestionnaireMapper.findValidByUserId(userId));
    }

    @Override
    @Transactional
    public void saveNewVersion(UserQuestionnaire questionnaire) {
        userQuestionnaireMapper.expireByUserId(questionnaire.getUserId());
        Integer maxVersion = userQuestionnaireMapper.selectMaxVersion(questionnaire.getUserId());
        questionnaire.setVersion(maxVersion == null ? 1 : maxVersion + 1);
        LocalDateTime now = LocalDateTime.now();
        questionnaire.setStatus(UserQuestionnaire.STATUS_ACTIVE);
        questionnaire.setCreateTime(now);
        questionnaire.setUpdateTime(now);
        userQuestionnaireMapper.insert(questionnaire);
    }
}
```

- [ ] **Step 5: 编译验证**

Run: `mvn -pl dingRing-infrastructure -am test-compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add start/src/main/resources/schema.sql \
        dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/mapper/UserQuestionnaireMapper.java \
        dingRing-infrastructure/src/main/resources/mapper/UserQuestionnaireMapper.xml \
        dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/repository/UserQuestionnaireRepositoryImpl.java
git commit -m "feat: 用户问卷答案表与持久化实现（版本化写回）"
```

---

### Task 5: 应用层 —— DTO 与 QuestionnaireAppService（app）

**Files:**
- Create: `dingRing-app/src/main/java/com/dingring/app/dto/request/SubmitQuestionnaireRequest.java`
- Create: `dingRing-app/src/main/java/com/dingring/app/dto/response/QuestionnaireDTO.java`
- Create: `dingRing-app/src/main/java/com/dingring/app/service/QuestionnaireAppService.java`
- Test: `dingRing-app/src/test/java/com/dingring/app/service/QuestionnaireAppServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.dingring.app.service;

import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.common.exception.ParamException;
import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import com.dingring.domain.user.UserQuestionnaire;
import com.dingring.domain.user.UserQuestionnaireRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link QuestionnaireAppService} 单元测试。
 */
@DisplayName("QuestionnaireAppService 问卷应用服务")
class QuestionnaireAppServiceTest {

    private UserQuestionnaireRepository questionnaireRepository;
    private UserProfileRepository userProfileRepository;
    private QuestionnaireAppService service;

    @BeforeEach
    void setUp() {
        questionnaireRepository = mock(UserQuestionnaireRepository.class);
        userProfileRepository = mock(UserProfileRepository.class);
        service = new QuestionnaireAppService(questionnaireRepository, userProfileRepository);
    }

    private Map<String, Object> validAnswers() {
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("career", "MID");
        answers.put("directions", List.of("BACKEND"));
        answers.put("motivations", List.of("INTERVIEW"));
        answers.put("focusAreas", List.of("JVM", "CONCURRENCY"));
        answers.put("areaLevels", Map.of("JVM", "INTERMEDIATE", "CONCURRENCY", "ELEMENTARY"));
        answers.put("discussionDepth", "DEEP_THEORY");
        answers.put("discussionPace", "DIVERGE_THEN_CONVERGE");
        answers.put("interactionStyle", "SOCRATIC");
        answers.put("presentationForms", List.of("SOURCE_ANALYSIS"));
        answers.put("terminologyLanguage", "MIXED");
        return answers;
    }

    @Nested
    @DisplayName("get 问卷查询")
    class Get {

        @Test
        @DisplayName("未填过返回空 answers 与 13 题 schema")
        void shouldReturnEmptyAnswersWhenNeverFilled() {
            when(questionnaireRepository.findValidByUserId(1L)).thenReturn(Optional.empty());

            QuestionnaireDTO dto = service.get();

            assertThat(dto.getAnswers()).isEmpty();
            assertThat(dto.getQuestions()).hasSize(13);
        }

        @Test
        @DisplayName("已填过回显当前有效答案")
        void shouldReturnSavedAnswers() {
            UserQuestionnaire questionnaire = new UserQuestionnaire();
            questionnaire.setAnswers(Map.of("career", "MID"));
            when(questionnaireRepository.findValidByUserId(1L)).thenReturn(Optional.of(questionnaire));

            QuestionnaireDTO dto = service.get();

            assertThat(dto.getAnswers()).containsEntry("career", "MID");
        }
    }

    @Nested
    @DisplayName("submit 提交问卷")
    class Submit {

        @Test
        @DisplayName("合法答案同事务双写事实源与画像版本")
        void shouldWriteBothStores() {
            Map<String, Object> answers = validAnswers();

            service.submit(answers);

            ArgumentCaptor<UserQuestionnaire> qc = ArgumentCaptor.forClass(UserQuestionnaire.class);
            verify(questionnaireRepository).saveNewVersion(qc.capture());
            assertThat(qc.getValue().getUserId()).isEqualTo(1L);
            assertThat(qc.getValue().getAnswers()).isEqualTo(answers);

            ArgumentCaptor<UserProfile> pc = ArgumentCaptor.forClass(UserProfile.class);
            verify(userProfileRepository).saveNewVersion(pc.capture());
            assertThat(pc.getValue().getUserId()).isEqualTo(1L);
            assertThat(pc.getValue().getProfileText()).contains("中级工程师（3-5 年）");
            assertThat(pc.getValue().getProfileText()).contains("JVM（中级）、并发编程（初级）");
        }

        @Test
        @DisplayName("非法答案抛 ParamException 且不落库")
        void shouldRejectInvalidAnswers() {
            Map<String, Object> answers = validAnswers();
            answers.put("career", "CTO");

            assertThatThrownBy(() -> service.submit(answers))
                    .isInstanceOf(ParamException.class);

            verifyNoInteractions(questionnaireRepository, userProfileRepository);
        }

        @Test
        @DisplayName("answers 为 null 抛 ParamException 且不落库")
        void shouldRejectNullAnswers() {
            assertThatThrownBy(() -> service.submit(null))
                    .isInstanceOf(ParamException.class);

            verifyNoInteractions(questionnaireRepository, userProfileRepository);
        }
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `mvn -pl dingRing-app -am test -Dtest=QuestionnaireAppServiceTest`
Expected: COMPILATION ERROR（`QuestionnaireAppService` 不存在）

- [ ] **Step 3: 写请求/响应 DTO**

```java
package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 提交问卷答案请求。
 */
@Data
public class SubmitQuestionnaireRequest {

    @NotNull(message = "answers 不能为空")
    private Map<String, Object> answers;
}
```

```java
package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 问卷 schema + 当前有效答案（GET /api/questionnaire）。
 */
@Data
@Builder
public class QuestionnaireDTO {

    /** 题目 schema（有序） */
    private List<QuestionDTO> questions;
    /** 当前有效答案（未填过为空 Map） */
    private Map<String, Object> answers;

    @Data
    @Builder
    public static class QuestionDTO {
        private String key;
        private String label;
        private String dimension;
        /** SINGLE / MULTI / TEXT / AREA_LEVELS */
        private String type;
        private Boolean required;
        private List<OptionDTO> options;
    }

    @Data
    @Builder
    public static class OptionDTO {
        private String value;
        private String label;
    }
}
```

- [ ] **Step 4: 实现 QuestionnaireAppService**

```java
package com.dingring.app.service;

import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.common.exception.ParamException;
import com.dingring.domain.user.QuestionnaireDefinition;
import com.dingring.domain.user.QuestionnaireProfileAssembler;
import com.dingring.domain.user.UserProfile;
import com.dingring.domain.user.UserProfileRepository;
import com.dingring.domain.user.UserQuestionnaire;
import com.dingring.domain.user.UserQuestionnaireRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 用户问卷应用服务：画像主数据的读写编排。
 * <p>提交即双写：答案事实源（user_questionnaire）+ 画像消费视图（user_profile），
 * 同一事务保证不出现"有答案无画像"的中间态；重填视为用户主动重置画像基线。
 */
@Service
@RequiredArgsConstructor
public class QuestionnaireAppService {

    private final UserQuestionnaireRepository questionnaireRepository;
    private final UserProfileRepository userProfileRepository;

    /** 问卷 schema + 当前有效答案（未填过返回空 answers） */
    public QuestionnaireDTO get() {
        Map<String, Object> answers = questionnaireRepository
                .findValidByUserId(GroupAppService.DEFAULT_USER_ID)
                .map(UserQuestionnaire::getAnswers)
                .orElseGet(Map::of);
        return QuestionnaireDTO.builder()
                .questions(toQuestionDTOs())
                .answers(answers)
                .build();
    }

    /**
     * 提交问卷：校验 → 事务内双写。
     *
     * @throws ParamException 答案缺失必填项、取值非法或联动不一致
     */
    @Transactional
    public void submit(Map<String, Object> answers) {
        if (answers == null) {
            throw new ParamException("问卷答案不能为空");
        }
        QuestionnaireDefinition.validate(answers);

        UserQuestionnaire questionnaire = new UserQuestionnaire();
        questionnaire.setUserId(GroupAppService.DEFAULT_USER_ID);
        questionnaire.setAnswers(answers);
        questionnaireRepository.saveNewVersion(questionnaire);

        UserProfile profile = new UserProfile();
        profile.setUserId(GroupAppService.DEFAULT_USER_ID);
        profile.setProfileText(QuestionnaireProfileAssembler.assemble(answers));
        userProfileRepository.saveNewVersion(profile);
    }

    private List<QuestionnaireDTO.QuestionDTO> toQuestionDTOs() {
        return QuestionnaireDefinition.questions().stream()
                .map(q -> QuestionnaireDTO.QuestionDTO.builder()
                        .key(q.key())
                        .label(q.label())
                        .dimension(q.dimension())
                        .type(q.type().name())
                        .required(q.required())
                        .options(q.options().stream()
                                .map(o -> QuestionnaireDTO.OptionDTO.builder()
                                        .value(o.value())
                                        .label(o.label())
                                        .build())
                                .toList())
                        .build())
                .toList();
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -pl dingRing-app -am test -Dtest=QuestionnaireAppServiceTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add dingRing-app/src/main/java/com/dingring/app/dto/request/SubmitQuestionnaireRequest.java \
        dingRing-app/src/main/java/com/dingring/app/dto/response/QuestionnaireDTO.java \
        dingRing-app/src/main/java/com/dingring/app/service/QuestionnaireAppService.java \
        dingRing-app/src/test/java/com/dingring/app/service/QuestionnaireAppServiceTest.java
git commit -m "feat: 问卷应用服务（校验 + 事务内双写事实源与画像）"
```

---

### Task 6: 接入层 —— QuestionnaireController（adapter）

**Files:**
- Create: `dingRing-adapter/src/main/java/com/dingring/adapter/rest/QuestionnaireController.java`
- Test: `dingRing-adapter/src/test/java/com/dingring/adapter/rest/QuestionnaireControllerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.dingring.adapter.rest;

import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.app.service.QuestionnaireAppService;
import com.dingring.common.exception.ParamException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link QuestionnaireController} REST API 单元测试。
 */
@DisplayName("QuestionnaireController 问卷 REST API")
@ExtendWith(MockitoExtension.class)
class QuestionnaireControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private QuestionnaireAppService questionnaireAppService;

    @InjectMocks
    private QuestionnaireController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Nested
    @DisplayName("GET /api/questionnaire")
    class Get {

        @Test
        @DisplayName("返回题目 schema 与当前答案")
        void shouldReturnSchemaAndAnswers() throws Exception {
            QuestionnaireDTO dto = QuestionnaireDTO.builder()
                    .questions(List.of(QuestionnaireDTO.QuestionDTO.builder()
                            .key("career").label("你的职业身份").dimension("BACKGROUND")
                            .type("SINGLE").required(true)
                            .options(List.of(QuestionnaireDTO.OptionDTO.builder()
                                    .value("STUDENT").label("在校学生").build()))
                            .build()))
                    .answers(Map.of("career", "MID"))
                    .build();
            when(questionnaireAppService.get()).thenReturn(dto);

            mockMvc.perform(get("/api/questionnaire"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.questions[0].key").value("career"))
                    .andExpect(jsonPath("$.data.questions[0].required").value(true))
                    .andExpect(jsonPath("$.data.answers.career").value("MID"));
        }
    }

    @Nested
    @DisplayName("POST /api/questionnaire")
    class Submit {

        @Test
        @DisplayName("合法提交返回 200")
        void shouldSubmit() throws Exception {
            mockMvc.perform(post("/api/questionnaire")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("answers", Map.of("career", "MID")))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("answers 缺失返回 400 + PARAM_INVALID")
        void shouldRejectMissingAnswers() throws Exception {
            mockMvc.perform(post("/api/questionnaire")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"));
        }

        @Test
        @DisplayName("非法答案返回 400 + PARAM_INVALID")
        void shouldRejectInvalidAnswers() throws Exception {
            doThrow(new ParamException("取值不在选项内：你的职业身份"))
                    .when(questionnaireAppService).submit(any());

            mockMvc.perform(post("/api/questionnaire")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("answers", Map.of("career", "CTO")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("PARAM_INVALID"))
                    .andExpect(jsonPath("$.message").value("取值不在选项内：你的职业身份"));
        }
    }
}
```

- [ ] **Step 2: 运行测试确认编译失败**

Run: `mvn -pl dingRing-adapter -am test -Dtest=QuestionnaireControllerTest`
Expected: COMPILATION ERROR（`QuestionnaireController` 不存在）

- [ ] **Step 3: 实现 Controller**

```java
package com.dingring.adapter.rest;

import com.dingring.app.dto.request.SubmitQuestionnaireRequest;
import com.dingring.app.dto.response.QuestionnaireDTO;
import com.dingring.app.service.QuestionnaireAppService;
import com.dingring.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户问卷 REST API（画像主数据采集）。
 */
@RestController
@RequestMapping("/api/questionnaire")
@RequiredArgsConstructor
public class QuestionnaireController {

    private final QuestionnaireAppService questionnaireAppService;

    /** 问卷 schema + 当前有效答案（未填过返回空 answers） */
    @GetMapping
    public ApiResponse<QuestionnaireDTO> get() {
        return ApiResponse.ok(questionnaireAppService.get());
    }

    /** 提交问卷答案：事实源与画像同事务写入，画像即时生效 */
    @PostMapping
    public ApiResponse<Void> submit(@Validated @RequestBody SubmitQuestionnaireRequest request) {
        questionnaireAppService.submit(request.getAnswers());
        return ApiResponse.ok();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -pl dingRing-adapter -am test -Dtest=QuestionnaireControllerTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add dingRing-adapter/src/main/java/com/dingring/adapter/rest/QuestionnaireController.java \
        dingRing-adapter/src/test/java/com/dingring/adapter/rest/QuestionnaireControllerTest.java
git commit -m "feat: 问卷 REST 接口（GET schema / POST 提交）"
```

---

### Task 7: 前端 —— 个人画像问卷页（frontend）

**Files:**
- Modify: `frontend/src/api.ts`（文件末尾追加）
- Create: `frontend/src/pages/Profile/index.tsx`
- Create: `frontend/src/pages/Profile/style.css`
- Modify: `frontend/src/App.tsx`
- Modify: `frontend/src/components/Sidebar.tsx`

- [ ] **Step 1: api.ts 追加问卷 API 封装**

```typescript
// ==================== 用户问卷（画像主数据） ====================

/** 问卷选项 */
export interface QuestionOption {
  value: string;
  label: string;
}

/** 问卷题目（与后端 QuestionnaireDTO.QuestionDTO 对齐） */
export interface QuestionDef {
  key: string;
  label: string;
  dimension: string;
  type: 'SINGLE' | 'MULTI' | 'TEXT' | 'AREA_LEVELS';
  required: boolean;
  options: QuestionOption[];
}

/** 问卷 schema + 当前有效答案（GET /api/questionnaire） */
export interface QuestionnaireDTO {
  questions: QuestionDef[];
  answers: Record<string, unknown>;
}

export const QuestionnaireApi = {
  get: () => API.get<QuestionnaireDTO>('/api/questionnaire'),
  submit: (answers: Record<string, unknown>) =>
    API.post<void>('/api/questionnaire', { answers }),
};
```

- [ ] **Step 2: 写问卷页组件**

```typescript
import { useCallback, useEffect, useMemo, useState } from 'react';
import Sidebar from '../../components/Sidebar';
import { toast } from '../../components/Toast';
import { QuestionnaireApi } from '../../api';
import type { QuestionDef } from '../../api';
import './style.css';

/** 分步向导：每步覆盖的维度（共 4 步走完 6 维度） */
const STEPS: Array<{ title: string; dimensions: string[] }> = [
  { title: '背景与目标', dimensions: ['BACKGROUND', 'GOAL'] },
  { title: '关注领域与水平', dimensions: ['FOCUS'] },
  { title: '讨论风格', dimensions: ['STYLE'] },
  { title: '呈现偏好', dimensions: ['PRESENTATION'] },
];

/** 领域被勾选但未自评时的默认水平 */
const DEFAULT_LEVEL = 'BEGINNER';

/** focusAreas 题目 key（选择变化需联动维护 areaLevels） */
const KEY_FOCUS_AREAS = 'focusAreas';

type Answers = Record<string, unknown>;

interface QuestionBlockProps {
  question: QuestionDef;
  questions: QuestionDef[];
  answers: Answers;
  setValue: (key: string, value: unknown) => void;
  toggleMulti: (key: string, value: string) => void;
}

/** 单题渲染：按 type 分发为选项 chips / 文本输入 / 领域水平联动行 */
function QuestionBlock({ question: q, questions, answers, setValue, toggleMulti }: QuestionBlockProps) {
  const focusQuestion = questions.find(x => x.key === KEY_FOCUS_AREAS);
  const areaLabel = (v: string) =>
    focusQuestion?.options.find(o => o.value === v)?.label ?? v;
  const multiValue = (answers[q.key] as string[] | undefined) ?? [];
  const levels = (answers[q.key] as Record<string, string> | undefined) ?? {};

  return (
    <div className="q-item">
      <div className="q-item__label">
        {q.label}
        {!q.required && <span className="q-item__optional">（可选）</span>}
      </div>

      {q.type === 'SINGLE' && (
        <div className="q-chips">
          {q.options.map(o => (
            <button key={o.value} type="button"
                    className={`q-chip${answers[q.key] === o.value ? ' is-selected' : ''}`}
                    onClick={() => setValue(q.key, o.value)}>
              {o.label}
            </button>
          ))}
        </div>
      )}

      {q.type === 'MULTI' && (
        <div className="q-chips">
          {q.options.map(o => (
            <button key={o.value} type="button"
                    className={`q-chip${multiValue.includes(o.value) ? ' is-selected' : ''}`}
                    onClick={() => toggleMulti(q.key, o.value)}>
              {o.label}
            </button>
          ))}
        </div>
      )}

      {q.type === 'TEXT' && (
        <input
          className="q-input"
          type="text"
          value={(answers[q.key] as string | undefined) ?? ''}
          placeholder={q.key === 'techStack'
            ? '如：Java / Spring Boot / MySQL'
            : q.key === 'goalDescription'
              ? '如：三个月搞定 JVM 调优'
              : '如：Netty 源码'}
          onChange={e => setValue(q.key, e.target.value)}
        />
      )}

      {q.type === 'AREA_LEVELS' && (
        <div className="q-levels">
          {multiValueOfFocus(answers).map(area => (
            <div key={area} className="q-level-row">
              <span className="q-level-name">{areaLabel(area)}</span>
              <div className="q-chips">
                {q.options.map(o => (
                  <button key={o.value} type="button"
                          className={`q-chip q-chip--sm${levels[area] === o.value ? ' is-selected' : ''}`}
                          onClick={() => setValue(q.key, { ...levels, [area]: o.value })}>
                    {o.label}
                  </button>
                ))}
              </div>
            </div>
          ))}
          {!multiValueOfFocus(answers).length && (
            <div className="q-level-empty">先在上一题选择关注领域</div>
          )}
        </div>
      )}
    </div>
  );
}

function multiValueOfFocus(answers: Answers): string[] {
  return (answers[KEY_FOCUS_AREAS] as string[] | undefined) ?? [];
}

export default function ProfilePage() {
  const [questions, setQuestions] = useState<QuestionDef[]>([]);
  const [answers, setAnswers] = useState<Answers>({});
  const [step, setStep] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);

  // 加载 schema + 已存答案；未填过的必答题取首项为默认值
  useEffect(() => {
    (async () => {
      try {
        const dto = await QuestionnaireApi.get();
        setQuestions(dto.questions);
        const init: Answers = {};
        for (const q of dto.questions) {
          if (dto.answers[q.key] !== undefined) {
            init[q.key] = dto.answers[q.key];
          } else if (q.type === 'SINGLE' && q.required && q.options.length > 0) {
            init[q.key] = q.options[0].value;
          } else if (q.type === 'MULTI' && q.required && q.options.length > 0) {
            init[q.key] = [q.options[0].value];
          } else if (q.type === 'AREA_LEVELS') {
            init[q.key] = {};
          }
        }
        setAnswers(init);
      } catch (e: any) {
        toast(e.message || '加载问卷失败', 'error');
      } finally {
        setLoaded(true);
      }
    })();
  }, []);

  const stepQuestions = useMemo(
    () => questions.filter(q => STEPS[step]?.dimensions.includes(q.dimension)),
    [questions, step],
  );

  const setValue = useCallback((key: string, value: unknown) => {
    setAnswers(prev => ({ ...prev, [key]: value }));
  }, []);

  /** 多选切换；focusAreas 变化时联动补齐/裁剪 areaLevels，保证后端联动校验通过 */
  const toggleMulti = useCallback((key: string, value: string) => {
    setAnswers(prev => {
      const cur = (prev[key] as string[] | undefined) ?? [];
      const next = cur.includes(value) ? cur.filter(x => x !== value) : [...cur, value];
      if (key !== KEY_FOCUS_AREAS) {
        return { ...prev, [key]: next };
      }
      const levels = { ...((prev.areaLevels as Record<string, string> | undefined) ?? {}) };
      for (const v of next) if (!levels[v]) levels[v] = DEFAULT_LEVEL;
      for (const k of Object.keys(levels)) if (!next.includes(k)) delete levels[k];
      return { ...prev, [key]: next, areaLevels: levels };
    });
  }, []);

  /** 步内校验：必答单选有值、必答多选非空、领域水平自评完整 */
  const validateStep = useCallback((): boolean => {
    for (const q of stepQuestions) {
      if (!q.required) continue;
      const value = answers[q.key];
      if (q.type === 'SINGLE' && !value) {
        toast(`请选择：${q.label}`, 'error');
        return false;
      }
      if (q.type === 'MULTI' && (!Array.isArray(value) || value.length === 0)) {
        toast(`请至少选择一项：${q.label}`, 'error');
        return false;
      }
      if (q.type === 'AREA_LEVELS') {
        const areas = (answers[KEY_FOCUS_AREAS] as string[] | undefined) ?? [];
        const levels = (value as Record<string, string> | undefined) ?? {};
        if (areas.some(a => !levels[a])) {
          toast('请完成各关注领域的水平自评', 'error');
          return false;
        }
      }
    }
    return true;
  }, [stepQuestions, answers]);

  const next = () => {
    if (validateStep()) setStep(s => Math.min(s + 1, STEPS.length - 1));
  };
  const prev = () => setStep(s => Math.max(s - 1, 0));

  const submit = useCallback(async () => {
    if (!validateStep()) return;
    setSaving(true);
    try {
      await QuestionnaireApi.submit(answers);
      toast('画像已更新，AI 同事下次发言即可感知', 'success');
    } catch (e: any) {
      toast(e.message || '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  }, [answers, validateStep]);

  if (!loaded) return null;

  return (
    <div className="app-shell">
      <Sidebar />

      <section className="page page--editorial">
        <header className="page__head">
          <div className="page__head-meta">
            <span className="page__eyebrow">Profile</span>
            <span className="page__head-rule" aria-hidden />
          </div>
          <div className="page__head-main">
            <h1 className="page__title-serif">个人画像</h1>
            <p className="page__lead">
              填写问卷生成你的学习画像，AI 同事将据此调整讨论深度与讲解方式；可随时重填，重填即重置画像基线
            </p>
          </div>
        </header>

        <div className="page__body">
          <ol className="profile-steps">
            {STEPS.map((s, i) => (
              <li key={s.title}
                  className={`profile-steps__item${i === step ? ' is-active' : ''}${i < step ? ' is-done' : ''}`}>
                <span className="profile-steps__index">{i + 1}</span>
                <span className="profile-steps__name">{s.title}</span>
              </li>
            ))}
          </ol>

          <div className="profile-form">
            {stepQuestions.map(q => (
              <QuestionBlock
                key={q.key}
                question={q}
                questions={questions}
                answers={answers}
                setValue={setValue}
                toggleMulti={toggleMulti}
              />
            ))}
          </div>

          <div className="profile-actions">
            <button type="button" className="btn" onClick={prev} disabled={step === 0}>
              上一步
            </button>
            {step < STEPS.length - 1 ? (
              <button type="button" className="btn btn--brand" onClick={next}>下一步</button>
            ) : (
              <button type="button" className="btn btn--brand" onClick={submit} disabled={saving}>
                {saving ? '保存中…' : '保存画像'}
              </button>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
```

- [ ] **Step 3: 写问卷页样式**

```css
/* Profile 问卷页样式（editorial 风格，复用全局 CSS 变量） */

/* ---- 分步指示器 ---- */
.profile-steps {
  display: flex;
  gap: 8px;
  margin: 0 0 20px;
  padding: 0;
  list-style: none;
}
.profile-steps__item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 1px solid var(--border-neutral-l1);
  border-radius: 10px;
  background: var(--bg-base-secondary);
  color: var(--text-tertiary);
  transition: border-color .15s, color .15s;
}
.profile-steps__item.is-active {
  border-color: var(--border-brand);
  color: var(--text-default);
}
.profile-steps__item.is-done { color: var(--text-secondary); }
.profile-steps__index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  font-family: "JetBrains Mono", monospace;
  font-size: 11px;
  background: var(--bg-overlay-l2);
}
.profile-steps__item.is-active .profile-steps__index {
  background: var(--border-brand);
  color: var(--bg-base-secondary);
}
.profile-steps__name { font-size: 13px; }

/* ---- 题目区块 ---- */
.profile-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 20px;
  border: 1px solid var(--border-neutral-l1);
  border-radius: 12px;
  background: var(--bg-base-secondary);
}
.q-item__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-default);
  margin-bottom: 10px;
}
.q-item__optional {
  margin-left: 4px;
  font-weight: 400;
  font-size: 11px;
  color: var(--text-tertiary);
}

/* ---- 选项 chips ---- */
.q-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.q-chip {
  padding: 6px 14px;
  border: 1px solid var(--border-neutral-l2);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 13px;
  cursor: pointer;
  transition: border-color .15s, color .15s, background .15s;
}
.q-chip:hover { border-color: var(--border-brand); color: var(--text-default); }
.q-chip.is-selected {
  border-color: var(--border-brand);
  background: var(--bg-overlay-l1);
  color: var(--text-brand);
  font-weight: 600;
}
.q-chip--sm { padding: 4px 10px; font-size: 12px; }

/* ---- 文本输入 ---- */
.q-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border-neutral-l2);
  border-radius: 8px;
  background: var(--bg-base-tertiary);
  color: var(--text-default);
  font-size: 13px;
}
.q-input:focus { outline: none; border-color: var(--border-brand); }

/* ---- 领域水平联动 ---- */
.q-levels { display: flex; flex-direction: column; gap: 10px; }
.q-level-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.q-level-name {
  min-width: 96px;
  font-size: 13px;
  color: var(--text-secondary);
}
.q-level-empty {
  font-size: 12px;
  color: var(--text-tertiary);
  padding: 8px 0;
}

/* ---- 底部操作 ---- */
.profile-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 20px;
}
```

- [ ] **Step 4: App.tsx 注册路由**

懒加载声明区追加（`const DashboardPage = ...` 之后）：

```typescript
const ProfilePage = lazy(() => import('./pages/Profile'));
```

`<Routes>` 内 `<Route path="/kb" ...>` 之后追加：

```tsx
<Route path="/profile" element={<ProfilePage />} />
```

- [ ] **Step 5: Sidebar.tsx 新增入口**

图标区追加（`IconTool` 定义之后）：

```typescript
const IconUser = () => (
  <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="5" r="2.8" stroke="currentColor" strokeWidth="1.2"/><path d="M2.8 13.5c.9-2.4 2.8-3.7 5.2-3.7s4.3 1.3 5.2 3.7" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
);
```

导航区 `<NavLink to="/skills" ...>` 之后追加：

```tsx
<NavLink to="/profile" className={({ isActive }) => `sidebar__nav-item${isActive ? ' sidebar__nav-item--active' : ''}`}>
  <span className="sidebar__nav-icon"><IconUser /></span>
  <span className="sidebar__nav-text">个人画像</span>
</NavLink>
```

- [ ] **Step 6: 构建与 lint 验证**

Run: `cd frontend && npm run lint && npm run build`
Expected: oxlint 无错误；`tsc -b && vite build` 成功产出

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api.ts frontend/src/pages/Profile/index.tsx frontend/src/pages/Profile/style.css \
        frontend/src/App.tsx frontend/src/components/Sidebar.tsx
git commit -m "feat: 个人画像问卷页（4 步向导，按 schema 动态渲染）"
```

- [ ] **Step 8: 同步前端构建产物（仓库惯例）**

Run: `cd frontend && npm run build`（产物输出至 `start/src/main/resources/static`）

```bash
git add start/src/main/resources/static
git commit -m "chore: 同步前端构建产物（个人画像页）"
```

---

### Task 8: 收尾 —— 全量测试、wiki 文档、端到端验证

**Files:**
- Modify: `docs/wiki/data-model.md`
- Modify: `docs/wiki/rest-api.md`

- [ ] **Step 1: 后端全量测试**

Run: `mvn test`
Expected: 全部模块 BUILD SUCCESS，新增 21 个用例（Definition 9 + Assembler 3 + AppService 5 + Controller 4）通过，无既有用例回归

- [ ] **Step 2: data-model.md 补充新表**

表清单表格（`user_topic_profile` 行之后）追加一行：

```markdown
| 12 | `user_questionnaire` | ring_chat | 用户问卷答案（画像主数据，版本化） | `status`：1 有效 / 0 历史；`version` 每次重填 +1 |
```

（原 12、13 行的序号顺延为 13、14）

ER 图 `user ||--o{ user_topic_profile : "user_id"` 之后追加一行：

```mermaid
    user ||--o{ user_questionnaire : "user_id（版本化）"
```

ER 图实体块 `user_profile { ... }` 之后追加：

```mermaid
    user_questionnaire {
        bigint id PK
        bigint user_id
        text answers "JSON 题目key→枚举编码"
        int version
        tinyint status "1有效 0历史"
    }
```

- [ ] **Step 3: rest-api.md 补充新接口**

Controller 总表（「观测日志」行之前）追加一行：

```markdown
| 用户问卷 | `QuestionnaireController` | `/api/questionnaire` | 2 | 否 |
```

（端点总数 43 → 45，Controller 数 9 → 10）

文档末尾「## 附：本文档对应的源码位置」之前追加新章节：

```markdown
## 11. 用户问卷（QuestionnaireController）

画像主数据采集：答案存 `user_questionnaire`（事实源），并拼接画像文本写入 `user_profile` 新版本（消费视图），经 `ProfileInjectionHook` 注入所有群的 Agent。

| 方法 | 路径 | 说明 | 路径参数 | 请求体 | 响应 |
| --- | --- | --- | --- | --- | --- |
| GET | `/api/questionnaire` | 题目 schema + 当前有效答案（未填过返回空 `answers`） | 无 | 无 | `QuestionnaireDTO` |
| POST | `/api/questionnaire` | 提交问卷答案（事实源与画像同事务写入） | 无 | `SubmitQuestionnaireRequest` | 无 |

### 11.1 响应 `QuestionnaireDTO`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `questions` | `QuestionDTO[]` | 题目 schema（有序，13 题） |
| `answers` | `object` | 当前有效答案（题目 key → 枚举编码 / 文本） |

`QuestionDTO`：`key`（题目键）、`label`（题干）、`dimension`（BACKGROUND/GOAL/FOCUS/STYLE/PRESENTATION）、`type`（SINGLE/MULTI/TEXT/AREA_LEVELS）、`required`、`options[]`（`{value, label}`）。

### 11.2 请求体 `SubmitQuestionnaireRequest`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `answers` | `object` | 是 | 题目 key → 答案；必答项缺失、取值非法、`areaLevels` 与 `focusAreas` 联动不一致均返回 400 `PARAM_INVALID` |
```

- [ ] **Step 4: Commit**

```bash
git add docs/wiki/data-model.md docs/wiki/rest-api.md
git commit -m "docs: 补充用户问卷表与接口的 wiki 文档"
```

- [ ] **Step 5: 手工端到端验证**

1. 启动依赖（MySQL/PostgreSQL）与应用：`mvn -pl start spring-boot:run`——启动日志无报错，`user_questionnaire` 表自动创建（`schema.sql` 为 `CREATE TABLE IF NOT EXISTS`）；
2. 启动前端 `cd frontend && npm run dev`，打开 `/profile`：
   - 4 步向导正常渲染 13 题；第 2 步勾选领域后水平自评行联动出现；
   - 清空一个必答多选点「下一步」→ 出现 toast 错误提示；
3. 填写并保存 → toast「画像已更新」；
4. SQL 验证双写（MySQL `ring_chat` 库）：
   ```sql
   SELECT id, user_id, version, status, answers FROM user_questionnaire WHERE user_id = 1 ORDER BY id DESC;
   SELECT id, profile_text, status FROM user_profile WHERE user_id = 1 ORDER BY id DESC;
   ```
   预期：两表各有一条 `status=1` 新记录，`user_profile.profile_text` 为 5 行问卷拼接画像；
5. 重填一次保存 → 两表旧记录 `status=0`、新记录 `version=2`；
6. 在群聊发一条消息触发 Agent 发言 → Agent 回应体现出画像信息（如按术语语言偏好、讨论深度作答），可通过 Dashboard 观测 Trace 中注入的画像内容确认。

- [ ] **Step 6: 最终提交（如有验证期修正）**

```bash
git add -A
git commit -m "fix: 问卷功能端到端验证修正"
```

（无修正则跳过）

---

## Self-Review 记录

- **Spec 覆盖**：spec §2 问卷 13 题 → Task 1；§3.1 DDL → Task 4；§3.2 领域组件（实体/端口/定义/拼装器/AppService/Controller/RepositoryImpl）→ Task 1/2/3/4/5/6；§3.3 API → Task 5/6；§3.4 数据流双写 → Task 5 `@Transactional submit`；§3.5 融合规则 → Task 5 注释 + Task 8 手工验证第 5 步；§4 前端 → Task 7；§5 错误处理（校验/事务回滚）→ Task 1/5/6；§6 测试 → Task 1/3/5/6（Repository 集成测试按偏差说明以手工验证替代）；§7 范围外未引入。无遗漏。
- **占位符扫描**：所有代码步骤含完整代码，无 TBD/TODO。
- **类型一致性**：`QuestionDef(key, label, dimension, type, required, options)` 五段签名在 Task 1（定义）、Task 5（DTO 映射 `q.required()`）、Task 7（TS `QuestionDef`）三处一致；`areaLevels` 契约（Map<领域枚举, 水平枚举>、key 集合等于 focusAreas）在 Task 1（校验）、Task 3（拼接）、Task 5（透传）、Task 7（联动维护）四处一致；`DEFAULT_USER_ID = 1L` 复用自 `GroupAppService`。
