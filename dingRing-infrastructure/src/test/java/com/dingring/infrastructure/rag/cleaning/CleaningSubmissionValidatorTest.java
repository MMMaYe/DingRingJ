package com.dingring.infrastructure.rag.cleaning;

import com.dingring.domain.knowledgebase.CleaningSubmission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CleaningSubmissionValidator} 协议校验单测（3.2 v1）。
 * <p>协议是内置执行方与外部 Agent（MCP）的共用契约，此处穷举非法输入分支：
 * 解析失败返回 null（调用方决定拒绝原因），超上限抛异常（明确的大小问题）。
 */
@DisplayName("清洗提交校验器：JSON schema/非空/大小上限")
class CleaningSubmissionValidatorTest {

    private final CleaningSubmissionValidator validator = new CleaningSubmissionValidator(1000);

    @Test
    @DisplayName("合法提交：标题与全文均解析")
    void shouldParseValidSubmission() {
        CleaningSubmission submission = validator.parseAndValidate(
                "{\"documentTitle\":\"设计文档\",\"cleanMarkdown\":\"# 设计文档\\n正文\"}");

        assertThat(submission).isNotNull();
        assertThat(submission.documentTitle()).isEqualTo("设计文档");
        assertThat(submission.cleanMarkdown()).isEqualTo("# 设计文档\n正文");
    }

    @Test
    @DisplayName("documentTitle 可选：缺失时为 null 但 cleanMarkdown 必填")
    void shouldAllowMissingTitle() {
        CleaningSubmission submission = validator.parseAndValidate(
                "{\"cleanMarkdown\":\"正文\"}");

        assertThat(submission).isNotNull();
        assertThat(submission.documentTitle()).isNull();
        assertThat(submission.cleanMarkdown()).isEqualTo("正文");
    }

    @Test
    @DisplayName("null/空白 JSON：返回 null")
    void shouldRejectBlankJson() {
        assertThat(validator.parseAndValidate(null)).isNull();
        assertThat(validator.parseAndValidate("")).isNull();
        assertThat(validator.parseAndValidate("   ")).isNull();
    }

    @Test
    @DisplayName("非法 JSON：返回 null 而非抛异常")
    void shouldRejectMalformedJson() {
        assertThat(validator.parseAndValidate("not a json")).isNull();
        assertThat(validator.parseAndValidate("{\"cleanMarkdown\": ")).isNull();
        assertThat(validator.parseAndValidate("[1,2,3]")).isNull();
    }

    @Test
    @DisplayName("cleanMarkdown 缺失/空白：返回 null")
    void shouldRejectMissingCleanMarkdown() {
        assertThat(validator.parseAndValidate("{}")).isNull();
        assertThat(validator.parseAndValidate("{\"cleanMarkdown\":\"\"}")).isNull();
        assertThat(validator.parseAndValidate("{\"cleanMarkdown\":\"   \"}")).isNull();
        assertThat(validator.parseAndValidate("{\"documentTitle\":\"仅标题\"}")).isNull();
    }

    @Test
    @DisplayName("超过大小上限：抛 IllegalArgumentException（与解析失败区分）")
    void shouldThrowWhenExceedsMaxChars() {
        String oversized = "x".repeat(1001);
        String json = "{\"cleanMarkdown\":\"" + oversized + "\"}";

        assertThatThrownBy(() -> validator.parseAndValidate(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超过大小上限");
    }

    @Test
    @DisplayName("恰好达到上限：放行（边界含等于）")
    void shouldAllowExactlyMaxChars() {
        String bounded = "x".repeat(1000);
        CleaningSubmission submission = validator.parseAndValidate(
                "{\"cleanMarkdown\":\"" + bounded + "\"}");

        assertThat(submission).isNotNull();
        assertThat(submission.cleanMarkdown()).hasSize(1000);
    }

    @Test
    @DisplayName("大小上限按字符计：多字节中文不放大判定（UTF-8 3 字节仍按 1 字符）")
    void shouldCountByCharsNotBytes() {
        // 1000 个中文 = 3000 UTF-8 字节，但按字符计 = 1000 不超限
        String cjk = "中".repeat(1000);
        CleaningSubmission submission = validator.parseAndValidate(
                "{\"cleanMarkdown\":\"" + cjk + "\"}");

        assertThat(submission).isNotNull();
    }
}
