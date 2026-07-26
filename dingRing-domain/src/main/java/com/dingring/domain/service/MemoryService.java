package com.dingring.domain.service;

/**
 * 记忆检索接口（依赖倒置，infrastructure 层实现）。
 * v1.0：直接拼同群历史 Topic 的结论。
 */
public interface MemoryService {

    /**
     * 检索群的历史记忆（历史 Topic 结论），返回可直接拼入 Prompt 的文本。
     *
     * @return 无历史记忆时返回空字符串
     */
    String retrieveMemory(Long groupId);
}
