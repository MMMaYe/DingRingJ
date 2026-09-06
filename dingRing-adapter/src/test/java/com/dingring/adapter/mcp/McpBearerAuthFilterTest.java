package com.dingring.adapter.mcp;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link McpBearerAuthFilter} P3 用例（3.4.3 安全面）：
 * 未配置 token 全拒、错误 token 401、正确 token 放行、请求体超限 413。
 */
@DisplayName("MCP Bearer 鉴权过滤器")
class McpBearerAuthFilterTest {

    @Test
    @DisplayName("未配置 token：拒绝所有请求（显式配置才可用）")
    void shouldRejectAllWhenTokenNotConfigured() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("", 1024);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("无 Authorization 头：401")
    void shouldRejectMissingHeader() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("secret-token", 1024);
        MockHttpServletResponse response = execute(filter, null);

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("错误 token：401")
    void shouldRejectWrongToken() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("secret-token", 1024);
        MockHttpServletResponse response = execute(filter, "Bearer wrong");

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("正确 Bearer token：放行到过滤器链")
    void shouldPassWithCorrectToken() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("secret-token", 1024);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull(); // 已放行到下游
    }

    @Test
    @DisplayName("请求体超过上限：413（不进 JSON 解析即拒绝）")
    void shouldRejectOversizedBody() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("secret-token", 1024);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer secret-token");
        request.setContent(new byte[2048]);
        request.addHeader("Content-Length", "2048");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // MockHttpServletRequest.getContentLengthLong 基于已 set 的 content 计算
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull(); // 未放行
    }

    @Test
    @DisplayName("token 前后空白容忍（trim 后比较）")
    void shouldTrimToken() throws Exception {
        McpBearerAuthFilter filter = new McpBearerAuthFilter("  secret-token  ", 1024);
        MockHttpServletResponse response = execute(filter, "Bearer secret-token");

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse execute(McpBearerAuthFilter filter, String authorization)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
