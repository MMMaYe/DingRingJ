package com.dingring.adapter.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * MCP 端点 Bearer 鉴权过滤器（3.4.3 安全面）。
 * <p>MCP 提交通道是新的内容注入面——提交内容最终会进入 embedding 与 Agent 上下文，
 * 风险高于只读接口，必须强制鉴权，禁止匿名访问。
 * <p>token 走 DINGRING_MCP_TOKEN 环境变量（不写入 git 与配置默认值）；
 * 未配置 token 时拒绝所有请求——"未配置即可用"等于匿名开放，安全语义必须是显式配置才可用。
 * <p>比较用常量时间算法（MessageDigest.isEqual）防时序攻击逐字符猜测。
 * <p>请求体大小上限与清洗输出上限（3.2）同口径：超限直接 413，
 * 避免超大提交进入 JSON 解析与 LLM 校验才被拒。
 */
public class McpBearerAuthFilter implements Filter {

    /** Bearer 前缀（RFC 6750） */
    private static final String BEARER_PREFIX = "Bearer ";
    /** 请求体字节上限（默认 2MB：覆盖 max-output-chars=500000 字符的 UTF-8 最坏 3 字节 + JSON 包装） */
    private final long maxRequestBytes;

    private final byte[] expectedTokenBytes;

    public McpBearerAuthFilter(String token, long maxRequestBytes) {
        this.expectedTokenBytes = token == null ? new byte[0]
                : token.trim().getBytes(StandardCharsets.UTF_8);
        this.maxRequestBytes = maxRequestBytes;
    }

    /** token 是否已配置（未配置 = 鉴权面完全关闭，全部拒绝） */
    public boolean isConfigured() {
        return expectedTokenBytes.length > 0;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpReq)
                || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }

        if (!isConfigured()) {
            reject(httpResp, HttpServletResponse.SC_UNAUTHORIZED,
                    "MCP 端点未配置鉴权 token（DINGRING_MCP_TOKEN），拒绝访问");
            return;
        }

        long contentLength = httpReq.getContentLengthLong();
        if (contentLength > maxRequestBytes) {
            reject(httpResp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "请求体超过上限: " + contentLength + " > " + maxRequestBytes);
            return;
        }

        String authorization = httpReq.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)
                || !tokenMatches(authorization.substring(BEARER_PREFIX.length()))) {
            // MCP 客户端（TRAE / Claude Code）配置 headers.Authorization: Bearer <token>
            reject(httpResp, HttpServletResponse.SC_UNAUTHORIZED, "无效的 Bearer token");
            return;
        }

        chain.doFilter(request, response);
    }

    /** 常量时间比较：不同长度直接返回 false 之外仍要求比较耗时与长度无关的前置校验 */
    private boolean tokenMatches(String presented) {
        byte[] presentedBytes = presented == null ? new byte[0]
                : presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedTokenBytes, presentedBytes);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
