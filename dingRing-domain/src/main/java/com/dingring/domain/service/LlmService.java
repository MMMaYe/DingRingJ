package com.dingring.domain.service;

import com.dingring.domain.agent.Agent;

import java.util.List;

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
}
