package com.dingring.infrastructure.agent.tool;

import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识库检索工具（Phase E 实现）。
 * <p>Agent 讨论时可调用此工具检索 RAG 知识库，获取与查询相关的知识段落。
 * <p>检索流程：向量召回 Top-20 + LLM 重排 Top-5，返回格式化文本。
 * <p>过滤口径：仅检索群绑定的知识库（chat_group.knowledge_base_config.kbIds）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeSearchTool {

    private final RagService ragService;
    private final GroupRepository groupRepository;

    /**
     * 搜索知识库。
     * <p>按群绑定的知识库过滤（knowledge_base_config.kbIds），未绑定库的群无可检索内容。
     *
     * @param query   搜索查询关键词
     * @param groupId 群组 ID（用于解析群绑定的知识库）
     * @return 知识段落文本，无结果时返回提示语
     */
    @Tool(description = "搜索知识库，返回与查询相关的知识段落，用于讨论时引用权威资料")
    public String searchKnowledge(
            @ToolParam(description = "搜索查询关键词") String query,
            @ToolParam(description = "群组 ID，用于限定检索群绑定的知识库") Long groupId) {
        List<Long> kbIds = groupId == null ? List.of()
                : groupRepository.findById(groupId).map(g -> g.boundKbIds()).orElse(List.of());
        if (kbIds.isEmpty()) {
            return "当前群未绑定任何知识库，无资料可检索。请基于你自身知识回答。";
        }
        log.debug("KnowledgeSearchTool 调用: query={} groupId={} kbIds={}", query, groupId, kbIds);
        String result = ragService.retrieve(query, kbIds);
        if (result == null || result.isBlank()) {
            return "未检索到相关知识。请基于你自身知识回答。";
        }
        return result;
    }
}
