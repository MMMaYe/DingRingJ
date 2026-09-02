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
