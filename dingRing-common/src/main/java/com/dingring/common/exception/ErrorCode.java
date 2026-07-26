package com.dingring.common.exception;

import lombok.Getter;

/**
 * 统一错误码定义（见技术方案 6.9 错误处理约定）。
 */
@Getter
public enum ErrorCode {

    PARAM_INVALID(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    TOPIC_ALREADY_IN_PROGRESS(409, "当前群已有进行中的主题，请先结束当前讨论"),
    TOPIC_NOT_IN_PROGRESS(409, "主题非进行中状态"),
    TOPIC_CONCLUSION_FAILED(500, "结论生成失败"),
    ALL_AGENTS_FAILED(503, "所有 Agent 暂时不可用，请稍后重试"),
    LLM_API_ERROR(502, "LLM API 调用异常"),
    INTERNAL_ERROR(500, "系统内部错误");

    /** 对应的 HTTP 状态码 */
    private final int httpStatus;
    /** 默认提示信息 */
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
