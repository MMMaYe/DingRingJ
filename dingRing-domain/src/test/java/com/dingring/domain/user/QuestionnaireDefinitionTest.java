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
