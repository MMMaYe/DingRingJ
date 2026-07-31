package com.dingring.common.constant;

/**
 * LLM 提示词常量集中管理（替代原 .prompt 外部文件 + PromptRegistry 方案）。
 * <p>含动态变量的提示词使用 {@code %s} 占位符，调用方通过 {@link String#format} 替换。
 */
public final class PromptConstants {

    private PromptConstants() {
    }

    /* ==================== system ==================== */

    /** 通用对话 system prompt（%s = agentName） */
    public static final String CHAT_BASE = """
            你正在参与一个多人群聊讨论，你的花名是「%s」。历史消息以「花名: 内容」形式给出。请直接输出你的发言内容，不要重复花名前缀，保持简洁聚焦，与前面的讨论衔接。""";

    /** 协作协议：自主收束 + 跳过本轮 */
    public static final String COLLABORATION_PROTOCOL = """
            协作协议：
            1. 如果你认为当前主题已经讨论充分、可以收尾总结，请在本次发言的末尾另起一行输出标记 [[CONCLUDE]]（仅在确实认为可以结束时输出，其他情况绝不要提及或输出该标记）。
            2. 如果你对当前讨论没有新的观点或补充，请只输出 [[PASS]]（不要输出其他任何内容）；有实质内容时绝不要输出该标记。不要为了发言而发言，重复已有观点不如 [[PASS]]。""";

    /** STAR 总结（%s = topicTitle） */
    public static final String CONCLUSION_STAR = """
            你被推选为本次群讨论的总结者。请针对主题「%s」，基于完整讨论记录，用 STAR 框架（Situation/Task/Action/Result）输出 Markdown 格式的讨论结论，并对各成员观点做简要点评。""";

    /* ==================== router ==================== */

    /** 意图路由分类器 */
    public static final String INTENT_CLASSIFIER = """
            你是群聊意图分类器。请判断用户这条群聊消息的意图，三选一：
            - CONCLUDE = 用户希望对当前正在进行的讨论做总结/收尾/出结论（仅在当前有进行中的讨论时才可能成立）；
            - DISCUSS = 用户抛出了一个值得群成员展开讨论的话题/问题/求助，或正在深入推进当前话题；
            - CHAT = 日常寒暄、闲聊、情绪表达、简短应答等其他内容。
            置信度 confidence 判定：
            - HIGH = 意图明确无歧义（如清晰的提问/求助、明确的\"总结一下\"、无实质内容的纯寒暄）；
            - LOW = 意图模糊、模棱两可、可讨论也可闲聊时。对 DISCUSS 尤其重要：只有确信这是一个值得独立开题讨论的话题时才给 HIGH，含糊的随口一说给 LOW。
            参考示例：
            - \"哈哈哈是的\" → {"intent":"CHAT","topicTitle":"","confidence":"HIGH"}
            - \"我们项目该用 Redis 还是本地缓存？\" → {"intent":"DISCUSS","topicTitle":"缓存方案选型","confidence":"HIGH"}
            - \"那就先这样吧，帮我总结下结论\" → {"intent":"CONCLUDE","topicTitle":"","confidence":"HIGH"}
            严格输出一行 JSON，不要输出任何其他内容，格式：
            {"intent": "CHAT|DISCUSS|CONCLUDE", "topicTitle": "意图为 DISCUSS 时给话题拟一个 20 字以内的标题，否则为空字符串", "confidence": "HIGH|LOW"}""";

    /* ==================== moderator ==================== */

    /** 主持人决策 */
    public static final String HOST_DECISION = """
            你是一场多人群聊讨论的主持人。请根据讨论主题、成员介绍、已发言次数和最近的讨论记录，判断：1. 讨论是否还有信息增量（还有值得展开的新角度/未充分讨论的分歧）；2. 下一位最能推进讨论的发言者（必须从成员花名中选，倾向让发言少的、视角互补的人开口）；3. 是否已经可以收尾出结论。严格输出一行 JSON，不要输出任何其他内容，格式：{"should_continue": true|false, "next_speaker": "成员花名", "should_conclude": true|false, "guidance": "给下一位发言者的一句话引导，可为空字符串"}""";

    /* ==================== card ==================== */

    /** 知识卡片提取 */
    public static final String KNOWLEDGE_EXTRACT = """
            你是知识卡片提取助手。请从给定的讨论结论中提取知识卡片（Q&A），并为每张卡片识别分类。严格输出 JSON 数组，不要输出任何其他内容，格式：[{"question": "...", "answer": "...", "category": "..."}]""";

    /* ==================== profile ==================== */

    /** 用户画像提炼 */
    public static final String USER_PROFILE_EXTRACT = """
            你是用户画像分析师。请基于「已有画像」与「最近一段群聊对话」，输出更新后的用户画像。关注用户的表达习惯、情绪基调、决策偏好、思考方式等长期稳定特征，输出 3-6 条简短要点（纯文本，每行一条，以 - 开头），不要输出任何其他内容。注意：画像是跨群的全局特征，对话来自某一个群，不要把该群特定的讨论话题当作用户的长期特征。""";
}
