package com.dingring.domain.service;

import com.dingring.domain.agent.Agent;

/**
 * 用户画像服务（依赖倒置，infrastructure 层实现）。
 * <p>画像是用户维度的跨群全局记忆：同一个人在不同群里性格/习惯/思考方式不变。
 */
public interface ProfileService {

    /**
     * 读取用户的全局画像文本。
     *
     * @return 无画像时返回空字符串
     */
    String getProfile(Long userId);

    /**
     * LLM 读近期对话，与既有全局画像增量合并后覆盖写回。
     * <p>用户维度串行（同一时刻只跑一个提炼任务，防多群同时触发互相覆盖）；失败仅日志留痕。
     *
     * @param extractor      执行提炼的 Agent（用它的模型）
     * @param groupName      对话来源群名（语境提示，避免群特定话题污染全局特征）
     * @param recentDialogue 近期对话文本（「花名: 内容」逐行）
     */
    void extractAndMerge(Long userId, Agent extractor, String groupName, String recentDialogue);
}
