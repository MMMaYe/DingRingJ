package com.dingring.app.dto.test;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 调试接口响应：返回最终文本 + 调试辅助信息，不影响业务本身。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmDebugChatResponse {
    /** LLM 返回的完整文本（已经 trim 过） */
    private String content;
    /** 调用耗时毫秒（从进入 chat/chatStream 到返回的 wall-clock 时间） */
    private long durationMs;
    /** 返回文本的字符长度（用于快速判断是否被截断/空响应） */
    private int length;
    /** 流式模式下：收到的 delta 块数量（非流式返回 0） */
    private int deltaCount;
    /** 流式模式下：所有 delta 块按顺序拼接（非流式为 null）—— 和 content 字段等价，仅用于排查拼接异常 */
    private List<String> deltas;
}
