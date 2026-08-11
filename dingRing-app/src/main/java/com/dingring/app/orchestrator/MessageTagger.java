package com.dingring.app.orchestrator;

import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageTag;
import com.dingring.domain.group.SenderType;
import org.springframework.stereotype.Component;

/**
 * 消息标签器：消息入库后打标签（零 LLM 规则判定），观点驱动而非长度驱动。
 * <p>设计要点（方案 6.3.6）：
 * <ul>
 *   <li>用户消息永远标记为 KEY，原文即观点，不摘要</li>
 *   <li>明显无观点（纯标点/emoji/极短应答）标记为 NOISE，不摘要，零 LLM 调用</li>
 *   <li>其余 Agent 发言标记为 VIEWPOINT，由 {@link com.dingring.app.event.MessageSummaryHandler}
 *       异步调 LLM 判断是否有观点并生成摘要</li>
 * </ul>
 * <p>与旧方案差异：不再用长度阈值（>100字=SUBSTANTIVE）判断是否摘要，
 * 因为 50 字的清晰观点也应该摘要，150 字的废话不应该摘要。
 */
@Component
public class MessageTagger {

    public MessageTag tag(GroupMessage msg) {
        // 用户消息：永远 KEY
        if (msg.getSenderType() == SenderType.USER) {
            return MessageTag.KEY;
        }
        String content = msg.getContent();
        if (content == null || content.isBlank()) {
            return MessageTag.NOISE;
        }
        // 明显无观点：纯标点/emoji/极短应答（零 LLM 调用）
        if (isObviousNoise(content)) {
            return MessageTag.NOISE;
        }
        // 其他所有发言：交给 LLM 判断是否有观点 + 摘要
        return MessageTag.VIEWPOINT;
    }

    /**
     * 规则判断明显噪音：去除空白/标点/emoji 后不足 5 字符。
     * 如"同意""+1""哈哈""对"等，无需 LLM 判断。
     * <p>{\p{So}} 覆盖 emoji（符号类字符），避免"😂😂😂"这类纯表情消息进 LLM 浪费调用。
     */
    private boolean isObviousNoise(String content) {
        String stripped = content.replaceAll("[\\s\\p{Punct}\\p{So}]", "");
        return stripped.length() < 5;
    }
}
