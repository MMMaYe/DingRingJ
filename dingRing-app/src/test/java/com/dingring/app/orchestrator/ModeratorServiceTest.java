package com.dingring.app.orchestrator;

import com.dingring.app.service.MessageAssembler;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.service.LlmService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ModeratorService} 主持人决策单元测试。
 * <p>核心：LLM JSON 决策解析；任何失败（未开启/异常/非法花名/非 JSON）回退 null 走 Phase 1。
 */
@DisplayName("ModeratorService 主持人")
class ModeratorServiceTest {

    private LlmService llmService;
    private MessageRepository messageRepository;
    private MessageAssembler messageAssembler;
    private ModeratorService service;

    private Topic topic;
    private List<Agent> members;

    @BeforeEach
    void setUp() throws Exception {
        llmService = mock(LlmService.class);
        messageRepository = mock(MessageRepository.class);
        messageAssembler = mock(MessageAssembler.class);
        service = new ModeratorService(llmService, new ObjectMapper(), messageRepository, messageAssembler);
        setField("enabled", true);
        setField("model", "");
        setField("contextWindow", 30);

        topic = new Topic();
        topic.setId(100L);
        topic.setTitle("测试主题");
        members = List.of(agent(10L, "老王", "qwen-plus"), agent(11L, "小李", "step-flash"));
        when(messageRepository.findRecentByTopicId(eq(100L), anyInt())).thenReturn(List.of());
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ModeratorService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private Agent agent(Long id, String name, String model) {
        Agent a = new Agent();
        a.setId(id);
        a.setName(name);
        a.setModelName(model);
        a.setBaseUrl("https://api.test.com");
        a.setApiKey("sk-test");
        return a;
    }

    @Nested
    @DisplayName("决策解析")
    class Parse {

        @Test
        @DisplayName("正常 JSON：全字段解析，花名映射为 AgentId")
        void validJsonShouldParseAllFields() {
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn(
                    "{\"should_continue\": true, \"next_speaker\": \"小李\", "
                            + "\"should_conclude\": false, \"guidance\": \"请从成本角度补充\"}");

            ModeratorService.Decision d = service.decide(topic, members, Map.of(10L, 3L, 11L, 1L));

            assertThat(d).isNotNull();
            assertThat(d.shouldContinue()).isTrue();
            assertThat(d.nextSpeakerId()).isEqualTo(11L);
            assertThat(d.shouldConclude()).isFalse();
            assertThat(d.guidance()).isEqualTo("请从成本角度补充");
            assertThat(d.wantsConclude()).isFalse();
        }

        @Test
        @DisplayName("容忍 ```json 包裹与前后杂文")
        void wrappedJsonShouldBeTolerated() {
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn(
                    "好的，我的判断是：\n```json\n{\"should_continue\": false, \"next_speaker\": \"老王\", "
                            + "\"should_conclude\": false, \"guidance\": \"\"}\n```");

            ModeratorService.Decision d = service.decide(topic, members, Map.of());

            assertThat(d).isNotNull();
            assertThat(d.shouldContinue()).isFalse();
            // should_continue=false 即无信息增量，视为要收束
            assertThat(d.wantsConclude()).isTrue();
        }

        @Test
        @DisplayName("should_conclude=true 时 wantsConclude 为真")
        void shouldConcludeTrueShouldWantConclude() {
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn(
                    "{\"should_continue\": true, \"next_speaker\": \"老王\", "
                            + "\"should_conclude\": true, \"guidance\": \"\"}");

            ModeratorService.Decision d = service.decide(topic, members, Map.of());

            assertThat(d).isNotNull();
            assertThat(d.wantsConclude()).isTrue();
        }

        @Test
        @DisplayName("next_speaker 不在群内：nextSpeakerId 为 null，其余字段正常")
        void unknownSpeakerShouldYieldNullSpeakerId() {
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn(
                    "{\"should_continue\": true, \"next_speaker\": \"路人甲\", "
                            + "\"should_conclude\": false, \"guidance\": \"继续\"}");

            ModeratorService.Decision d = service.decide(topic, members, Map.of());

            assertThat(d).isNotNull();
            assertThat(d.nextSpeakerId()).isNull();
            assertThat(d.guidance()).isEqualTo("继续");
        }
    }

    @Nested
    @DisplayName("降级回退")
    class Fallback {

        @Test
        @DisplayName("未开启时返回 null 且不调 LLM")
        void disabledShouldReturnNullWithoutLlm() throws Exception {
            setField("enabled", false);

            assertThat(service.decide(topic, members, Map.of())).isNull();
            verify(llmService, never()).chat(any(), anyString(), anyList());
        }

        @Test
        @DisplayName("成员为空时返回 null")
        void emptyMembersShouldReturnNull() {
            assertThat(service.decide(topic, List.of(), Map.of())).isNull();
        }

        @Test
        @DisplayName("LLM 异常时返回 null")
        void llmFailureShouldReturnNull() {
            when(llmService.chat(any(Agent.class), anyString(), anyList()))
                    .thenThrow(new RuntimeException("LLM 超时"));

            assertThat(service.decide(topic, members, Map.of())).isNull();
        }

        @Test
        @DisplayName("输出不含 JSON 时返回 null")
        void nonJsonOutputShouldReturnNull() {
            when(llmService.chat(any(Agent.class), anyString(), anyList()))
                    .thenReturn("我觉得大家聊得差不多了");

            assertThat(service.decide(topic, members, Map.of())).isNull();
        }
    }

    @Nested
    @DisplayName("主持人身份")
    class HostIdentity {

        @Test
        @DisplayName("配置了独立模型：借用首成员端点但用主持人模型")
        void configuredModelShouldOverrideMemberModel() throws Exception {
            setField("model", "moderator-fast");
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn(
                    "{\"should_continue\": true, \"next_speaker\": \"老王\", "
                            + "\"should_conclude\": false, \"guidance\": \"\"}");

            service.decide(topic, members, Map.of());

            ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
            verify(llmService).chat(captor.capture(), anyString(), anyList());
            Agent host = captor.getValue();
            assertThat(host.getName()).isEqualTo("主持人");
            assertThat(host.getModelName()).isEqualTo("moderator-fast");
            assertThat(host.getBaseUrl()).isEqualTo("https://api.test.com");
            assertThat(host.getApiKey()).isEqualTo("sk-test");
        }

        @Test
        @DisplayName("未配置模型：复用首成员模型")
        void blankModelShouldReuseMemberModel() {
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn(
                    "{\"should_continue\": true, \"next_speaker\": \"老王\", "
                            + "\"should_conclude\": false, \"guidance\": \"\"}");

            service.decide(topic, members, Map.of());

            ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
            verify(llmService).chat(captor.capture(), anyString(), anyList());
            assertThat(captor.getValue().getModelName()).isEqualTo("qwen-plus");
        }

        @Test
        @DisplayName("判定输入包含主题/成员发言次数/讨论记录")
        void inputShouldContainTopicRosterAndDialogue() {
            com.dingring.domain.group.GroupMessage msg = new com.dingring.domain.group.GroupMessage();
            msg.setContent("我先说两句");
            when(messageRepository.findRecentByTopicId(eq(100L), anyInt())).thenReturn(List.of(msg));
            when(messageAssembler.resolveSenderName(any())).thenReturn("老王");
            when(llmService.chat(any(Agent.class), anyString(), anyList())).thenReturn(
                    "{\"should_continue\": true, \"next_speaker\": \"老王\", "
                            + "\"should_conclude\": false, \"guidance\": \"\"}");

            service.decide(topic, members, Map.of(10L, 2L));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LlmService.ChatTurn>> turnsCaptor =
                    ArgumentCaptor.forClass((Class) List.class);
            verify(llmService).chat(any(Agent.class), anyString(), turnsCaptor.capture());
            String input = turnsCaptor.getValue().get(0).content();
            assertThat(input).contains("测试主题");
            assertThat(input).contains("老王").contains("已发言 2 次");
            assertThat(input).contains("小李").contains("已发言 0 次");
            assertThat(input).contains("老王: 我先说两句");
        }
    }
}
