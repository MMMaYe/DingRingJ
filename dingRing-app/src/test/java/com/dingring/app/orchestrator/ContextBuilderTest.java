package com.dingring.app.orchestrator;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.SenderType;
import com.dingring.domain.service.LlmService.ChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ContextBuilder} 上下文构建单元测试。
 * <p>Phase D 改造：群记忆/用户画像/群成员名单已迁移到 Hook（MemoryInjectionHook /
 * ProfileInjectionHook / GroupRosterHook），ContextBuilder 只负责"静态系统提示词 + 消息历史"，
 * 故本测试不再覆盖记忆/画像/名单的拼接断言（由各 Hook 的单测覆盖）。
 * <p>核心：消息序列 → ChatTurn 转换规则
 * <ul>
 *   <li>Agent 自己的历史发言 → ASSISTANT 轮次</li>
 *   <li>其他人的消息 → 合并为带「花名: 内容」前缀的 USER 轮次</li>
 *   <li>连续 USER 轮次合并为一条</li>
 * </ul>
 */
@DisplayName("ContextBuilder 上下文构建")
class ContextBuilderTest {

    private MessageRepository messageRepository;
    private ContextBuilder contextBuilder;

    @BeforeEach
    void setUp() throws Exception {
        messageRepository = mock(MessageRepository.class);
        contextBuilder = new ContextBuilder(messageRepository);
        setField(contextBuilder, "contextWindow", 200);
    }

    private void setField(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Agent agent(Long id, String name, String systemPrompt) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        a.setSystemPrompt(systemPrompt);
        return a;
    }

    private GroupMessage userMsg(Long id, Long senderId, String senderName, String content) {
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
    @DisplayName("build 普通发言上下文")
    class Build {

        @Test
        @DisplayName("空消息列表时仍生成兜底 USER 轮次")
        void emptyMessagesShouldFallbackToStarterTurn() {
            Agent self = agent(10L, "老王", "你是后端专家");
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of());

            ContextBuilder.LlmContext ctx = contextBuilder.build(self, 1L, 100L, m -> "用户");

            assertThat(ctx.turns()).hasSize(1);
            assertThat(ctx.turns().get(0).role()).isEqualTo("USER");
            assertThat(ctx.turns().get(0).content()).contains("讨论刚开始");
        }

        @Test
        @DisplayName("Agent 自己的发言被映射为 ASSISTANT 轮次")
        void selfMessagesShouldBeAssistantTurns() {
            Agent self = agent(10L, "老王", null);
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of(
                    agentMsg(1L, 10L, "我觉得用 Redis 合适"),
                    agentMsg(2L, 10L, "补充一下：可以用 ZSET 排序")
            ));

            ContextBuilder.LlmContext ctx = contextBuilder.build(self, 1L, 100L, m -> "老王");

            assertThat(ctx.turns()).extracting(ChatTurn::role)
                    .containsExactly("ASSISTANT", "ASSISTANT");
            assertThat(ctx.turns()).extracting(ChatTurn::content)
                    .containsExactly("我觉得用 Redis 合适", "补充一下：可以用 ZSET 排序");
        }

        @Test
        @DisplayName("他人连续消息合并为一条带花名前缀的 USER 轮次")
        void otherMessagesShouldBeMergedUserTurn() {
            Agent self = agent(10L, "老王", null);
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of(
                    userMsg(1L, 1L, "用户A", "什么是缓存雪崩？"),
                    userMsg(2L, 1L, "用户A", "怎么避免？")
            ));

            ContextBuilder.LlmContext ctx = contextBuilder.build(self, 1L, 100L, m -> "用户A");

