package com.dingring.infrastructure.rag.splitter;

import com.dingring.infrastructure.rag.parser.BlockType;
import com.dingring.infrastructure.rag.parser.MarkdownBlock;
import com.dingring.infrastructure.rag.parser.MarkdownStructureParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownSemanticChunkerTest {

    /** 确定性假 token 计数：中文 1 字 = 1 token，ASCII 2 字符 = 1 token */
    private static final TokenCounter FAKE = text -> {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int wide = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                wide++;
            } else {
                ascii++;
            }
        }
        return wide + (ascii + 1) / 2;
    };

    private final MarkdownStructureParser parser = new MarkdownStructureParser();
    private final MarkdownSemanticChunker chunker =
            new MarkdownSemanticChunker(FAKE, 600, 120, 900, 1800);

    @Test
    void aggregatesShortParagraphsUnderSameHeading() {
        String markdown = """
                # 标题A
                短段落一。
                短段落二。
                """;

        List<SemanticChunk> chunks = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("短段落一", "短段落二");
        assertThat(chunks.get(0).headingPath()).containsExactly("doc.md", "标题A");
    }

    @Test
    void mergesFragmentsAcrossHeadingBoundaryBelowFloor() {
        // 两侧均低于 minTokens=120 的碎片必须合并（设计 §5.1"低于必须合并"优先于标题边界），
        // 否则结构密集文档会产生大量噪声向量
        String markdown = """
                # 标题A
                段落一。
                # 标题B
                段落二。
                """;

        List<SemanticChunk> chunks = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("段落一", "段落二");
        assertThat(chunks.get(0).headingPath()).containsExactly("doc.md", "标题A");
    }

    @Test
    void keepsHeadingBoundaryWhenBothSidesReachFloor() {
        // 双方都达到 minTokens 时保持标题边界（不无脑合并）
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            first.append("标题A下的段落内容编号").append(i).append("。");
            second.append("标题B下的段落内容编号").append(i).append("。");
        }
        String markdown = "# 标题A\n" + first + "\n# 标题B\n" + second;

        List<SemanticChunk> chunks = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).content()).contains("标题A下的段落内容编号");
        assertThat(chunks.get(1).content()).contains("标题B下的段落内容编号");
    }

    @Test
    void mergesTinyIntroSentenceWithFollowingCode() {
        // "典型代码如下："这类引导句单独成 chunk 是检索噪声，应并入后续代码块
        String markdown = """
                # 标题
                典型代码如下：

                ```java
                int a = 1;
                ```

                尾部说明段。
                """;

        List<SemanticChunk> chunks = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("典型代码如下", "int a = 1;", "尾部说明段");
        assertThat(chunks.get(0).blockTypes()).contains(BlockType.PARAGRAPH, BlockType.CODE_FENCE);
    }

    @Test
    void keepsCodeAndTableAtomicWhenShort() {
        String markdown = """
                # 标题
                ```java
                int a = 1;
                ```

                | 配置 | 值 |
                | --- | --- |
                | k | v |
                """;

        List<SemanticChunk> chunks = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(chunks).extracting(c -> String.join(",", c.blockTypes().stream().map(Enum::name).sorted().toList()))
                .anySatisfy(types -> assertThat(types).contains("CODE_FENCE"))
                .anySatisfy(types -> assertThat(types).contains("TABLE"));
        assertThat(chunks).anySatisfy(c -> assertThat(c.content()).contains("int a = 1;"));
    }

    @Test
    void splitsLongTableWithRepeatedHeader() {
        StringBuilder sb = new StringBuilder("| 配置项 | 取值 |\n| --- | --- |\n");
        for (int i = 0; i < 200; i++) {
            sb.append("| config-key-").append(i).append(" | value-").append(i).append(" |\n");
        }
        String markdown = "# 标题\n" + sb;

        List<SemanticChunk> chunks = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(chunks).hasSizeGreaterThan(1);
        chunks.forEach(c -> {
            assertThat(c.content()).startsWith("| 配置项 | 取值 |");
            assertThat(FAKE.count(c.content())).isLessThanOrEqualTo(1800);
        });
    }

    @Test
    void splitsLongParagraphDeterministically() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("这是第").append(i).append("句用于测试超长段落切分的句子内容。");
        }
        String markdown = "# 标题\n" + sb;

        List<SemanticChunk> first = chunker.chunk(parser.parse(markdown, "doc.md"));
        List<SemanticChunk> second = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(first).isEqualTo(second);
        assertThat(first.size()).isGreaterThan(1);
        first.forEach(c -> assertThat(FAKE.count(c.content())).isLessThanOrEqualTo(1800));
    }

    @Test
    void allChunksCarryTraceableSourceLocations() {
        String markdown = """
                # 标题
                段落内容。

                ## 小节
                - 项目一
                - 项目二
                """;

        List<SemanticChunk> chunks = chunker.chunk(parser.parse(markdown, "doc.md"));

        assertThat(chunks).isNotEmpty();
        chunks.forEach(c -> {
            assertThat(c.headingPath()).isNotEmpty();
            assertThat(c.sourceStartLine()).isGreaterThan(0);
            assertThat(c.sourceEndLine()).isGreaterThanOrEqualTo(c.sourceStartLine());
            assertThat(c.parentChunkId()).isNotBlank();
        });
    }
}
