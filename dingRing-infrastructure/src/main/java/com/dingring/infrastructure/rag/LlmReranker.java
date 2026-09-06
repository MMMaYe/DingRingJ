package com.dingring.infrastructure.rag;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.LlmService.CallOptions;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.service.Reranker;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * LLM 文档重排器（Phase E，legacy 兼容实现）。
 * <p>向量召回 Top-20 后，用轻量 LLM 对候选文档按相关性打分，取 Top-5。
 * <p>重排 prompt 由 {@link PromptTemplateLoader} 渲染 Nacos 模板（本地降级 classpath prompt-config.json），
 * 复用 routeJudge Agent（id=6，DeepSeek-V4-Flash）调用 LLM，避免新增 API 依赖。
 * <p>容错：LLM 打分失败时降级为原始顺序返回（跳过重排）。
 * <p>P3 灰度切换：dingring.rag.reranker.provider=llm（或不配置）时生效；
 * 配置 siliconflow 时由 {@link com.dingring.infrastructure.rag.retrieval.SiliconFlowReranker}
 * 取代（Qwen3-Reranker-4B 专用模型，rerankStructured 主链路）——两者互斥装配，
 * 任意时刻容器内恰有一个 Reranker Bean。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.rag.reranker.provider", havingValue = "llm", matchIfMissing = true)
public class LlmReranker implements Reranker {

    /** 重排 prompt 模板名（prompt-config.json 中定义） */
    private static final String PROMPT_TEMPLATE = "rag-rerank";
    /** 候选文档单条截取长度（避免 token 过长） */
    private static final int CANDIDATE_TRUNCATE = 200;

    private final LlmService llmService;
    private final AgentRepository agentRepository;
    private final PromptTemplateLoader promptTemplateLoader;

    /** 重排用 Agent ID（默认 routeJudge Agent，轻量模型） */
    @Value("${dingring.rag.reranker.agent-id:6}")
    private Long rerankerAgentId;

    @Override
    public List<ScoredDocument> rerank(String query, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        // 候选数 <= 5 时无需重排，直接返回
        if (candidates.size() <= 5) {
            return candidates.stream()
                    .map(c -> new ScoredDocument(c, 5.0))
                    .collect(Collectors.toList());
        }
        try {
            Optional<Agent> agentOpt = agentRepository.findById(rerankerAgentId);
            if (agentOpt.isEmpty()) {
                log.warn("重排Agent不存在,降级为原始顺序: agentId={}", rerankerAgentId);
                return fallbackOrder(candidates);
            }
            Agent agent = agentOpt.get();
            String prompt = buildRerankPrompt(query, candidates);
            List<ChatTurn> messages = List.of(ChatTurn.user(prompt));

            CallOptions options = new CallOptions(0.0, 512, 15L, true, false);
            String response = llmService.chat(agent, "你是文档相关性评估专家", messages, options);

            return parseRerankResponse(response, candidates);
        } catch (Exception e) {
            LogHelper.printWarnLog(LlmReranker.class, "rerank", "RAG_RERANK",
                    "LLM重排失败降级为原始顺序", "query={} 候选数={} 错误: {}",
                            query.substring(0, Math.min(50, query.length())), candidates.size(), e.getMessage());
            return fallbackOrder(candidates);
        }
    }

    /**
     * 渲染重排 prompt：模板注入 query + 候选文档列表。
     * <p>模板缺失时降级为硬编码拼接（保证无 Nacos/本地模板时仍可用）。
     */
    private String buildRerankPrompt(String query, List<String> candidates) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("query", query);
        vars.put("candidates", formatCandidates(candidates));
        String rendered = promptTemplateLoader.render(PROMPT_TEMPLATE, vars);
        if (rendered != null && !rendered.isBlank()) {
            return rendered;
        }
        // 模板加载失败兜底：手工拼接，保证重排链路可用
        StringBuilder sb = new StringBuilder();
        sb.append("请根据查询对以下文档按相关性打分（0-10分，10分最相关）。\n\n");
        sb.append("查询：").append(query).append("\n\n候选文档：\n").append(formatCandidates(candidates));
        sb.append("\n请输出 JSON 数组，格式：[{\"index\":0,\"score\":8.5},...]，不需要其他文字。");
        return sb.toString();
    }

    /** 候选文档格式化为「[i] 内容」行（截断避免 token 过长） */
    private String formatCandidates(List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i);
            if (text.length() > CANDIDATE_TRUNCATE) {
                text = text.substring(0, CANDIDATE_TRUNCATE) + "...";
            }
            sb.append("[").append(i).append("] ").append(text).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 解析 LLM 重排响应。
     * <p>期望格式: [{"index":0,"score":8.5},...]
     */
    private List<ScoredDocument> parseRerankResponse(String response, List<String> candidates) {
        if (response == null || response.isBlank()) {
            return fallbackOrder(candidates);
        }
        try {
            // 提取 JSON 数组（LLM 可能在 JSON 前后加文字）
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start < 0 || end < 0) {
                return fallbackOrder(candidates);
            }
            String jsonStr = response.substring(start, end + 1);
            JSONArray scores = JSON.parseArray(jsonStr);

            List<ScoredDocument> result = new ArrayList<>();
            for (int i = 0; i < scores.size(); i++) {
                JSONObject item = scores.getJSONObject(i);
                int index = item.getIntValue("index");
                double score = item.getDoubleValue("score");
                if (index >= 0 && index < candidates.size()) {
                    result.add(new ScoredDocument(candidates.get(index), score));
                }
            }

            // 按分数降序排序
            result.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());

            if (result.isEmpty()) {
                return fallbackOrder(candidates);
            }
            LogHelper.printLog(LlmReranker.class, "rerank", "RAG_RERANK",
                    "重排完成", "候选数={} 重排结果数={} 最高分={}",
                            candidates.size(), result.size(), result.get(0).score());
            return result;
        } catch (JSONException e) {
            LogHelper.printWarnLog(LlmReranker.class, "rerank", "RAG_RERANK",
                    "重排响应解析失败", "response={} 错误: {}",
                            response.substring(0, Math.min(100, response.length())), e.getMessage());
            return fallbackOrder(candidates);
        }
    }

    /** 降级：原始顺序，给默认分数 */
    private List<ScoredDocument> fallbackOrder(List<String> candidates) {
        return candidates.stream()
                .map(c -> new ScoredDocument(c, 5.0))
                .collect(Collectors.toList());
    }
}
