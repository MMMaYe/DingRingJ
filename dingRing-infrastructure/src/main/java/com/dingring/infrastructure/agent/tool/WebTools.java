package com.dingring.infrastructure.agent.tool;

import com.dingring.common.util.LogHelper;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 联网工具集（Tavily Search + Extract，一个类管理所有联网相关 @Tool）。
 * <p>两个工具构成完整 ReAct 检索链：webSearch（关键词→答案+来源列表）→
 * webFetch（URL→网页正文，摘要不够时深读）。
 * <p>调用方式决策：Tavily 无官方 Java SDK（仅 Python/JS），直接用 Spring RestClient
 * 调 REST——同步调用天然匹配工具方法顺序执行，SaaLlmFactory 已有 RestClient 使用先例。
 * <p>为什么选 Tavily：为 LLM Agent 设计的结构化搜索（url/title/content/score），
 * 支持 keyless 免费模式（api-key 留空时自动降级）；配置 Key 后走免费月度额度。
 * <p>设计约定：
 * <ul>
 *   <li>schema 最小化：webSearch 只暴露 query，webFetch 只暴露 url——参数越少模型调用越准</li>
 *   <li>结果截断：工具输出会回流进模型上下文，搜索单条 300 字/总量 2000 字，提取 3000 字</li>
 *   <li>失败返回提示语不抛异常：沿用项目工具约定，防 ReAct 循环因报错反复重试</li>
 * </ul>
 */
@Slf4j
@Component
public class WebTools {

    private static final String SEARCH_URL = "https://api.tavily.com/search";
    private static final String EXTRACT_URL = "https://api.tavily.com/extract";
    /** 每次搜索返回条数：学习项目默认 5 条平衡信息量与 token */
    private static final int MAX_RESULTS = 5;
    /** 搜索：单条摘要截断长度（字符） */
    static final int SNIPPET_MAX_LEN = 300;
    /** 搜索：格式化输出总量上限（字符） */
    static final int SEARCH_TOTAL_MAX_LEN = 2000;
    /** 提取：正文截断长度（字符）——深读场景比搜索摘要宽 */
    static final int FETCH_MAX_LEN = 3000;
    /** IO 瞬时异常重试退避（毫秒）：TLS 握手被重置类故障秒级自愈，短退避足够 */
    private static final long RETRY_BACKOFF_MS = 500;

    private final String apiKey;
    private final RestClient restClient;

    public WebTools(@Value("${dingring.tool.web-search.api-key:}") String apiKey) {
        // trim 防 yml 缩进空格导致 Bearer 头非法；空值走 keyless 免费模式
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Tool(description = "联网搜索最新信息。当问题涉及实时资讯、新闻、近期事件或模型不确定的事实时调用")
    public String webSearch(
            @ToolParam(description = "搜索关键词，建议用简洁明确的中文或英文查询词") String query) {
        try {
            // include_answer=basic：Tavily 合成的参考答案置顶返回（免费 mini-RAG）；
            // search_depth=basic：1 credit/次低延迟（advanced 2 credits 且 ~4.5s，群聊等待感明显）
            TavilySearchResponse resp = postWithRetry(SEARCH_URL, Map.of(
                    "query", query,
                    "max_results", MAX_RESULTS,
                    "search_depth", "basic",
                    "include_answer", "basic"), TavilySearchResponse.class);
            String formatted = formatSearch(resp == null ? null : resp.answer(),
                    resp == null ? null : resp.results());
            LogHelper.printLog(WebTools.class, "webSearch", "TOOL_WEB_SEARCH",
                    "联网搜索完成", "query={} 结果条数={}", query,
                    resp == null || resp.results() == null ? 0 : resp.results().size());
            return formatted;
        } catch (Exception e) {
            // 网络/限流/Key 无效统一兜底：给模型一句可自纠的提示，不阻断 ReAct
            LogHelper.printWarnLog(WebTools.class, "webSearch", "TOOL_WEB_SEARCH",
                    "联网搜索失败", "query={} 错误: {}", query, e.getMessage());
            return "联网搜索暂不可用，请基于已有知识回答";
        }
    }

    @Tool(description = "读取指定网页的正文内容。当搜索结果的摘要不足以回答问题时，用它深入阅读该网页")
    public String webFetch(
            @ToolParam(description = "要读取的网页 URL，优先使用搜索结果中返回的链接") String url) {
        try {
            // format=markdown：与 search 的 content 格式统一（保留标题/列表结构，模型消费体验一致）
            // schema 只暴露单 url：ReAct 逐步深挖模式，比批量 20 个更符合模型行为
            TavilyExtractResponse resp = postWithRetry(EXTRACT_URL,
                    Map.of("urls", List.of(url), "format", "markdown"), TavilyExtractResponse.class);
            String formatted = formatExtract(resp == null ? null : resp.results(),
                    resp == null ? null : resp.failedResults());
            LogHelper.printLog(WebTools.class, "webFetch", "TOOL_WEB_FETCH",
                    "网页读取完成", "url={} 长度={}", url, formatted.length());
            return formatted;
        } catch (Exception e) {
            LogHelper.printWarnLog(WebTools.class, "webFetch", "TOOL_WEB_FETCH",
                    "网页读取失败", "url={} 错误: {}", url, e.getMessage());
            return "网页读取暂不可用，请基于搜索摘要回答";
        }
    }

    /**
     * POST + 瞬时 IO 异常单次退避重试。
     * <p>为什么只重试 IO 类异常：本机到 Tavily 的 TLS 握手偶发被重置（日志实证同实例
     * 09:34 失败、09:43 成功），此类故障秒级自愈，一次重试能救回且代价可控；
     * 4xx/5xx 是语义错误（Key 无效/限流），重试无意义直接抛出。
     */
    private <T> T postWithRetry(String uri, Map<String, Object> body, Class<T> type) {
        try {
            return doPost(uri, body, type);
        } catch (ResourceAccessException io) {
            LogHelper.printWarnLog(WebTools.class, "postWithRetry", "TOOL_WEB_RETRY",
                    "IO瞬时异常重试", "uri={} 错误: {}", uri, io.getMessage());
            sleepQuietly(RETRY_BACKOFF_MS);
            return doPost(uri, body, type);
        }
    }

    private <T> T doPost(String uri, Map<String, Object> body, Class<T> type) {
        return restClient.post()
                .uri(uri)
                .headers(this::applyAuth)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(type);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            // 保留中断标记，让上层调用者（如虚拟线程取消）能感知
            Thread.currentThread().interrupt();
        }
    }

