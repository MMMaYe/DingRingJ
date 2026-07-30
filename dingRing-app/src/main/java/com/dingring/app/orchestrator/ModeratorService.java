package com.dingring.app.orchestrator;

import com.dingring.app.service.MessageAssembler;
import com.dingring.common.constant.PromptConstants;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Moderator 主持人（Phase 2）：讨论态每轮发言前做一次 LLM 全局判断，
 * 替代 Phase 1 的机械判断（评分调度选人 / PASS 计数收敛 / Agent 自报收束）。
 * <p>降级链哲学：默认关（{@code dingring.moderator.enabled=false}）；
 * 判定失败/解析失败/花名不在群内均回退 Phase 1 机制，本轮照常推进。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModeratorService {

    /**
     * 主持人决策。
     *
     * @param shouldContinue false = 讨论已无信息增量，应收束
     * @param nextSpeakerId  下一位发言 Agent（null = 花名无效，回退评分调度）
     * @param shouldConclude true = 主持人判断可以出结论了
     * @param guidance       给下一位发言者的一句话引导（注入 system prompt 尾部，可为空）
     */
    public record Decision(boolean shouldContinue, Long nextSpeakerId,
                           boolean shouldConclude, String guidance) {

        /** 任一条件满足即收束（与 Agent 侧 [[CONCLUDE]] 双保险） */
        public boolean wantsConclude() {
            return shouldConclude || !shouldContinue;
        }
    }

    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final MessageRepository messageRepository;
    private final MessageAssembler messageAssembler;

    /** 主持人开关：false 时完全走 Phase 1 评分调度 */
    @Value("${dingring.moderator.enabled:false}")
    private boolean enabled;

    /** 主持人判定模型（空 = 复用群首个成员 Agent 的模型；建议配快模型） */
    @Value("${dingring.moderator.model:}")
    private String model;

    /** 主持人判定输入的讨论消息窗口 */
    @Value("${dingring.moderator.context-window:30}")
    private int contextWindow;

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 主持一轮判断。
     *
     * @return null = 未开启或判定失败（调用方回退 Phase 1 机制）
     */
    public Decision decide(Topic topic, List<Agent> members, Map<Long, Long> speakCounts) {
        if (!enabled || members.isEmpty()) {
            return null;
        }
        try {
            String raw = llmService.chat(moderatorAgent(members.get(0)), PromptConstants.HOST_DECISION,
                    List.of(LlmService.ChatTurn.user(buildInput(topic, members, speakCounts))));
            Decision decision = parse(raw, members);
            log.info("Moderator 决策: topicId={}, shouldContinue={}, nextSpeakerId={}, shouldConclude={}, guidance={}",
                    topic.getId(), decision.shouldContinue(), decision.nextSpeakerId(),
                    decision.shouldConclude(), decision.guidance());
            return decision;
        } catch (Exception e) {
            log.warn("Moderator 判定失败，回退评分调度, topicId={}", topic.getId(), e);
            return null;
        }
    }

    /** 主持人身份：借用群首个成员的 LLM 端点，模型可独立配置，不占用成员人设 */
    private Agent moderatorAgent(Agent connection) {
        Agent host = new Agent();
        host.setId(connection.getId());
        host.setName("主持人");
        host.setBaseUrl(connection.getBaseUrl());
        host.setApiKey(connection.getApiKey());
        host.setModelName(model == null || model.isBlank() ? connection.getModelName() : model);
        return host;
    }

    private String buildInput(Topic topic, List<Agent> members, Map<Long, Long> speakCounts) {
        String roster = members.stream()
                .map(a -> "- " + a.getName()
                        + (a.getDescription() == null || a.getDescription().isBlank()
                                ? "" : "（" + a.getDescription() + "）")
                        + "，已发言 " + speakCounts.getOrDefault(a.getId(), 0L) + " 次")
                .collect(Collectors.joining("\n"));
        List<GroupMessage> recent = messageRepository.findRecentByTopicId(topic.getId(), contextWindow);
        String dialogue = recent.stream()
                .map(m -> messageAssembler.resolveSenderName(m) + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
        return "讨论主题：「" + topic.getTitle() + "」\n\n成员：\n" + roster
                + "\n\n最近讨论记录：\n" + (dialogue.isBlank() ? "（暂无发言）" : dialogue);
    }

    /** 解析 LLM 输出（容忍 ```json 包裹与前后杂文）；解析失败抛异常由上层降级 */
    private Decision parse(String raw, List<Agent> members) throws Exception {
        String json = raw.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Moderator 输出不含 JSON: " + raw);
        }
        JsonNode node = objectMapper.readTree(json.substring(start, end + 1));
        boolean shouldContinue = node.path("should_continue").asBoolean(true);
        boolean shouldConclude = node.path("should_conclude").asBoolean(false);
        String speakerName = node.path("next_speaker").asText("").trim();
        Long nextSpeakerId = members.stream()
                .filter(a -> a.getName().equals(speakerName))
                .map(Agent::getId)
                .findFirst().orElse(null);
        if (nextSpeakerId == null && !speakerName.isBlank()) {
            log.warn("Moderator 指定的发言者不在群内，回退评分调度, next_speaker={}", speakerName);
        }
        String guidance = node.path("guidance").asText("").trim();
        return new Decision(shouldContinue, nextSpeakerId, shouldConclude, guidance);
    }
}
