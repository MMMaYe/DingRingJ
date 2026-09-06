package com.dingring.infrastructure.rag.retrieval;

import com.dingring.common.util.HashUtil;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.service.LlmService;
import com.dingring.infrastructure.rag.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 检索 query 规范化与改写（7.2）。
 * <p>两步走：
 * <ol>
 *   <li>normalize：trim + 连续空白折叠为单空格（确定性、零成本）；
 *       规范化结果同时用于 Redis 目录缓存 key（同一 query 两种写法命中同一缓存）；</li>
 *   <li>rewrite：query 含指代词（它/这个/刚才/上述…）时，
 *       结合话题标题用小模型改写为自包含查询（temperature=0 保证稳定）。
 *       改写失败回退原 query——改写是增强不是依赖。</li>
 * </ol>
 * <p>rewrite 复用 routeJudge Agent（id=6，DeepSeek-V4-Flash）：
 * 与意图分类同一轻量模型池，不新增 API 依赖（P3 一贯的复用原则）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class QueryNormalizer {

    /** 指代词触发列表：命中才值得花一次 LLM 调用做改写 */
    private static final Pattern REFERENCE_WORDS = Pattern.compile(
            "它|他们|她们|这个|那个|这些|那些|刚才|上述|上面|前面|之前|该|此");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final LlmService llmService;
    private final AgentRepository agentRepository;
    private final RagProperties ragProperties;

    public QueryNormalizer(LlmService llmService, AgentRepository agentRepository,
                           RagProperties ragProperties) {
        this.llmService = llmService;
        this.agentRepository = agentRepository;
        this.ragProperties = ragProperties;
    }

    /** 规范化：trim + 空白折叠（确定性） */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return WHITESPACE.matcher(raw.trim()).replaceAll(" ");
    }

    /** 规范化结果的缓存 key：sha256（query 通常含中文，hash 比 URL 编码紧凑） */
    public static String cacheKey(String normalizedQuery) {
        return HashUtil.sha256(normalizedQuery);
    }

    /**
     * 改写指代词为自包含查询。
     *
     * @param normalizedQuery 已规范化 query
     * @param topicTitle     当前话题标题（提供指代消解的锚点；空则跳过改写）
     * @return 改写后的 query；不含指代词/失败/为空时返回原 query
     */
    public String rewrite(String normalizedQuery, String topicTitle) {
        if (normalizedQuery == null || normalizedQuery.isBlank()
                || topicTitle == null || topicTitle.isBlank()
                || !REFERENCE_WORDS.matcher(normalizedQuery).find()) {
            return normalizedQuery;
        }
        try {
            Optional<Agent> agentOpt =
                    agentRepository.findById(ragProperties.getRetrieval().getRewriteAgentId());
            if (agentOpt.isEmpty()) {
                return normalizedQuery;
            }
            String prompt = "当前话题标题：" + topicTitle + "\n"
                    + "用户检索词（含指代）：" + normalizedQuery + "\n"
                    + "把检索词改写为不依赖上下文也能理解的完整查询（把指代词替换为具体所指）。"
                    + "只输出改写后的查询本身，不要解释。";
            LlmService.CallOptions options = new LlmService.CallOptions(0.0, 256, 15L, false, false);
            String rewritten = llmService.chat(agentOpt.get(),
                    "你是查询改写助手", List.of(LlmService.ChatTurn.user(prompt)), options);
            if (rewritten == null || rewritten.isBlank()) {
                return normalizedQuery;
            }
            String cleaned = normalize(rewritten);
            LogHelper.printLog(QueryNormalizer.class, "rewrite", "RAG_QUERY",
                    "指代词改写", "origin={} rewritten={}", normalizedQuery, cleaned);
            return cleaned;
        } catch (Exception e) {
            LogHelper.printWarnLog(QueryNormalizer.class, "rewrite", "RAG_QUERY",
                    "改写失败回退原query", "错误: {}", e.getMessage());
            return normalizedQuery;
        }
    }
}
