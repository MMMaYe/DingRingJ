package com.dingring.app.event;

import com.dingring.app.service.MessageAssembler;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.event.TopicClosed;
import com.dingring.domain.group.Group;
import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.GroupRepository;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProfileEventHandler} 画像事件处理单元测试。
 * <p>提炼跑在虚拟线程上，用 {@code verify(mock, timeout(..))} 异步验证。
 * 核心规则：讨论结束触发提炼；无用户发言的讨论跳过；总结者优先作为提炼 Agent。
 */
@DisplayName("ProfileEventHandler 画像提炼")
class ProfileEventHandlerTest {

    private static final long WAIT = 3000;

    private GroupRepository groupRepository;
    private MessageRepository messageRepository;
    private AgentRepository agentRepository;
    private MessageAssembler messageAssembler;
    private ProfileService profileService;
    private ProfileEventHandler handler;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        messageRepository = mock(MessageRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageAssembler = mock(MessageAssembler.class);
        profileService = mock(ProfileService.class);
        handler = new ProfileEventHandler(groupRepository, messageRepository,
                agentRepository, messageAssembler, profileService);
    }

    private TopicClosed event(Long concluderAgentId) {
        return new TopicClosed(100L, 1L, "主题", "# 结论", 6, "USER", concluderAgentId);
    }

    private Group group() {
        Group g = new Group();
        g.setId(1L);
        g.setName("测试群");
        g.setGroupMember(new java.util.ArrayList<>(List.of(
                new GroupMember(10L, MemberType.AGENT, MemberRole.MEMBER))));
        return g;
    }

    private Agent agent(Long id, String name) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        return a;
    }

    private GroupMessage msg(SenderType type, String content) {
        GroupMessage m = new GroupMessage();
        m.setSenderType(type);
        m.setContent(content);
        return m;
    }

    @Test
    @DisplayName("讨论含用户发言：以总结者为提炼 Agent 触发画像提炼")
    void topicWithUserMessagesShouldExtractWithConcluder() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        Agent concluder = agent(11L, "小李");
        when(agentRepository.findById(11L)).thenReturn(Optional.of(concluder));
        when(messageRepository.findRecentByTopicId(eq(100L), anyInt())).thenReturn(List.of(
                msg(SenderType.USER, "我觉得A方案好"),
                msg(SenderType.AGENT, "同意")));
        when(messageAssembler.resolveSenderName(any())).thenReturn("发言者");

        handler.onTopicClosed(event(11L));

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(profileService, timeout(WAIT)).extractAndMerge(anyLong(), captor.capture(),
                eq("测试群"), any(String.class));
        assertThat(captor.getValue().getId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("总结者不存在时回退群首个成员 Agent")
    void missingConcluderShouldFallbackToFirstMember() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(agentRepository.findById(99L)).thenReturn(Optional.empty());
        when(agentRepository.findByIds(any())).thenReturn(List.of(agent(10L, "老王")));
        when(messageRepository.findRecentByTopicId(eq(100L), anyInt()))
                .thenReturn(List.of(msg(SenderType.USER, "hi")));
        when(messageAssembler.resolveSenderName(any())).thenReturn("发言者");

        handler.onTopicClosed(event(99L));

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(profileService, timeout(WAIT)).extractAndMerge(anyLong(), captor.capture(),
                any(), any(String.class));
        assertThat(captor.getValue().getId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("讨论无用户发言时跳过提炼")
    void topicWithoutUserMessagesShouldSkip() throws Exception {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(agentRepository.findById(11L)).thenReturn(Optional.of(agent(11L, "小李")));
        when(messageRepository.findRecentByTopicId(eq(100L), anyInt())).thenReturn(List.of(
                msg(SenderType.AGENT, "观点A"),
                msg(SenderType.AGENT, "观点B")));

        handler.onTopicClosed(event(11L));

        // 虚拟线程执行完毕后仍未触发提炼
        Thread.sleep(300);
        verify(profileService, org.mockito.Mockito.never())
                .extractAndMerge(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("群不存在时静默跳过")
    void missingGroupShouldSkipSilently() throws Exception {
        when(groupRepository.findById(1L)).thenReturn(Optional.empty());

        handler.onTopicClosed(event(11L));

        Thread.sleep(300);
        verify(profileService, org.mockito.Mockito.never())
                .extractAndMerge(anyLong(), any(), any(), any());
    }
}
