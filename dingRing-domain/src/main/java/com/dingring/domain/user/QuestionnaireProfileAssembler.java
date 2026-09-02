package com.dingring.domain.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 嵌套枚举经静态导入后以简单名引用（题库定义与拼装器同包但枚举为 QuestionnaireDefinition 嵌套类型）
import static com.dingring.domain.user.QuestionnaireDefinition.Career;
import static com.dingring.domain.user.QuestionnaireDefinition.Direction;
import static com.dingring.domain.user.QuestionnaireDefinition.Motivation;
import static com.dingring.domain.user.QuestionnaireDefinition.FocusArea;
import static com.dingring.domain.user.QuestionnaireDefinition.Level;
import static com.dingring.domain.user.QuestionnaireDefinition.DiscussionDepth;
import static com.dingring.domain.user.QuestionnaireDefinition.DiscussionPace;
import static com.dingring.domain.user.QuestionnaireDefinition.InteractionStyle;
import static com.dingring.domain.user.QuestionnaireDefinition.PresentationForm;
import static com.dingring.domain.user.QuestionnaireDefinition.TerminologyLanguage;

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
