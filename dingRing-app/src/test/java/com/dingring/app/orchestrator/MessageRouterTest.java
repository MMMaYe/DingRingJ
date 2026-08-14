package com.dingring.app.orchestrator;

import com.dingring.domain.agent.Agent;
import com.dingring.domain.service.LlmService;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MessageRouter} 意图路由单元测试。
 * <p>核心规则：LLM 输出 JSON 解析 → Route；任何失败降级 CHAT/LOW（宁可少建题不乱建题）。
  * <p>路由走带 {@link LlmService.CallOptions} 的低温/短超时重载，打桩针对 4 参方法。
 */
@DisplayName("MessageRouter 意图路由")
class MessageRouterTest {

    private LlmService llmService;
    private PromptTemplateLoader promptLoader;
    private MessageRouter router;
    private Agent judge;

    @BeforeEach
    void setUp() {
        llmService = mock(LlmService.class);
        promptLoader = mock(PromptTemplateLoader.class);
        router = new MessageRouter(llmService, new ObjectMapper(), promptLoader);
        when(promptLoader.render(eq("intent-classify"), any())).thenReturn("意图分类模板");
        judge = new Agent();
        judge.setId(10L);
        judge.setName("老王");
    }

    private void stubLlm(String raw) {
        when(llmService.chat(any(Agent.class), anyString(), anyList(),
                any(LlmService.CallOptions.class))).thenReturn(raw);
    }

    @Nested
    @DisplayName("正常解析")
    class Parse {

        @Test
        @DisplayName("DISCUSS/HIGH 带标题正常解析")
        void discussHighShouldParse() {
            stubLlm("{\"intent\": \"DISCUSS\", \"topicTitle\": \"周末去哪玩\", \"confidence\": \"HIGH\"}");

            MessageRouter.Route route = router.route(judge, "周末去哪玩好呢", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.DISCUSS);
            assertThat(route.topicTitle()).isEqualTo("周末去哪玩");
            assertThat(route.confidence()).isEqualTo(MessageRouter.Confidence.HIGH);
        }

        @Test
        @DisplayName("容忍 ```json 代码块包裹与前后杂文")
        void wrappedJsonShouldParse() {
            stubLlm("好的，判定结果如下：\n```json\n{\"intent\": \"CONCLUDE\", \"topicTitle\": \"\", \"confidence\": \"HIGH\"}\n```");

            MessageRouter.Route route = router.route(judge, "总结一下吧", "周末去哪玩");

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.CONCLUDE);
        }

        @Test
        @DisplayName("WORK 带产出物指令正常解析")
        void workIntentShouldParse() {
            stubLlm("{\"intent\": \"WORK\", \"topicTitle\": \"\", \"confidence\": \"HIGH\"}");

            MessageRouter.Route route = router.route(judge, "帮我写一份商城系统技术方案文档", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.WORK);
            assertThat(route.confidence()).isEqualTo(MessageRouter.Confidence.HIGH);
        }

        @Test
        @DisplayName("WORK 空标题不触发 DISCUSS 截断兜底")
        void workBlankTitleShouldNotFallback() {
            stubLlm("{\"intent\": \"WORK\", \"topicTitle\": \"\", \"confidence\": \"LOW\"}");

            MessageRouter.Route route = router.route(judge, "帮我把群聊结论整理成要点清单", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.WORK);
            assertThat(route.topicTitle()).isEmpty();
        }

        @Test
        @DisplayName("intent 小写也能解析")
        void lowercaseIntentShouldParse() {
            stubLlm("{\"intent\": \"chat\", \"topicTitle\": \"\", \"confidence\": \"low\"}");

            MessageRouter.Route route = router.route(judge, "哈哈哈", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.CHAT);
            assertThat(route.confidence()).isEqualTo(MessageRouter.Confidence.LOW);
        }

        @Test
        @DisplayName("confidence 非法时兜底为 LOW")
        void invalidConfidenceShouldFallbackLow() {
            stubLlm("{\"intent\": \"DISCUSS\", \"topicTitle\": \"t\", \"confidence\": \"MAYBE\"}");

            MessageRouter.Route route = router.route(judge, "讨论一下", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.DISCUSS);
            assertThat(route.confidence()).isEqualTo(MessageRouter.Confidence.LOW);
        }

        @Test
        @DisplayName("路由调用携带低温/限额 CallOptions（不复用 Agent 会话参数）")
        void routeShouldUseLowTempOptions() {
            stubLlm("{\"intent\": \"CHAT\", \"topicTitle\": \"\", \"confidence\": \"LOW\"}");

            router.route(judge, "随便聊聊", null);

            ArgumentCaptor<LlmService.CallOptions> captor = ArgumentCaptor.forClass(LlmService.CallOptions.class);
            verify(llmService).chat(any(Agent.class), anyString(), anyList(), captor.capture());
            assertThat(captor.getValue().temperature()).isZero();
            assertThat(captor.getValue().maxTokens()).isEqualTo(100000);
        }
    }

    @Nested
    @DisplayName("标题兜底")
    class TitleFallback {

        @Test
        @DisplayName("DISCUSS 无标题时截取用户消息前 20 字")
        void blankTitleShouldTruncateContent() {
            stubLlm("{\"intent\": \"DISCUSS\", \"topicTitle\": \"\", \"confidence\": \"HIGH\"}");
            String content = "这是一条超过二十个字的讨论消息内容啊啊啊啊啊啊啊啊";

            MessageRouter.Route route = router.route(judge, content, null);

            assertThat(route.topicTitle()).isEqualTo(content.substring(0, 20));
        }

        @Test
        @DisplayName("消息不足 20 字时全量作为标题")
        void shortContentShouldBeFullTitle() {
            stubLlm("{\"intent\": \"DISCUSS\", \"topicTitle\": \"\", \"confidence\": \"HIGH\"}");

            MessageRouter.Route route = router.route(judge, "短消息", null);

            assertThat(route.topicTitle()).isEqualTo("短消息");
        }
    }

    @Nested
    @DisplayName("失败降级")
    class Degrade {

        @Test
        @DisplayName("LLM 抛异常时降级 CHAT/LOW")
        void llmFailureShouldDegradeChat() {
            when(llmService.chat(any(Agent.class), anyString(), anyList(),
                    any(LlmService.CallOptions.class)))
                    .thenThrow(new RuntimeException("LLM 超时"));

            MessageRouter.Route route = router.route(judge, "hello", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.CHAT);
            assertThat(route.confidence()).isEqualTo(MessageRouter.Confidence.LOW);
        }

        @Test
        @DisplayName("输出不含 JSON 时降级 CHAT")
        void nonJsonOutputShouldDegradeChat() {
            stubLlm("我觉得这是闲聊");

            MessageRouter.Route route = router.route(judge, "hello", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.CHAT);
        }

        @Test
        @DisplayName("intent 非法值时降级 CHAT")
        void invalidIntentShouldDegradeChat() {
            stubLlm("{\"intent\": \"UNKNOWN\", \"topicTitle\": \"\", \"confidence\": \"HIGH\"}");

            MessageRouter.Route route = router.route(judge, "hello", null);

            assertThat(route.intent()).isEqualTo(MessageRouter.Intent.CHAT);
        }
    }
}
