package com.dingring.domain.service;

import com.dingring.domain.agent.Agent;

import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 调用接口（依赖倒置，infrastructure 层用 Spring AI 实现）。
 */
public interface LlmService {

    /**
     * 以指定 Agent 的 LLM 端点发起一次对话补全。
     *
     * @param agent        Agent 配置（端点、模型、温度等）
     * @param systemPrompt 系统提示词
     * @param messages     对话消息（时间升序）
     * @return LLM 生成内容（可能为空字符串 = Agent 选择不发言）
     */
    String chat(Agent agent, String systemPrompt, List<ChatTurn> messages);

    /**
     * 带单次参数覆盖的对话补全：意图分类等确定性任务用低温/小 maxTokens/短超时，
     * 避免复用 Agent 会话参数（temperature 0.7、读超时 120s）导致判定抖动或长时间卡住调用方。
     * <p>默认实现忽略覆盖参数回退 {@link #chat}（Mock 等实现无需感知）。
     *
     * @param options 单次调用参数覆盖（null = 完全沿用 Agent 配置）
     */
    default String chat(Agent agent, String systemPrompt, List<ChatTurn> messages, CallOptions options) {
        return chat(agent, systemPrompt, messages);
    }

    /**
     * 流式对话补全：逐块回调 {@code onDelta}，返回拼接后的完整内容（落库语义与 {@link #chat} 一致）。
     * <p>默认实现回退非流式：不支持流式的实现/场景下整段返回，不触发 delta 回调。
     *
     * @param onDelta 逐块内容回调（原始 chunk，未做任何标记过滤）
     */
    default String chatStream(Agent agent, String systemPrompt, List<ChatTurn> messages, Consumer<String> onDelta) {
        return chat(agent, systemPrompt, messages);
    }

    /**
     * 单条对话消息。
     *
     * @param role    USER / ASSISTANT
     * @param content 内容（含发送者花名前缀，形成群聊语境）
     */
    record ChatTurn(String role, String content) {

        public static ChatTurn user(String content) {
            return new ChatTurn("USER", content);
        }

        public static ChatTurn assistant(String content) {
            return new ChatTurn("ASSISTANT", content);
        }
    }

    /**
     * 单次调用参数覆盖。字段为 null（或超时 &le;0）表示沿用 Agent 配置/全局默认。
     *
     * @param temperature        采样温度（分类任务建议 0）
     * @param maxTokens          生成上限（分类输出为小 JSON，建议限小）
     * @param readTimeoutSeconds 读超时秒数（短超时避免卡死调用方线程）
     * @param jsonMode           是否强制 JSON 输出（API 层 response_format=json_object）
     * @param logReasoning       是否打印推理过程（推理模型的 reasoning_content，非推理模型无效果）
     */
    record CallOptions(Double temperature, Integer maxTokens, Long readTimeoutSeconds,
                       Boolean jsonMode, Boolean logReasoning) {

        /** 向后兼容：3 参构造器，jsonMode=false, logReasoning=false */
        public CallOptions(Double temperature, Integer maxTokens, Long readTimeoutSeconds) {
            this(temperature, maxTokens, readTimeoutSeconds, false, false);
        }
    }
}
