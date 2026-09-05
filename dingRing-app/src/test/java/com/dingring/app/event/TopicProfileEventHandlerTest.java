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
import com.dingring.domain.service.LlmService;
import com.dingring.domain.skill.Skill;
import com.dingring.domain.skill.SkillLoaderService;
import com.dingring.domain.skill.SkillScene;
import com.dingring.domain.user.UserTopicProfile;
import com.dingring.domain.user.UserTopicProfileRepository;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TopicProfileEventHandler} 话题画像分析单元测试。
 * <p>分析跑在虚拟线程上，用 {@code verify(mock, timeout(..))} 异步验证。
 * 核心规则：topic-profile 模板渲染后追加 TOPIC_PROFILE 场景技能（运营增量规范），
 * 无技能时 prompt 与模板渲染产物一致（零行为变化）。
 */
@DisplayName("TopicProfileEventHandler 话题画像分析")
class TopicProfileEventHandlerTest {

    private static final long WAIT = 3000;

    private static final String PROFILE_JSON =
            "{\"understanding_level\":\"中\",\"weak_points\":\"JMM 可见性\","
                    + "\"strong_points\":\"JUC 熟练\",\"suggested_focus\":\"补齐 JMM 内存语义\"}";

    private GroupRepository groupRepository;
    private AgentRepository agentRepository;
    private MessageRepository messageRepository;
    private MessageAssembler messageAssembler;
    private UserTopicProfileRepository profileRepository;
    private LlmService llmService;
    private PromptTemplateLoader promptLoader;
    private SkillLoaderService skillLoader;
    private TopicProfileEventHandler handler;

    @BeforeEach
    void setUp() {
        groupRepository = mock(GroupRepository.class);
        agentRepository = mock(AgentRepository.class);
        messageRepository = mock(MessageRepository.class);
        messageAssembler = mock(MessageAssembler.class);
        profileRepository = mock(UserTopicProfileRepository.class);
        llmService = mock(LlmService.class);
        promptLoader = mock(PromptTemplateLoader.class);
        skillLoader = mock(SkillLoaderService.class);
        handler = new TopicProfileEventHandler(groupRepository, agentRepository, messageRepository,
                messageAssembler, profileRepository, llmService, promptLoader, skillLoader);

        when(promptLoader.render(eq("topic-profile"), any())).thenReturn("用户话题画像模板");
        // Mockito mock 接口时 default 方法同样被 mock（默认返回 null，会把 prompt 变 null），
        // 故让 applySceneSkills 走真实 default 实现：loadSceneSkills 未打桩时 Mockito 默认返回空列表，
        // default 方法原样返回 basePrompt——与生产端口的「无技能零行为变化」语义一致
        doCallRealMethod().when(skillLoader).applySceneSkills(any(), any());
    }

    private TopicClosed event() {
        return new TopicClosed(100L, 1L, "Java 内存模型", "# 结论", 6L, "USER", 11L);
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

    /** 通用前置：群/总结者/含用户发言的对话全部就绪，LLM 返回合法画像 JSON */
    private void stubHappyPath() {
        when(groupRepository.findById(1L)).thenReturn(Optional.of(group()));
        when(agentRepository.findById(11L)).thenReturn(Optional.of(agent(11L, "总结者")));
        when(messageRepository.findRecentByTopicId(eq(100L), anyInt())).thenReturn(List.of(
                msg(SenderType.USER, "什么是 JMM 可见性"),
                msg(SenderType.AGENT, "可见性指一个线程的修改对其他线程是否立即可见")));
        when(messageAssembler.resolveSenderName(any())).thenReturn("小明");
        when(llmService.chat(any(), anyString(), any(), any())).thenReturn(PROFILE_JSON);
    }

    @Test
    @DisplayName("topic-profile 技能分节拼接在出厂模板之后，随 systemPrompt 传给 LLM")
    void shouldAppendTopicProfileSceneSkillToSystemPrompt() {
        stubHappyPath();
        Skill skill = new Skill();
        skill.setName("profile-comparable");
        skill.setSystemPrompt("weak_points 用标准领域术语");
        // applySceneSkills 走真实 default 实现（见 setUp），这里只打桩数据源
        when(skillLoader.loadSceneSkills(SkillScene.TOPIC_PROFILE)).thenReturn(List.of(skill));

        handler.onTopicClosed(event());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService, timeout(WAIT)).chat(any(), promptCaptor.capture(), any(), any());
        assertThat(promptCaptor.getValue())
                .contains("用户话题画像模板")                            // 出厂模板基线仍在
                .contains("## 附加规范（SKILL: profile-comparable）")   // 技能分节头
                .contains("weak_points 用标准领域术语");                // 技能正文
        // 拼接时机断言：技能分节必须位于出厂模板之后
        assertThat(promptCaptor.getValue().indexOf("## 附加规范"))
                .isGreaterThan(promptCaptor.getValue().indexOf("用户话题画像模板"));
        // 技能注入不改变主流程：画像仍正常入库
        verify(profileRepository, timeout(WAIT)).save(any(UserTopicProfile.class));
    }

    @Test
    @DisplayName("无 topic-profile 技能时 systemPrompt 与模板渲染产物完全一致")
    void noSkillShouldKeepTemplatePrompt() {
        // loadSceneSkills 未打桩 → Mockito 默认返回空列表 → default 方法原样返回 basePrompt
        stubHappyPath();

        handler.onTopicClosed(event());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmService, timeout(WAIT)).chat(any(), promptCaptor.capture(), any(), any());
        assertThat(promptCaptor.getValue()).isEqualTo("用户话题画像模板");
        verify(profileRepository, timeout(WAIT)).save(any(UserTopicProfile.class));
    }
}
