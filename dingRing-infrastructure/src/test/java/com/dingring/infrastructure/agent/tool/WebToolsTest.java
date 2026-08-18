package com.dingring.infrastructure.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebTools 纯函数单测：搜索/提取结果的格式化与截断。
 * HTTP 调用链路由 debug 端点（/api/test/tool/web-search|web-fetch）手动验证，不在此 mock。
 */
class WebToolsTest {

    private WebTools.TavilyResult result(String title, String url, String content) {
        return new WebTools.TavilyResult(title, url, content, 0.9);
    }

    // ---------- webSearch: formatSearch ----------

    @Test
    void 搜索_空结果返回提示语() {
        assertEquals("未搜索到相关结果", WebTools.formatSearch(null, null));
        assertEquals("未搜索到相关结果", WebTools.formatSearch(null, List.of()));
    }

    @Test
    void 搜索_参考答案置顶_后接来源列表() {
        String out = WebTools.formatSearch("MCP 是模型上下文协议", List.of(
                result("AI 新闻", "https://a.com", "今天发布了新模型")));
        assertTrue(out.startsWith("【参考答案】MCP 是模型上下文协议"));
        assertTrue(out.contains("【来源】"));
        assertTrue(out.contains("[1] AI 新闻 (https://a.com)"));
        assertTrue(out.contains("今天发布了新模型"));
    }

    @Test
    void 搜索_无answer时只有来源列表() {
        String out = WebTools.formatSearch(null, List.of(
                result("t", "https://a.com", "c")));
        assertFalse(out.contains("【参考答案】"));
        assertTrue(out.startsWith("【来源】"));
    }

    @Test
    void 搜索_单条摘要超300字截断加省略号() {
        String longContent = "字".repeat(400);
        String out = WebTools.formatSearch(null, List.of(
                result("t", "https://a.com", longContent)));
        assertTrue(out.contains("字".repeat(300) + "..."));
        assertFalse(out.contains("字".repeat(301)));
    }

    @Test
    void 搜索_总量超2000字提前停止() {
        // 10 条 × 每条约 350 字 > 2000 上限，输出必须被截停
        List<WebTools.TavilyResult> many = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> result("t" + i, "https://a.com/" + i, "字".repeat(350)))
                .toList();
        String out = WebTools.formatSearch(null, many);
        assertTrue(out.length() <= 2000 + 100); // 余量容纳【来源】等头部行
    }

    // ---------- webFetch: formatExtract ----------

    @Test
    void 提取_正常结果带标题URL与正文() {
        String out = WebTools.formatExtract(List.of(
                new WebTools.TavilyExtractResult("什么是MCP？", "https://a.com", "正文内容第一段")),
                null);
        assertTrue(out.contains("【网页】什么是MCP？ (https://a.com)"));
        assertTrue(out.contains("正文内容第一段"));
    }

    @Test
    void 提取_全空返回提示语() {
        assertEquals("网页内容读取失败或为空", WebTools.formatExtract(null, null));
        assertEquals("网页内容读取失败或为空", WebTools.formatExtract(List.of(), List.of()));
    }

    @Test
    void 提取_失败结果带回URL与原因供模型自纠() {
        String out = WebTools.formatExtract(List.of(),
                List.of(new WebTools.TavilyFailedResult("https://a.com", "404 Not Found")));
        assertTrue(out.contains("https://a.com"));
        assertTrue(out.contains("404 Not Found"));
    }

    @Test
    void 提取_正文超3000字截断() {
        String longContent = "字".repeat(5000);
        String out = WebTools.formatExtract(List.of(
                new WebTools.TavilyExtractResult("t", "https://a.com", longContent)), null);
        assertTrue(out.contains("字".repeat(3000) + "..."));
        assertFalse(out.contains("字".repeat(3001)));
    }
}