            assertThat(ctx.turns()).hasSize(1);
            ChatTurn turn = ctx.turns().get(0);
            assertThat(turn.role()).isEqualTo("USER");
            assertThat(turn.content()).isEqualTo("用户A: 什么是缓存雪崩？\n用户A: 怎么避免？");
        }

        @Test
        @DisplayName("USER 与 ASSISTANT 交替时按发言顺序正确切换角色")
        void mixedMessagesShouldAlternateRoles() {
            Agent self = agent(10L, "老王", null);
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of(
                    userMsg(1L, 1L, "用户", "什么是 JVM？"),
                    agentMsg(2L, 10L, "JVM 是 Java 虚拟机"),
                    userMsg(3L, 1L, "用户", "继续说说")
            ));

            ContextBuilder.LlmContext ctx = contextBuilder.build(self, 1L, 100L, m -> "用户");

            assertThat(ctx.turns()).extracting(ChatTurn::role)
                    .containsExactly("USER", "ASSISTANT", "USER");
            assertThat(ctx.turns().get(0).content()).isEqualTo("用户: 什么是 JVM？");
            assertThat(ctx.turns().get(2).content()).isEqualTo("用户: 继续说说");
        }

        @Test
        @DisplayName("systemPrompt 含 Agent 人设与群聊语境")
        void systemPromptShouldContainPersonaAndGroupContext() {
            Agent self = agent(10L, "老王", "你是后端架构师");
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of());

            ContextBuilder.LlmContext ctx = contextBuilder.build(self, 1L, 100L, m -> "用户");

            assertThat(ctx.systemPrompt())
                    .contains("你是后端架构师")        // 人设
                    .contains("你的花名是「老王」");   // 群聊语境
        }

        @Test
        @DisplayName("topicId 为 null 时走群窗口（闲聊场景）")
        void nullTopicIdShouldUseGroupWindow() {
            Agent self = agent(10L, "老王", null);
            when(messageRepository.findRecentByGroupId(1L, 200)).thenReturn(List.of(
                    userMsg(1L, 1L, "用户", "hi")
            ));

            ContextBuilder.LlmContext ctx = contextBuilder.build(self, 1L, null, m -> "用户");

            assertThat(ctx.turns()).hasSize(1);
            assertThat(ctx.turns().get(0).content()).isEqualTo("用户: hi");
        }
    }

    @Nested
    @DisplayName("buildForConclusion 结论生成上下文")
    class BuildForConclusion {

        @Test
        @DisplayName("systemPrompt 含 STAR 框架指令与主题标题")
        void shouldContainStarFrameworkAndTopicTitle() {
            Agent expert = agent(99L, "专家", "你是领域专家");
            when(messageRepository.findRecentByTopicId(100L, 200)).thenReturn(List.of());

            ContextBuilder.LlmContext ctx = contextBuilder.buildForConclusion(
                    expert, 1L, 100L, "Java 内存模型", m -> "用户");

            assertThat(ctx.systemPrompt())
                    .contains("你是领域专家")                    // 原人设
                    .contains("STAR 框架")                       // STAR 指令
                    .contains("Java 内存模型");                  // 主题标题
        }
    }

    @Nested
    @DisplayName("buildForDiscuss 讨论态上下文（方案 6.3.6）")
    class BuildForDiscuss {

        @BeforeEach
        void setUp() throws Exception {
            setField(contextBuilder, "viewpointLimit", 20);
            setField(contextBuilder, "discussRecentWindow", 8);
        }

        @Test
        @DisplayName("观点摘要列表拼入 systemPrompt，近期窗口转对话轮次")
        void shouldMergeViewpointsIntoPromptAndRecentWindowIntoTurns() throws Exception {
            Agent self = agent(10L, "老王", "你是后端专家");
            GroupMessage viewpoint = agentMsg(1L, 20L, "Agent 长篇原文");
            viewpoint.setViewpoint("建议用 Redis 缓存热点数据");
            viewpoint.setTag(com.dingring.domain.group.MessageTag.VIEWPOINT);
            when(messageRepository.findViewpointsByTopicId(100L, 20)).thenReturn(List.of(viewpoint));
            when(messageRepository.findRecentByTopicId(100L, 8)).thenReturn(List.of(
                    userMsg(2L, 1L, "用户", "最近一条提问")
            ));

            ContextBuilder.LlmContext ctx = contextBuilder.buildForDiscuss(
                    self, 1L, 100L, m -> "用户", "");

            // 观点摘要（用摘要而非原文）进入 systemPrompt
            assertThat(ctx.systemPrompt())
                    .contains("讨论观点:")
                    .contains("建议用 Redis 缓存热点数据")
                    .doesNotContain("Agent 长篇原文");
            // 近期窗口转轮次
            assertThat(ctx.turns()).extracting(ChatTurn::content)
                    .contains("用户: 最近一条提问");
        }

        @Test
        @DisplayName("观点无摘要时回退原文")
        void shouldFallbackToRawContentWhenNoViewpointSummary() {
            Agent self = agent(10L, "老王", null);
            GroupMessage viewpoint = agentMsg(1L, 20L, "缓存穿透可以用布隆过滤器");
            when(messageRepository.findViewpointsByTopicId(100L, 20)).thenReturn(List.of(viewpoint));
            when(messageRepository.findRecentByTopicId(100L, 8)).thenReturn(List.of());

            ContextBuilder.LlmContext ctx = contextBuilder.buildForDiscuss(
                    self, 1L, 100L, m -> "用户", "");

            assertThat(ctx.systemPrompt()).contains("缓存穿透可以用布隆过滤器");
        }

        @Test
        @DisplayName("userHistoryHint 追加进 systemPrompt，空时不追加")
        void shouldAppendUserHistoryHint() {
            Agent self = agent(10L, "老王", null);
            when(messageRepository.findViewpointsByTopicId(100L, 20)).thenReturn(List.of());
            when(messageRepository.findRecentByTopicId(100L, 8)).thenReturn(List.of());

            ContextBuilder.LlmContext withHint = contextBuilder.buildForDiscuss(
                    self, 1L, 100L, m -> "用户", "用户上次讨论「缓存」时在一致性方面还需提升");
            assertThat(withHint.systemPrompt())
                    .contains("用户上次讨论「缓存」时在一致性方面还需提升");

            ContextBuilder.LlmContext withoutHint = contextBuilder.buildForDiscuss(
                    self, 1L, 100L, m -> "用户", "");
            assertThat(withoutHint.systemPrompt()).doesNotContain("用户上次讨论");
        }
    }
}
