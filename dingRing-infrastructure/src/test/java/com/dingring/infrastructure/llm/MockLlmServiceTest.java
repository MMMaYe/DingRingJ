package com.dingring.infrastructure.llm;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import com.dingring.domain.service.LlmService.ChatTurn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MockLlmService} 单元测试。
 * <p>验证三种 systemPrompt 分支：STAR 结论 / 知识卡片 / 默认发言，以及空消息边界。
 */
@DisplayName("MockLlmService 模拟 LLM 实现")
class MockLlmServiceTest {

    private final MockLlmService service = new MockLlmService();

    private Agent agent(String name) {
        Agent a = new Agent();
        a.setId(1L);
        a.setName(name);
        return a;
    }

    @Test
    @DisplayName("systemPrompt 含 STAR 关键字时返回 STAR 框架结论")
    void shouldReturnStarConclusionWhenSystemPromptContainsStar() {
        String result = service.chat(agent("专家"), "请按 STAR 框架总结本次讨论", List.of(
                ChatTurn.user("用户问题")
        ));

        assertThat(result).contains("STAR")
                .contains("S - 背景")
                .contains("T - 任务")
                .contains("A - 行动")
                .contains("R - 结果");
    }

    @Test
    @DisplayName("systemPrompt 含\"知识卡片\"关键字时返回 JSON 数组")
    void shouldReturnCardsJsonWhenSystemPromptContainsKnowledgeCard() {
        String result = service.chat(agent("专家"), "请生成知识卡片", List.of(
                ChatTurn.user("讨论内容")
        ));

        // 不验证 JSON 严格性，只验证关键字段存在
        assertThat(result).contains("\"question\"")
                .contains("\"answer\"")
                .contains("\"category\"");
    }

    @Test
    @DisplayName("其他场景返回默认发言，包含 Agent 花名")
    void shouldReturnDefaultReplyWithAgentName() {
        String result = service.chat(agent("老王"), "你是群成员", List.of(
                ChatTurn.user("聊一下 Java 内存模型")
        ));

        assertThat(result).contains("老王");
    }

    @Test
    @DisplayName("systemPrompt 为 null 时走默认发言分支")
    void nullSystemPromptShouldFallbackToDefaultReply() {
        String result = service.chat(agent("小李"), null, List.of(
                ChatTurn.user("在吗")
        ));

        assertThat(result).contains("小李");
    }

    @Test
    @DisplayName("消息列表为空时也能返回发言（不抛异常）")
    void emptyMessagesShouldNotThrow() {
        String result = service.chat(agent("老王"), null, List.of());

        assertThat(result).contains("老王");
    }

    @Test
    @DisplayName("用户消息过长时发言会被截断（40 字符 + 省略号）")
    void longUserMessageShouldBeTruncated() {
        String longContent = "a".repeat(100);
        String result = service.chat(agent("老王"), null, List.of(
                ChatTurn.user(longContent)
        ));

        // 模板里会把用户内容作为 topicHint，过长会截断到 40 字符
        assertThat(result).doesNotContain("a".repeat(100));
    }
}
