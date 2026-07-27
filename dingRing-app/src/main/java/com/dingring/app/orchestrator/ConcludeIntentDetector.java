package com.dingring.app.orchestrator;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户总结意图判定：用户 @ 某个 Agent 时，用 LLM 判断该消息是否在请它对当前讨论做总结。
 * <p>判定用被 @ 的 Agent 自身模型执行（零额外配置）；判定失败降级为普通调度，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConcludeIntentDetector {

    private static final String SYSTEM_PROMPT = """
            你是群聊意图分类器。用户在群里 @ 了某位成员并发了一条消息，\
            请判断这条消息是否在请求被 @ 的成员对当前讨论进行总结/收尾/结论陈词。\
            只输出 YES 或 NO，不要输出任何其他内容。""";

    private final LlmService llmService;

    /**
     * @param judge   被 @ 的 Agent（用它的模型做判定）
     * @param content 用户消息原文
     * @return true = 用户在请该 Agent 总结
     */
    public boolean isConcludeIntent(Agent judge, String content) {
        try {
            String verdict = llmService.chat(judge, SYSTEM_PROMPT,
                    List.of(LlmService.ChatTurn.user(content)));
            if (verdict == null) {
                return false;
            }
            String normalized = verdict.strip().toUpperCase();
            boolean yes = normalized.startsWith("YES")
                    || (!normalized.startsWith("NO") && normalized.contains("YES"));
            log.info("总结意图判定: agent={}, verdict={}, result={}", judge.getName(), verdict.strip(), yes);
            return yes;
        } catch (Exception e) {
            // 判定失败不影响主流程：降级为普通调度
            log.warn("总结意图判定失败，降级为普通调度, agent={}", judge.getName(), e);
            return false;
        }
    }
}
