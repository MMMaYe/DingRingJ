package com.dingring.adapter.mcp;

import com.dingring.common.util.LogHelper;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Server 装配（3.4 外部执行方通道）。
 * <p>传输层由 spring-ai-starter-mcp-server-webmvc 自动配置：
 * spring.ai.mcp.server.protocol=STREAMABLE 时暴露 /mcp 端点（Streamable HTTP）。
 * 本类只做三件事：
 * <ul>
 *   <li>注册唯一工具 kb_cleaning_submit（ToolCallbackProvider 会被
 *       ToolCallbackConverterAutoConfiguration 聚合进 MCP 工具清单）；</li>
 *   <li>注册 /mcp 的 Bearer 鉴权过滤器（token 走 DINGRING_MCP_TOKEN 环境变量）；</li>
 *   <li>随 dingring.rag.enabled 联动：RAG 关闭时工具与过滤器都不装配，
 *       yml 中 spring.ai.mcp.server.enabled 引用同一开关，端点也不启动。</li>
 * </ul>
 * <p>刻意不注册 WebTools 等其他工具 Bean：清洗提交通道只收结果，
 * 联网搜索等 Agent 内部工具不外泄给 MCP 客户端。
 */
@Configuration
@ConditionalOnProperty(name = "dingring.rag.enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfig {

    /** MCP 端点路径（与 spring.ai.mcp.server.streamable-http.mcp-endpoint 默认值一致） */
    static final String MCP_ENDPOINT = "/mcp";

    @Bean
    public ToolCallbackProvider kbCleaningToolCallbackProvider(KbCleaningMcpTool tool) {
        return MethodToolCallbackProvider.builder().toolObjects(tool).build();
    }

    /**
     * Bearer 鉴权过滤器：仅拦 /mcp。
     * <p>FilterRegistrationBean 显式指定 urlPatterns，避免拦截业务 REST/WS 流量；
     * 顺序设 1（最早执行，先于任何业务过滤器）。
     */
    @Bean
    public FilterRegistrationBean<McpBearerAuthFilter> mcpBearerAuthFilter(
            @Value("${DINGRING_MCP_TOKEN:}") String token,
            @Value("${dingring.mcp.server.max-request-bytes:2097152}") long maxRequestBytes) {
        McpBearerAuthFilter filter = new McpBearerAuthFilter(token, maxRequestBytes);
        FilterRegistrationBean<McpBearerAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(MCP_ENDPOINT);
        registration.setOrder(1);
        registration.setName("mcpBearerAuthFilter");
        LogHelper.printLog(McpServerConfig.class, "mcpBearerAuthFilter", "KB_MCP_SERVER",
                "MCP 鉴权过滤器已注册", "endpoint={} tokenConfigured={} maxRequestBytes={}",
                MCP_ENDPOINT, filter.isConfigured(), maxRequestBytes);
        return registration;
    }
}
