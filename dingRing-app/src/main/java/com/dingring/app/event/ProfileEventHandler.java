package com.dingring.app.event;

import com.dingring.app.service.GroupAppService;
import com.dingring.app.service.MessageAssembler;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 画像域：监听 TopicClosed，从本次讨论对话中提炼用户画像（独立虚拟线程，失败不影响主流程）。
 * <p>闲聊场景的画像提炼由 DiscussionEngine 的缓冲计数触发，两者共用 {@link ProfileService}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileEventHandler {

    /** 提炼输入的对话条数上限（控制 token 成本） */
    private static final int DIALOGUE_LIMIT = 30;

    private final GroupRepository groupRepository;
    private final MessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final MessageAssembler messageAssembler;
    private final ProfileService profileService;

    @EventListener
    public void onTopicClosed(TopicClosed event) {
        Thread.ofVirtual().name("profile-topic-" + event.getTopicId())
                .start(() -> extract(event));
    }

    private void extract(TopicClosed event) {
        try {
            Group group = groupRepository.findById(event.getGroupId()).orElse(null);
            if (group == null) {
                return;
            }
            Agent extractor = resolveExtractor(event.getConcluderAgentId(), group);
            if (extractor == null) {
                log.warn("画像提炼跳过：无可用 Agent, topicId={}", event.getTopicId());
                return;
            }
            List<GroupMessage> messages = messageRepository.findRecentByTopicId(event.getTopicId(), DIALOGUE_LIMIT);
            // 用户没参与发言的讨论对画像无增量
            boolean hasUserMessage = messages.stream().anyMatch(m -> m.getSenderType() == SenderType.USER);
            if (!hasUserMessage) {
                log.info("画像提炼跳过：本次讨论无用户发言, topicId={}", event.getTopicId());
                return;
            }
            String dialogue = messages.stream()
                    .map(m -> messageAssembler.resolveSenderName(m) + ": " + m.getContent())
                    .collect(Collectors.joining("\n"));
            log.info("讨论结束触发画像提炼, topicId={}, 输入消息数={}", event.getTopicId(), messages.size());
            profileService.extractAndMerge(GroupAppService.DEFAULT_USER_ID, extractor, group.getName(), dialogue);
        } catch (Exception e) {
            log.warn("讨论结束画像提炼异常，跳过, topicId={}", event.getTopicId(), e);
        }
    }

    /** 提炼 Agent：总结者优先，否则群首个成员 */
    private Agent resolveExtractor(Long concluderAgentId, Group group) {
        if (concluderAgentId != null) {
            Agent concluder = agentRepository.findById(concluderAgentId).orElse(null);
            if (concluder != null) {
                return concluder;
            }
        }
        List<Agent> members = agentRepository.findByIds(group.memberAgentIds());
        return members.isEmpty() ? null : members.get(0);
    }
}
