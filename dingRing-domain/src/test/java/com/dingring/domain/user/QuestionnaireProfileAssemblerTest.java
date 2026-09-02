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
