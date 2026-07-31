package com.dingring.app.orchestrator;

import com.dingring.common.constant.PromptConstants;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消息路由器：对每条用户消息做一次 LLM 意图判定（CHAT 闲聊 / DISCUSS 讨论 / CONCLUDE 收束）。
 * <p>DISCUSS 时顺带产出话题标题与置信度（追溯式建题依据）；判定失败降级 CHAT，不阻塞主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageRouter {

    /** 消息意图 */
    public enum Intent { CHAT, DISCUSS, CONCLUDE }

    /** 判定置信度 */
    public enum Confidence { HIGH, LOW }

    /** 路由结果（topicTitle 仅 DISCUSS 时有意义） */
    public record Route(Intent intent, String topicTitle, Confidence confidence) {

        public static Route chat() {
            return new Route(Intent.CHAT, "", Confidence.LOW);
        }
    }

    /** 标题兜底截断长度 */
    private static final int TITLE_FALLBACK_LEN = 20;

    /** 分类输出为小 JSON，限制生成上限控制成本 */
    private static final int ROUTE_MAX_TOKENS = 256;

    /** 分类要确定性输出，不复用 Agent 会话温度（默认 0.7 会导致判定抖动） */
    private static final double ROUTE_TEMPERATURE = 0.0;

    /** 路由读超时秒数：路由在群串行执行器内同步调用，短超时避免卡住全群 */
    @Value("${dingring.orchestrator.route-timeout-seconds:15}")
    private long routeTimeoutSeconds;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    /**
     * 路由一条用户消息。
     *
     * @param judge            执行判定的 Agent（@提及时用被 @ 者，否则群首个成员）
     * @param content          用户消息原文
     * @param activeTopicTitle 当前活跃主题标题（null = 无活跃主题，此时 CONCLUDE 无从谈起）
     * @return 判定失败降级为 CHAT/LOW
     */
    public Route route(Agent judge, String content, String activeTopicTitle) {
        try {
            String userInput = activeTopicTitle == null
                    ? "（当前群里没有进行中的讨论主题）\n用户消息：" + content
                    : "（当前群里正在讨论主题「" + activeTopicTitle + "」）\n用户消息：" + content;
            String raw = llmService.chat(judge, PromptConstants.INTENT_CLASSIFIER,
                    List.of(LlmService.ChatTurn.user(userInput)),
                    new LlmService.CallOptions(ROUTE_TEMPERATURE, ROUTE_MAX_TOKENS, routeTimeoutSeconds));
            Route route = parse(raw, content);
            LogHelper.printLog(log, "MessageRouter.route", "消息路由判定",
                    "judge=%s intent=%s confidence=%s topicTitle=%s 原文=%s",
                    judge.getName(), route.intent(), route.confidence(), route.topicTitle(), content);
            return route;
        } catch (Exception e) {
            // 判定失败降级闲聊：宁可少建题，不可乱建题
            LogHelper.printWarnLog(log, "MessageRouter.route", "路由判定失败降级CHAT", "judge=" + judge.getName(), e);
            return Route.chat();
        }
    }

    /** 解析 LLM 输出（容忍 ```json 包裹与前后杂文）；解析失败抛异常由上层降级 */
    private Route parse(String raw, String originalContent) throws Exception {
        String json = raw.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("路由输出不含 JSON: " + raw);
        }
        JsonNode node = objectMapper.readTree(json.substring(start, end + 1));
        Intent intent = Intent.valueOf(node.path("intent").asText().trim().toUpperCase());
        Confidence confidence;
        try {
            confidence = Confidence.valueOf(node.path("confidence").asText().trim().toUpperCase());
        } catch (Exception ignore) {
            confidence = Confidence.LOW;
        }
        String title = node.path("topicTitle").asText("").trim();
        if (intent == Intent.DISCUSS && title.isBlank()) {
            // 标题兜底：截取用户消息前 N 字
            title = originalContent.length() > TITLE_FALLBACK_LEN
                    ? originalContent.substring(0, TITLE_FALLBACK_LEN)
                    : originalContent;
        }
        return new Route(intent, title, confidence);
    }
}
