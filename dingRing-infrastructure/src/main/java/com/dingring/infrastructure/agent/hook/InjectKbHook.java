package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.service.RagService;
import com.dingring.domain.service.RetrievalCandidate;
import com.dingring.domain.service.RetrievalResult;
import com.dingring.domain.service.TopicVectorService;
import com.dingring.domain.workflow.StateKeys;
import com.dingring.infrastructure.rag.config.RagProperties;
import com.dingring.infrastructure.rag.retrieval.QueryNormalizer;
import com.dingring.infrastructure.rag.splitter.TokenCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 级知识注入 Hook（P3 目录注入模式，7.5）。
 * <p>为什么是 AgentHook 而非 ModelHook：RagInjectionHook 的 BEFORE_MODEL 位置在 ReAct
 * 工具循环内每次模型调用都触发（重复检索+重复注入）；本 Hook 的 BEFORE_AGENT 位置在整个
 * Agent 运行前执行一次，注入的 SystemMessage 存于 state 贯穿全部模型调用。
 * <p>P3 注入协议（两级结构）：
 * <ul>
 *   <li>Level 1（本 Hook 注入）：检索目录 ≤500 token——相关性排序的条目列表
 *       （分数/文件/章节/chunkId/摘要），让模型低成本感知有哪些可用知识；</li>
 *   <li>Level 2（kb_read_in_redis 工具按需读取）：chunk 全文，按次 4096 token 预算，
 *       仅 DISCUSS/WORK 的 ToolSet 注册（SaaLlmFactory）——目录保 prompt 低成本，
 *       全文保深读能力，chunkId 两级关联。</li>
 * </ul>
 * <p>意图门控矩阵：
 * <ul>
 *   <li>CHAT：双源跳过（闲聊不需要知识注入）</li>
 *   <li>DISCUSS：kb 源（ragQuery）+ topic 源（topicTitle）</li>
 *   <li>CONCLUDE：仅 topic 源（收束总结本话题，相似历史结论作参考）</li>
 *   <li>WORK：仅 kb 源（任务要资料，历史话题无关）</li>
 *   <li>intent 缺失：有 ragQuery 则 kb 源（兼容 Supervisor Worker clearContext 后的空 state）</li>
 * </ul>
 * <p>query 预处理（7.2）：normalize（确定性）→ 含指代词时结合 topicTitle 用
 * routeJudge 小模型改写为自包含查询（改写失败回退原 query）。
 * <p>kb 源只检索群绑定的知识库（chat_group.knowledge_base_config.kbIds）。
 * topic 源按 topicId 缓存 TTL 10 分钟（话题存续期内重复检索浪费 embedding 调用）。
 * <p>容错：任一源异常跳过该源，双源全空返回空 Map，绝不阻塞 Agent 发言。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class InjectKbHook extends AgentHook {

    private static final int SIMILAR_TOP_K = 3;
    private static final double SIMILAR_THRESHOLD = 0.75;
    /** 相似话题结论单条截断（控制 prompt 长度） */
    private static final int CONCLUSION_MAX_LEN = 200;
    /** 目录条目摘要长度（字符）：足够模型判断相关性，不足够替代全文 */
    private static final int CATALOG_SNIPPET_CHARS = 120;
    /** topic 源缓存 TTL（ms） */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    /** 缓存容量上限（超限全清：回溯场景量小，简单策略足够） */
    private static final int CACHE_MAX_SIZE = 100;

    private final RagService ragService;
    private final TopicVectorService topicVectorService;
    private final TopicRepository topicRepository;
    private final GroupRepository groupRepository;
    private final QueryNormalizer queryNormalizer;
    private final TokenCounter tokenCounter;
    private final RagProperties ragProperties;

    /** topicId → 缓存条目（注入文本段 + 时间戳） */
    private final ConcurrentHashMap<Long, CacheEntry> topicCache = new ConcurrentHashMap<>();

    private record CacheEntry(long timestamp, String text) {}

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        String intent = state.value(StateKeys.INTENT, "");
        String ragQuery = state.value("ragQuery", "");
        Long groupId = state.<Long>value("groupId").orElse(null);
        Long topicId = state.<Long>value(StateKeys.TOPIC_ID).orElse(null);
        String topicTitle = state.value(StateKeys.TOPIC_TITLE, "");

        LogHelper.printLog(InjectKbHook.class,
                "InjectKbHook.beforeAgent",
                "RAG_TOPIC_MEMORY","增强主题记忆",
                "msg={}", JsonHelper.toJsonStr("intent",intent, "ragQuery", ragQuery, "groupId", groupId, "topicId", topicId, "topicTitle", topicTitle));

        long start = System.currentTimeMillis();

        // 意图门控：CHAT 显式跳过（闲聊不注入）
        if ("CHAT".equals(intent)) {
            return CompletableFuture.completedFuture(Map.of());
        }

        boolean kbWanted = "DISCUSS".equals(intent) || "WORK".equals(intent)
                || (intent.isBlank() && !ragQuery.isBlank());
        boolean topicWanted = ("DISCUSS".equals(intent) || "CONCLUDE".equals(intent))
                && !topicTitle.isBlank();

        String kbSection = "";
        if (kbWanted && groupId != null && !ragQuery.isBlank()) {
            List<Long> kbIds = resolveBoundKbIds(groupId);
            if (kbIds.isEmpty()) {
                // 未绑定任何知识库：kb 源整体跳过（检索也只会得到空结果，直接省掉）
                LogHelper.printLog(InjectKbHook.class, "beforeAgent", "INJECT_KB",
                        "群未绑定知识库，跳过kb源", "groupId={}", groupId);
            } else {
                kbSection = retrieveKbSafely(ragQuery, kbIds, groupId, topicTitle);
            }
        }

        String topicSection = "";
        if (topicWanted && topicId != null) {
            topicSection = retrieveTopicSafely(topicId, topicTitle);
        }

        LogHelper.printLog(InjectKbHook.class, "beforeAgent", "INJECT_KB",
                "知识注入完成", "intent={} kb={} topicLen={} 耗时={}ms",
                intent.isBlank() ? "(missing)" : intent, !kbSection.isBlank(),
                topicSection.length(), System.currentTimeMillis() - start);

        if (kbSection.isBlank() && topicSection.isBlank()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        String text = kbSection.isBlank() ? topicSection
                : topicSection.isBlank() ? kbSection : kbSection + "\n\n" + topicSection;
        return CompletableFuture.completedFuture(Map.of("messages", new SystemMessage(text)));
    }

    /**
     * 解析群绑定的知识库 ID 列表，群不存在等异常按未绑定处理（跳过 kb 源）。
     * <p>刻意不做缓存：群设置随时可改绑定，实时读取保证下一条消息即生效。
     */
    private List<Long> resolveBoundKbIds(Long groupId) {
        try {
            return groupRepository.findById(groupId)
                    .map(Group::boundKbIds)
                    .orElse(List.of());
        } catch (Exception e) {
            LogHelper.printWarnLog(InjectKbHook.class, "resolveBoundKbIds", "INJECT_KB",
                    "群绑定解析失败按未绑定处理", "groupId={} 错误: {}", groupId, e.getMessage());
            return List.of();
        }
    }

    /**
     * kb 源（7.2 + 7.5）：规范化/改写 query → 多阶段检索 → 目录注入。
     * <p>检索失败返回空字符串（Hook 级容错），绝不阻塞 Agent 发言。
     */
    private String retrieveKbSafely(String query, List<Long> kbIds, Long groupId, String topicTitle) {
        try {
            String normalized = QueryNormalizer.normalize(query);
            String searchQuery = queryNormalizer.rewrite(normalized, topicTitle);
            RetrievalResult result = ragService.retrieveStructured(searchQuery, kbIds, groupId);
            return formatCatalog(result);
        } catch (Exception e) {
            LogHelper.printWarnLog(InjectKbHook.class, "retrieveKbSafely", "INJECT_KB",
                    "kb源检索失败跳过", "kbIds={} 错误: {}", kbIds, e.getMessage());
            return "";
        }
    }

    /**
     * 目录注入格式（7.5）：条目 = 分数 + 来源 + 章节 + chunkId + 摘要，token 预算内截断。
     * <p>rerank 回退（RRF 顺序）时分数是 RRF 分（数量纲 0-0.03），显示为排名序号而非误导性百分比。
     */
    private String formatCatalog(RetrievalResult result) {
        List<RetrievalCandidate> candidates = result.candidates();
        if (candidates.isEmpty()) {
            return "";
        }
        boolean rerankScored = result.fallbackReason() == null;
        int budget = ragProperties.getRetrieval().getCatalogMaxTokens();
        int used = tokenCounter.count("知识库检索目录（按相关性排序，调用 kb_read_in_redis 工具传入 chunkId 可读取全文）：\n");
        StringBuilder sb = new StringBuilder("知识库检索目录（按相关性排序，调用 kb_read_in_redis 工具传入 chunkId 可读取全文）：\n");
        int emitted = 0;
        for (int i = 0; i < candidates.size(); i++) {
            RetrievalCandidate c = candidates.get(i);
            String scoreText = rerankScored
                    ? String.format("%.2f", c.score())
                    : "#" + (i + 1);
            String entry = "[" + (i + 1) + "] (" + scoreText + ") "
                    + nullSafe(c.fileName())
                    + (c.headingPath().isEmpty() ? "" : " | 章节: " + String.join(" > ", c.headingPath()))
                    + (c.chunkId() == null ? "" : " | chunkId: " + c.chunkId())
                    + "\n     摘要: " + snippet(c.content()) + "\n";
            int tokens = tokenCounter.count(entry);
            if (used + tokens > budget) {
                break;
            }
            used += tokens;
            sb.append(entry);
            emitted++;
        }
        if (emitted == 0) {
            // 首条就超预算（异常长 chunk 元数据）：退化为纯 id 列录，保证至少可读
            sb.setLength(0);
            sb.append("知识库检索目录（调用 kb_read_in_redis 工具传入 chunkId 可读取全文）：\n");
            for (RetrievalCandidate c : candidates) {
                if (c.chunkId() != null) {
                    sb.append("chunkId: ").append(c.chunkId()).append("\n");
                }
            }
        }
        if (emitted < candidates.size() && emitted > 0) {
            sb.append("（目录已达 token 预算，剩余 ").append(candidates.size() - emitted)
                    .append(" 条未展示；提高检索精度或直接读已展示条目）\n");
        }
        return sb.toString().trim();
    }

    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String compact = QueryNormalizer.normalize(content.replace("\n", " "));
        return compact.length() > CATALOG_SNIPPET_CHARS
                ? compact.substring(0, CATALOG_SNIPPET_CHARS) + "…" : compact;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** topic 源：语义相似历史话题结论，按 topicId 缓存，异常跳过 */
    private String retrieveTopicSafely(Long topicId, String topicTitle) {
        CacheEntry cached = topicCache.get(topicId);
        if (cached != null) {
            if (System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
                return cached.text();
            }
            topicCache.remove(topicId);  // 过期
        }
        try {
            List<TopicVectorService.SimilarTopic> similar =
                    topicVectorService.findSimilarTopics(topicTitle, SIMILAR_TOP_K, SIMILAR_THRESHOLD);
            if (similar == null || similar.isEmpty()) {
                cache(topicId, "");
                return "";
            }
            List<String> conclusions = new ArrayList<>();
            for (TopicVectorService.SimilarTopic s : similar) {
                if (topicId.equals(s.topicId())) {
                    continue;  // 排除自身
                }
                topicRepository.findById(s.topicId())
                        .map(Topic::getConclusion)
                        .filter(c -> c != null && !c.isBlank())
                        .ifPresent(c -> {
                            String trimmed = c.length() > CONCLUSION_MAX_LEN
                                    ? c.substring(0, CONCLUSION_MAX_LEN) + "…" : c;
                            conclusions.add("「" + s.title() + "」：" + trimmed);
                        });
            }
            String text = conclusions.isEmpty() ? "" : "历史相似话题结论（供参考，勿直接复述）：\n"
                    + conclusions.stream().reduce((a, b) -> a + "\n" + b).orElse("");
            cache(topicId, text);
            return text;
        } catch (Exception e) {
            LogHelper.printWarnLog(InjectKbHook.class, "retrieveTopicSafely", "INJECT_KB",
                    "topic源检索失败跳过", "topicId={} 错误: {}", topicId, e.getMessage());
            return "";
        }
    }

    private void cache(Long topicId, String text) {
        if (topicCache.size() >= CACHE_MAX_SIZE) {
            topicCache.clear();  // 简单容量保护：学习场景话题量小，全清代价可忽略
        }
        topicCache.put(topicId, new CacheEntry(System.currentTimeMillis(), text));
    }

    @Override
    public String getName() {
        return "inject-kb-hook";
    }
}
