package com.dingring.infrastructure.rag.splitter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FixedSizeTextSplitter} 固定字符滑窗切片单测。
 */
@DisplayName("FixedSizeTextSplitter 固定字符切片")
class FixedSizeTextSplitterTest {

    private FixedSizeTextSplitter splitter(int size, int overlap) {
        return new FixedSizeTextSplitter(size, overlap);
    }

    @Test
    @DisplayName("长文本按 512/64 滑窗切块：块长不超上限且相邻块有重叠")
    void shouldSplitLongTextWithOverlap() {
        String text = "知".repeat(1200);  // 1200 字符
        List<Document> chunks = splitter(512, 64).apply(List.of(new Document(text)));

        assertThat(chunks.size()).isGreaterThanOrEqualTo(3);
        for (Document chunk : chunks) {
            assertThat(chunk.getText().length()).isLessThanOrEqualTo(512);
        }
        // 相邻块重叠 = 第二块开头 64 字符 == 第一块结尾 64 字符（步进 = 512-64 = 448）
        String first = chunks.get(0).getText();
        String second = chunks.get(1).getText();
        assertThat(second.substring(0, 64)).isEqualTo(first.substring(512 - 64));
        // 拼接无损：最后块结尾 == 原文结尾
        assertThat(chunks.get(chunks.size() - 1).getText().endsWith("知")).isTrue();
    }

    @Test
    @DisplayName("短于切块大小的文本保留为单块不截断")
    void shouldKeepShortTextAsSingleChunk() {
        List<Document> chunks = splitter(512, 64).apply(List.of(new Document("短文本")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEqualTo("短文本");
    }

    @Test
    @DisplayName("中英混排按字符数（length）切块，不按字节")
    void shouldSplitByCharsNotBytes() {
        String text = "abc中文def".repeat(250);  // 2000 字符（每重复 8 字符：3 英文 + 2 中文 + 3 英文）
        List<Document> chunks = splitter(512, 64).apply(List.of(new Document(text)));

        assertThat(chunks.size()).isGreaterThanOrEqualTo(4);
        // 按字符切的强断言：非末块恰为 512 字符（若误按字节切，中文 3 字节/字，每块仅 ~300 字符，此断言必失败）
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertThat(chunks.get(i).getText().length()).isEqualTo(512);
        }
        assertThat(chunks.stream().mapToInt(c -> c.getText().length()).sum()).isGreaterThan(2000);
    }

    @Test
    @DisplayName("空文本返回单块空文档（与 TokenTextSplitter 行为对齐：不炸管道）")
    void shouldHandleEmptyText() {
        List<Document> chunks = splitter(512, 64).apply(List.of(new Document("")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getText()).isEmpty();
    }

    @Test
    @DisplayName("切片继承原文档 metadata（溯源信息随块传播）")
    void shouldInheritMetadata() {
        Document doc = new Document("内容内容", Map.of("fileId", 12L, "scope", "GLOBAL"));
        List<Document> chunks = splitter(4, 0).apply(List.of(doc));

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).getMetadata()).containsEntry("fileId", 12L).containsEntry("scope", "GLOBAL");
    }

    @Test
    @DisplayName("overlap >= chunkSize 时构造即抛出（滑窗步进非正数是配置错误）")
    void shouldRejectInvalidOverlap() {
        assertThatThrownBy(() -> splitter(100, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> splitter(100, 101)).isInstanceOf(IllegalArgumentException.class);
    }
}
