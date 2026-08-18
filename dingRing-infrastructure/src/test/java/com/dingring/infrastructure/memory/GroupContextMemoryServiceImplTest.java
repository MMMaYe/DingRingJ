package com.dingring.infrastructure.memory;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.GroupContextMemoryService.AgentPromptContext;
import com.dingring.domain.service.LlmService.ChatTurn;
import com.dingring.domain.user.User;
import com.dingring.domain.user.UserRepository;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link GroupContextMemoryServiceImpl} 单元测试（承接原 ContextBuilderTest / SimpleMemoryServiceTest）。
 * <p>核心规则：
 * <ul>
 *   <li>闲聊：最近 N 条记忆段，排除最后一条（当前输入）；turns 恒为空</li>
 *   <li>讨论：观点段仅 viewpoint 非空（null 丢弃不回退原文）；近期窗口转轮次</li>
 *   <li>收束：观点清单无摘要回退原文（KEY/摘要未就绪兜底）</li>
 *   <li>轮次：自己历史 ASSISTANT、他人合并带花名前缀 USER、连续 USER 合并</li>
 * </ul>
 */
@DisplayName("GroupContextMemoryServiceImpl 群上下文记忆组装")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupContextMemoryServiceImplTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private AgentRepository agentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PromptTemplateLoader promptLoader;

    @InjectMocks
    private GroupContextMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "chatRecentLimit", 10);
        ReflectionTestUtils.setField(service, "contextWindow", 200);
        ReflectionTestUtils.setField(service, "viewpointLimit", 20);
        ReflectionTestUtils.setField(service, "discussRecentWindow", 8);

        when(promptLoader.render(eq("chat-base"), any()))
                .thenAnswer(inv -> "群聊语境：你的花名是「" + ((Map<?, ?>) inv.getArgument(1)).get("agentName") + "」");
        when(promptLoader.render(eq("collaboration-protocol"), any())).thenReturn("协作协议 [[CONCLUDE]]/[[PASS]]");
        when(promptLoader.render(eq("conclude"), any()))
                .thenAnswer(inv -> "知识蒸馏总结，主题：「" + ((Map<?, ?>) inv.getArgument(1)).get("topicTitle") + "」");
    }

    private Agent agent(Long id, String name, String systemPrompt) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        a.setSystemPrompt(systemPrompt);
        return a;
    }

    private GroupMessage userMsg(Long id, Long senderId, String content) {
        GroupMessage m = new GroupMessage();
        m.setId(id);
        m.setSenderId(senderId);
        m.setSenderType(SenderType.USER);
        m.setContent(content);
        return m;
    }

    private GroupMessage agentMsg(Long id, Long senderId, String content) {
        GroupMessage m = new GroupMessage();
        m.setId(id);
        m.setSenderId(senderId);
        m.setSenderType(SenderType.AGENT);
        m.setContent(content);
        return m;
    }

    @Nested
    @DisplayName("buildChatContext 闲聊上下文")
    class Chat {

        @Test
        @DisplayName("记忆段含最近 N 条（花名前缀），排除最后一条当前输入；turns 恒为空")
        void shouldBuildMemoryExcludingCurrentInput() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user("小明")));
            when(agentRepository.findById(10L)).thenReturn(Optional.of(agent(10L, "老王", null)));
            when(messageRepository.findRecentChatByGroupId(1L, 11)).thenReturn(List.of(
                    userMsg(1L, 1L, "第一条"),
                    agentMsg(2L, 10L, "第二条"),
                    userMsg(3L, 1L, "当前输入")
            ));

            AgentPromptContext ctx = service.buildChatContext(agent(10L, "老王", null), 1L);

            assertThat(ctx.systemPrompt())
                    .contains("群最近聊天记录（供参考）：")
                    .contains("小明: 第一条")
                    .contains("老王: 第二条")
                    .doesNotContain("当前输入");          // 排除最后一条
            assertThat(ctx.turns()).isEmpty();           // 当前输入由节点兜底轮传入
        }

        @Test
        @DisplayName("仅当前输入一条时无记忆段")
        void onlyCurrentInputShouldSkipMemorySection() {
            when(messageRepository.findRecentChatByGroupId(1L, 11))
                    .thenReturn(List.of(userMsg(1L, 1L, "当前输入")));

            AgentPromptContext ctx = service.buildChatContext(agent(10L, "老王", null), 1L);

            assertThat(ctx.systemPrompt())
                    .contains("你的花名是「老王」")       // 人设 + chat-base 仍在
                    .doesNotContain("群最近聊天记录");
        }

        @Test
        @DisplayName("Agent 消息按 AgentRepository 解析花名，查不到显示 Agent#id")
        void shouldResolveAgentNameWithFallback() {
            when(agentRepository.findById(99L)).thenReturn(Optional.empty());
            when(messageRepository.findRecentChatByGroupId(1L, 11)).thenReturn(List.of(
                    agentMsg(1L, 99L, "未知Agent的发言"),
                    userMsg(2L, 1L, "当前输入")
            ));

            AgentPromptContext ctx = service.buildChatContext(agent(10L, "老王", null), 1L);

            assertThat(ctx.systemPrompt()).contains("Agent#99: 未知Agent的发言");
        }

        private User user(String name) {
            User u = new User();
            u.setId(1L);
            u.setName(name);
            return u;
        }
    }

    @Nested
    @DisplayName("buildDiscussContext 讨论上下文")
    class Discuss {

        @Test
        @DisplayName("观点段仅取 viewpoint 非空的消息（null 丢弃不回退原文）")
        void shouldDiscardNullViewpointWithoutFallback() {
            GroupMessage withSummary = agentMsg(1L, 20L, "长篇原文A");
            withSummary.setViewpoint("摘要A");
            GroupMessage noSummary = agentMsg(2L, 21L, "长篇原文B（无摘要，应被丢弃）");
            when(messageRepository.findViewpointsByTopicId(100L, 20))
                    .thenReturn(List.of(withSummary, noSummary));
            when(messageRepository.countByTopicIdAndSenderType(100L, SenderType.AGENT)).thenReturn(5L);
            when(messageRepository.findRecentByTopicId(100L, 8)).thenReturn(List.of());
            when(agentRepository.findById(20L)).thenReturn(Optional.of(agent(20L, "专家甲", null)));

            AgentPromptContext ctx = service.buildDiscussContext(agent(10L, "老王", null), 1L, 100L, "");

            assertThat(ctx.systemPrompt())
                    .contains("讨论观点:")
                    .contains("专家甲: 摘要A")
                    .doesNotContain("长篇原文A")
                    .doesNotContain("长篇原文B");
        }

        @Test
        @DisplayName("近期窗口转轮次：自己 ASSISTANT、他人合并带花名前缀 USER")
        void shouldConvertRecentWindowToTurnsWithRoles() {
            when(messageRepository.findViewpointsByTopicId(100L, 20)).thenReturn(List.of());
            when(messageRepository.countByTopicIdAndSenderType(100L, SenderType.AGENT)).thenReturn(0L);
            when(messageRepository.findRecentByTopicId(100L, 8)).thenReturn(List.of(
                    userMsg(1L, 1L, "什么是 JVM？"),
                    agentMsg(2L, 10L, "JVM 是 Java 虚拟机"),
                    userMsg(3L, 1L, "继续说说")
            ));
            when(userRepository.findById(1L)).thenReturn(Optional.of(newUser("小明")));

            AgentPromptContext ctx = service.buildDiscussContext(agent(10L, "老王", null), 1L, 100L, "");

            assertThat(ctx.turns()).extracting(ChatTurn::role)
                    .containsExactly("USER", "ASSISTANT", "USER");
            assertThat(ctx.turns().get(0).content()).isEqualTo("小明: 什么是 JVM？");
            assertThat(ctx.turns().get(1).content()).isEqualTo("JVM 是 Java 虚拟机");
            assertThat(ctx.turns().get(2).content()).isEqualTo("小明: 继续说说");
        }

        @Test
        @DisplayName("userHistoryHint 非空时追加，空时不追加")
        void shouldAppendUserHistoryHint() {
            when(messageRepository.findViewpointsByTopicId(100L, 20)).thenReturn(List.of());
            when(messageRepository.countByTopicIdAndSenderType(100L, SenderType.AGENT)).thenReturn(0L);
            when(messageRepository.findRecentByTopicId(100L, 8)).thenReturn(List.of());

            AgentPromptContext withHint = service.buildDiscussContext(
                    agent(10L, "老王", null), 1L, 100L, "用户上次讨论「缓存」时在一致性方面还需提升");
            assertThat(withHint.systemPrompt()).contains("用户上次讨论「缓存」时在一致性方面还需提升");

            AgentPromptContext withoutHint = service.buildDiscussContext(
                    agent(10L, "老王", null), 1L, 100L, "");
            assertThat(withoutHint.systemPrompt()).doesNotContain("用户上次讨论");
        }

        @Test
        @DisplayName("进度引导按打标消息总数（未过滤）推进")
        void progressGuideShouldUseUnfilteredCount() {
            // 2 条打标消息但 viewpoint 全为 null（全被丢弃），进度引导计数仍应为 2
            GroupMessage noSummary = agentMsg(1L, 20L, "原文");
            GroupMessage noSummary2 = agentMsg(2L, 21L, "原文2");
            when(messageRepository.findViewpointsByTopicId(100L, 20))
                    .thenReturn(List.of(noSummary, noSummary2));
            when(messageRepository.countByTopicIdAndSenderType(100L, SenderType.AGENT)).thenReturn(2L);
            when(messageRepository.findRecentByTopicId(100L, 8)).thenReturn(List.of());

            AgentPromptContext ctx = service.buildDiscussContext(agent(10L, "老王", null), 1L, 100L, "");

            assertThat(ctx.systemPrompt())
                    .contains("讨论进度: 早期")
                    .contains("已有2个观点")
                    .doesNotContain("讨论观点:");      // 观点段为空被省略
        }

        private com.dingring.domain.user.User newUser(String name) {
            com.dingring.domain.user.User u = new com.dingring.domain.user.User();
            u.setId(1L);
            u.setName(name);
            return u;
        }
    }

    @Nested
    @DisplayName("buildConclusionContext 收束上下文")
    class Conclude {

        @Test
        @DisplayName("观点清单用摘要；无摘要（KEY/未就绪）回退原文")
        void shouldFallbackToRawContentWhenNoSummary() {
            GroupMessage withSummary = agentMsg(1L, 20L, "长篇原文A");
            withSummary.setViewpoint("摘要A");
            GroupMessage noSummary = userMsg(2L, 1L, "用户的关键观点（KEY 原文）");
            when(messageRepository.findViewpointsByTopicId(100L, 20))
                    .thenReturn(List.of(withSummary, noSummary));
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of());
            when(agentRepository.findById(20L)).thenReturn(Optional.of(agent(20L, "专家甲", null)));
            when(userRepository.findById(1L)).thenReturn(Optional.of(newUser("小明")));

            AgentPromptContext ctx = service.buildConclusionContext(
                    agent(99L, "总结者", "你是领域专家"), 100L, "Java 内存模型");

            assertThat(ctx.systemPrompt())
                    .contains("知识蒸馏总结，主题：「Java 内存模型」")
                    .contains("讨论观点（含发言人归属，为主要输入）：")
                    .contains("专家甲: 摘要A")
                    .contains("小明: 用户的关键观点（KEY 原文）");   // 回退原文
        }

        @Test
        @DisplayName("全量原文窗口转轮次（含当前输入）")
        void shouldConvertFullWindowToTurns() {
            when(messageRepository.findViewpointsByTopicId(100L, 20)).thenReturn(List.of());
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of(
                    userMsg(2L, 1L, "请核对淘汰策略")
            ));
            when(userRepository.findById(1L)).thenReturn(Optional.of(newUser("小明")));

            AgentPromptContext ctx = service.buildConclusionContext(agent(99L, "总结者", null), 100L, "缓存方案");

            assertThat(ctx.turns()).extracting(ChatTurn::content).contains("小明: 请核对淘汰策略");
        }

        private com.dingring.domain.user.User newUser(String name) {
            com.dingring.domain.user.User u = new com.dingring.domain.user.User();
            u.setId(1L);
            u.setName(name);
            return u;
        }
    }
}