    /** 认证头：配置 Key 走 Bearer（免费额度），否则 keyless（有速率限制） */
    private void applyAuth(HttpHeaders headers) {
        if (apiKey.isEmpty()) {
            headers.set("X-Tavily-Access-Mode", "keyless");
        } else {
            headers.setBearerAuth(apiKey);
        }
    }

    /** 搜索结果 → 参考答案 + 来源列表；空结果返回提示语（包私有供单测） */
    static String formatSearch(String answer, List<TavilyResult> results) {
        if (results == null || results.isEmpty()) {
            return "未搜索到相关结果";
        }
        StringBuilder sb = new StringBuilder();
        if (answer != null && !answer.isBlank()) {
            sb.append("【参考答案】").append(answer.trim()).append("\n\n");
        }
        sb.append("【来源】\n");
        int total = sb.length();
        int idx = 1;
        for (TavilyResult r : results) {
            String title = r.title() == null ? "" : r.title();
            String url = r.url() == null ? "" : r.url();
            String snippet = r.content() == null ? "" : truncate(r.content(), SNIPPET_MAX_LEN);
            String entry = "[" + idx++ + "] " + title + " (" + url + ")\n" + snippet + "\n\n";
            if (total + entry.length() > SEARCH_TOTAL_MAX_LEN) {
                break; // 超总量上限直接停，不截半条
            }
            sb.append(entry);
            total += entry.length();
        }
        return sb.toString().trim();
    }

    /**
     * 提取结果 → 标题+URL+正文；失败项带回 url+error 供模型自纠（换来源或用摘要）；
     * 全空返回提示语（包私有供单测）
     */
    static String formatExtract(List<TavilyExtractResult> results, List<TavilyFailedResult> failedResults) {
        boolean noResults = results == null || results.isEmpty();
        boolean noFailed = failedResults == null || failedResults.isEmpty();
        if (noResults && noFailed) {
            return "网页内容读取失败或为空";
        }
        StringBuilder sb = new StringBuilder();
        if (!noResults) {
            for (TavilyExtractResult r : results) {
                if (sb.length() > 0) {
                    sb.append("\n\n");
                }
                String title = r.title() == null || r.title().isBlank() ? "" : r.title() + " ";
                sb.append("【网页】").append(title).append("(").append(r.url() == null ? "" : r.url())
                        .append(")\n");
                String content = r.rawContent() == null ? "" : truncate(r.rawContent(), FETCH_MAX_LEN);
                sb.append(content);
            }
        }
        if (!noFailed) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append("以下网页读取失败：\n");
            for (TavilyFailedResult f : failedResults) {
                sb.append("- ").append(f.url() == null ? "" : f.url())
                        .append("（").append(f.error() == null ? "未知原因" : f.error()).append("）\n");
            }
            sb.append("请尝试其他来源链接，或基于搜索摘要回答。");
        }
        return sb.toString().trim();
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Tavily /search 响应（answer 为合成参考答案；favicon/raw_content/id 等字段 Jackson 默认忽略） */
    record TavilySearchResponse(String answer, List<TavilyResult> results) {}

    /** 单条搜索结果 */
    record TavilyResult(String title, String url, String content, Double score) {}

    /** Tavily /extract 响应（failed_results 为提取失败的 URL 及原因） */
    record TavilyExtractResponse(List<TavilyExtractResult> results,
                                 @JsonProperty("failed_results") List<TavilyFailedResult> failedResults) {}

    /** 单条提取结果（title 来自用户调研响应确认存在；raw_content 为 snake_case 需显式映射） */
    record TavilyExtractResult(String title, String url,
                               @JsonProperty("raw_content") String rawContent) {}

    /** 提取失败项 */
    record TavilyFailedResult(String url, String error) {}
}
