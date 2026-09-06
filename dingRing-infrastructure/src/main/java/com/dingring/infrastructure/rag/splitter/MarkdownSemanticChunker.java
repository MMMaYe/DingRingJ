package com.dingring.infrastructure.rag.splitter;

import com.dingring.infrastructure.rag.parser.BlockType;
import com.dingring.infrastructure.rag.parser.MarkdownBlock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 结构感知切片器（P3 F3）。
 * <p>策略：同 headingPath 下聚合普通短块；结构块（代码/表格/列表/引用）保持原子，
 * 超长时按语法边界拆分；普通自然语言超长块按句子做确定性分组切分。
 * <p>当前句子切分不依赖 embedding 相似度（避免每个长块都打 API），
 * 采用固定容量贪心组包：在 token budget 内尽量填满、避免低于 minTokens 的碎片。
 * 语义断点接口保留，后续评测集校准后可替换实现。
 */
@Component
public class MarkdownSemanticChunker {

    private final TokenCounter tokenCounter;
    private final int targetTokens;
    private final int minTokens;
    private final int maxTokens;
    private final int absoluteMaxTokens;

    public MarkdownSemanticChunker(TokenCounter tokenCounter,
                                   @Value("${dingring.rag.chunk.target-tokens:600}") int targetTokens,
                                   @Value("${dingring.rag.chunk.min-tokens:120}") int minTokens,
                                   @Value("${dingring.rag.chunk.max-tokens:900}") int maxTokens,
                                   @Value("${dingring.rag.chunk.absolute-max-tokens:1800}") int absoluteMaxTokens) {
        this.tokenCounter = tokenCounter;
        this.targetTokens = targetTokens;
        this.minTokens = minTokens;
        this.maxTokens = maxTokens;
        this.absoluteMaxTokens = absoluteMaxTokens;
    }

    public List<SemanticChunk> chunk(List<MarkdownBlock> blocks) {
        List<SemanticChunk> result = new ArrayList<>();
        List<MarkdownBlock> buffer = new ArrayList<>();

        for (MarkdownBlock block : blocks) {
            if (block.type() == BlockType.HEADING || block.type() == BlockType.FRONT_MATTER
                    || block.type() == BlockType.THEMATIC_BREAK) {
                flush(result, buffer);
                continue;
            }
            if (isStructural(block.type()) || !sameAggregateGroup(buffer, block)) {
                flush(result, buffer);
            }
            buffer.add(block);
            if (tokenCounter.count(joinText(buffer)) >= targetTokens) {
                flush(result, buffer);
            }
        }
        flush(result, buffer);
        return mergeFragments(result);
    }

