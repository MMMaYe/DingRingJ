package com.dingring.adapter.rest;

import com.dingring.common.response.ApiResponse;
import com.dingring.infrastructure.agent.tool.WebTools;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool 调试专用 REST API。
 *
 * <p>目的：不经 LLM 直接调用工具方法，用 Postman 验证 Tavily 连通性、Key 有效性、
 * 格式化与截断输出。与 TestController（LLM 调试）同用 dingring.debug.llm.enabled 开关，
 * 生产配置必须关闭。
 */
@RestController
@RequestMapping("/api/test/tool")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dingring.debug.llm.enabled", havingValue = "true")
public class ToolTestController {

    private final WebTools webTools;

    /** GET /api/test/tool/web-search?query=今天AI新闻 */
    @GetMapping("/web-search")
    public ApiResponse<Map<String, Object>> webSearch(@RequestParam String query) {
        long start = System.currentTimeMillis();
        String result = webTools.webSearch(query);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("result", result);
        body.put("length", result == null ? 0 : result.length());
        body.put("durationMs", System.currentTimeMillis() - start);
        return ApiResponse.ok(body);
    }

    /** GET /api/test/tool/web-fetch?url=https://example.com */
    @GetMapping("/web-fetch")
    public ApiResponse<Map<String, Object>> webFetch(@RequestParam String url) {
        long start = System.currentTimeMillis();
        String result = webTools.webFetch(url);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("url", url);
        body.put("result", result);
        body.put("length", result == null ? 0 : result.length());
        body.put("durationMs", System.currentTimeMillis() - start);
        return ApiResponse.ok(body);
    }
}
