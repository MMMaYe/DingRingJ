package com.dingring.infrastructure.rag.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownStructureParserTest {

    private final MarkdownStructureParser parser = new MarkdownStructureParser();

    @Test
    void parsesHeadingsAndProtectedStructuresWithSourceLocations() {
        String markdown = """
                ---
                owner: dingring
                ---
                # 活动管理
                介绍段落。

                ## 独占模式
                1. 创建活动
                2. 保存配置

                | 配置 | 值 |
                | --- | --- |
                | mode | exclusive |

                ```java
                class ActivityService {}
                ```
                """;

        List<MarkdownBlock> blocks = parser.parse(markdown, "fallback.md");

        assertThat(blocks).extracting(MarkdownBlock::type)
                .containsExactly(BlockType.FRONT_MATTER, BlockType.HEADING, BlockType.PARAGRAPH,
                        BlockType.HEADING, BlockType.LIST, BlockType.TABLE, BlockType.CODE_FENCE);
        assertThat(blocks.get(4).headingPath()).containsExactly("fallback.md", "活动管理", "独占模式");
        assertThat(blocks.get(5).attributes()).containsEntry("headerRows", 2);
        assertThat(blocks.get(6).language()).isEqualTo("java");
        assertThat(markdown.substring(blocks.get(6).startOffset(), blocks.get(6).endOffset()))
                .isEqualTo(blocks.get(6).text());
    }

    @Test
    void doesNotInterpretMarkdownInsideCodeFence() {
        String markdown = """
                # 文档
                ```text
                # 不是标题
                | 不是 | 表格 |
                | --- | --- |
                ```
                后续正文
                """;

        List<MarkdownBlock> blocks = parser.parse(markdown, "doc.md");

        assertThat(blocks).extracting(MarkdownBlock::type)
                .containsExactly(BlockType.HEADING, BlockType.CODE_FENCE, BlockType.PARAGRAPH);
        assertThat(blocks.get(1).text()).contains("# 不是标题", "| --- | --- |");
    }

    @Test
    void keepsFaqQuestionAndAnswerTogether() {
        String markdown = """
                问：如何重试？
                答：点击重试按钮。
                仍失败时查看错误信息。

                下一段。
                """;

        List<MarkdownBlock> blocks = parser.parse(markdown, "FAQ");

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).attributes())
                .containsEntry("contentType", "faq")
                .containsEntry("hasAnswer", true);
        assertThat(blocks.get(0).text()).contains("问：", "答：", "仍失败");
    }

    @Test
    void leavesUnclosedFenceAsOneDeterministicBlock() {
        String markdown = "# 标题\n```java\nint value = 1;";

        List<MarkdownBlock> blocks = parser.parse(markdown, "doc.md");

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).type()).isEqualTo(BlockType.CODE_FENCE);
        assertThat(blocks.get(1).attributes()).containsEntry("closed", false);
        assertThat(blocks.get(1).endOffset()).isEqualTo(markdown.length());
    }
}