    /**
     * 碎片合并（设计 §5.1「minTokens=碎片下限，低于必须合并」）。
     * <p>标题/结构块边界只约束聚合阶段；结构密集文档（多小节+短代码块）按边界切完后
     * 仍会产生大量低于 minTokens 的碎片（实测一篇技术文 70% chunk 不足 120 token，
     * 极端如 7 字符的"典型代码如下："成为独立噪声向量）。碎片作为独立向量对检索
     * 是纯噪声——embedding 无语义、反而占据召回名额，因此合并的收益远大于跨边界的
     * 语境稀释（headingPath 取前块，父/邻居扩展可补上下文）。
     * <p>规则：相邻 chunk 任一低于 minTokens 即合并；合并后不超过 maxTokens；
     * 双方都达标则保持边界不动（尊重标题/结构边界）。
     */
    private List<SemanticChunk> mergeFragments(List<SemanticChunk> chunks) {
        if (chunks.size() <= 1) {
            return reindex(chunks);
        }
        int[] tokens = new int[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            tokens[i] = tokenCounter.count(chunks.get(i).content());
        }
        List<SemanticChunk> merged = new ArrayList<>();
        List<Integer> mergedTokens = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            SemanticChunk current = chunks.get(i);
            if (!merged.isEmpty()) {
                int lastTokens = mergedTokens.get(mergedTokens.size() - 1);
                boolean fragment = lastTokens < minTokens || tokens[i] < minTokens;
                if (fragment && lastTokens + tokens[i] <= maxTokens) {
                    merged.set(merged.size() - 1, combine(merged.get(merged.size() - 1), current));
                    mergedTokens.set(mergedTokens.size() - 1, lastTokens + tokens[i]);
                    continue;
                }
            }
            merged.add(current);
            mergedTokens.add(tokens[i]);
        }
        return reindex(merged);
    }

    /** 合并两个 chunk：来源行/偏移取跨度，headingPath/parentChunkId 取前块（语境锚定） */
    private SemanticChunk combine(SemanticChunk first, SemanticChunk second) {
        Set<BlockType> types = EnumSet.noneOf(BlockType.class);
        types.addAll(first.blockTypes());
        types.addAll(second.blockTypes());
        String language = first.language() != null && !first.language().isBlank()
                ? first.language() : second.language();
        return new SemanticChunk(first.index(),
                first.content() + "\n" + second.content(),
                first.headingPath(), types,
                first.sourceStartLine(), second.sourceEndLine(),
                first.sourceStartOffset(), second.sourceEndOffset(),
                language, first.parentChunkId());
    }

    /** 合并后重建连续索引（chunkId 含 index，必须 0..n-1 连续才能保证幂等重试不漂移） */
    private static List<SemanticChunk> reindex(List<SemanticChunk> chunks) {
        List<SemanticChunk> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            SemanticChunk c = chunks.get(i);
            result.add(c.index() == i ? c : new SemanticChunk(i, c.content(), c.headingPath(),
                    c.blockTypes(), c.sourceStartLine(), c.sourceEndLine(),
                    c.sourceStartOffset(), c.sourceEndOffset(), c.language(), c.parentChunkId()));
        }
        return result;
    }

    /** 相邻普通短块可合并的条件：headingPath 相同且同为 PARAGRAPH */
    private boolean sameAggregateGroup(List<MarkdownBlock> buffer, MarkdownBlock block) {
        return buffer.isEmpty()
                || (buffer.get(buffer.size() - 1).type() == BlockType.PARAGRAPH
                && block.type() == BlockType.PARAGRAPH
                && buffer.get(buffer.size() - 1).headingPath().equals(block.headingPath()));
    }

    private boolean isStructural(BlockType type) {
        return type == BlockType.CODE_FENCE || type == BlockType.TABLE
                || type == BlockType.LIST || type == BlockType.QUOTE
                || type == BlockType.HTML_BLOCK;
    }

    private void flush(List<SemanticChunk> result, List<MarkdownBlock> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        String text = joinText(buffer);
        int tokens = tokenCounter.count(text);
        if (tokens <= maxTokens) {
            result.add(toChunk(result.size(), buffer, text));
        } else if (buffer.size() == 1) {
            result.addAll(splitSingle(result.size(), buffer.get(0)));
        } else {
            // 多块聚合超长：先逐块回退拆，再按序输出
            for (MarkdownBlock block : buffer) {
                String blockText = block.text();
                if (tokenCounter.count(blockText) <= maxTokens) {
                    result.add(toChunk(result.size(), List.of(block), blockText));
                } else {
                    result.addAll(splitSingle(result.size(), block));
                }
            }
        }
        buffer.clear();
    }

    private List<SemanticChunk> splitSingle(int startIndex, MarkdownBlock block) {
        if (block.type() == BlockType.TABLE) {
            return splitTable(startIndex, block);
        }
        if (block.type() == BlockType.CODE_FENCE) {
            return splitByLines(startIndex, block);
        }
        return splitParagraph(startIndex, block);
    }

    /** 表格按行组拆分，每块重复表头（前两行视为表头） */
    private List<SemanticChunk> splitTable(int startIndex, MarkdownBlock block) {
        List<String> lines = block.text().lines().toList();
        if (lines.size() <= 2) {
            return List.of(toChunk(startIndex, List.of(block), block.text()));
        }
        String header = lines.get(0) + "\n" + lines.get(1);
        List<SemanticChunk> result = new ArrayList<>();
        StringBuilder current = new StringBuilder(header);
        int currentTokens = tokenCounter.count(header);
        for (int i = 2; i < lines.size(); i++) {
            String row = lines.get(i);
            int rowTokens = tokenCounter.count(row);
            if (currentTokens + rowTokens > maxTokens && current.length() > header.length()) {
                result.add(toChunk(startIndex + result.size(), List.of(block), current.toString()));
                current = new StringBuilder(header);
                currentTokens = tokenCounter.count(header);
            }
            current.append('\n').append(row);
            currentTokens += rowTokens;
        }
        if (current.length() > header.length()) {
            result.add(toChunk(startIndex + result.size(), List.of(block), current.toString()));
        }
        return result;
    }

    /** 代码/HTML 块按行拆分兜底，保留语言标记 */
    private List<SemanticChunk> splitByLines(int startIndex, MarkdownBlock block) {
        List<String> lines = block.text().lines().toList();
        List<SemanticChunk> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        for (String line : lines) {
            int lineTokens = tokenCounter.count(line);
            if (currentTokens + lineTokens > maxTokens && currentTokens >= minTokens) {
                result.add(toChunk(startIndex + result.size(), List.of(block), current.toString()));
                current = new StringBuilder();
                currentTokens = 0;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            currentTokens += Math.max(lineTokens, 1);
        }
        if (current.length() > 0) {
            result.add(toChunk(startIndex + result.size(), List.of(block), current.toString()));
        }
        return result;
    }

    /** 普通自然语言超长块：句子切分 + token budget 内贪心组包（确定性，可重复） */
    private List<SemanticChunk> splitParagraph(int startIndex, MarkdownBlock block) {
        List<String> sentences = splitSentences(block.text());
        List<SemanticChunk> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        for (String sentence : sentences) {
            int sentenceTokens = tokenCounter.count(sentence);
            if (sentenceTokens >= absoluteMaxTokens) {
                // 单句超绝对上限：按绝对上限硬切（最后兜底）
                if (current.length() > 0) {
                    result.add(toChunk(startIndex + result.size(), List.of(block), current.toString()));
                    current = new StringBuilder();
                    currentTokens = 0;
                }
                result.addAll(hardSplit(startIndex + result.size(), block, sentence));
                continue;
            }
            if (currentTokens + sentenceTokens > maxTokens && currentTokens >= minTokens) {
                result.add(toChunk(startIndex + result.size(), List.of(block), current.toString()));
                current = new StringBuilder();
                currentTokens = 0;
            }
            current.append(sentence);
            currentTokens += sentenceTokens;
        }
        if (current.length() > 0) {
            result.add(toChunk(startIndex + result.size(), List.of(block), current.toString()));
        }
        return result;
    }

    private List<SemanticChunk> hardSplit(int startIndex, MarkdownBlock block, String sentence) {
        List<SemanticChunk> result = new ArrayList<>();
        int start = 0;
        while (start < sentence.length()) {
            int end = sentence.length();
            while (end > start + 1 && tokenCounter.count(sentence.substring(start, end)) > absoluteMaxTokens) {
                end = Math.max(start + 1, end - 256);
            }
            result.add(toChunk(startIndex + result.size(), List.of(block), sentence.substring(start, end)));
            start = end;
        }
        return result;
    }

    private static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?'
                    || c == ';' || c == '；' || c == '\n') {
                if (i + 1 >= text.length() || text.charAt(i + 1) == ' '
                        || text.charAt(i + 1) == '\n' || text.charAt(i + 1) == '　') {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private SemanticChunk toChunk(int index, List<MarkdownBlock> blocks, String text) {
        MarkdownBlock first = blocks.get(0);
        MarkdownBlock last = blocks.get(blocks.size() - 1);
        Set<BlockType> types = EnumSet.noneOf(BlockType.class);
        blocks.forEach(b -> types.add(b.type()));
        String headingKey = String.join("/", first.headingPath());
        return new SemanticChunk(index, text, first.headingPath(), types,
                first.startLine(), last.endLine(), first.startOffset(), last.endOffset(),
                first.language(), "section:" + headingKey + ":" + first.startLine());
    }

    private static String joinText(List<MarkdownBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (MarkdownBlock block : blocks) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(block.text());
        }
        return sb.toString();
    }
}
