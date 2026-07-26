package com.dingring.app.service;

import com.dingring.app.dto.request.CreateAgentRequest;
import com.dingring.app.dto.request.UpdateAgentRequest;
import com.dingring.app.dto.response.AgentDTO;
import com.dingring.common.exception.BizException;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentAppService} 单元测试。
 */
@DisplayName("AgentAppService Agent 管理服务")
class AgentAppServiceTest {

    private AgentRepository agentRepository;
    private AgentAppService service;

    @BeforeEach
    void setUp() {
        agentRepository = mock(AgentRepository.class);
        service = new AgentAppService(agentRepository);
    }

    @Nested
    @DisplayName("create 创建 Agent")
    class Create {

        @Test
        @DisplayName("成功创建并回填字段，apiKey 不回传")
        void shouldCreateAndReturnDtoWithoutApiKey() {
            CreateAgentRequest req = new CreateAgentRequest();
            req.setName("老王");
            req.setBaseUrl("https://api.deepseek.com");
            req.setApiKey("sk-secret");
            req.setModelName("deepseek-chat");
            req.setDescription("后端专家");
            req.setSystemPrompt("你是后端架构师");
            req.setFeature(Map.of("temperature", 0.5));

            // 模拟仓储回填 id
            when(agentRepository.save(any(Agent.class))).thenAnswer(inv -> {
                Agent a = inv.getArgument(0);
                a.setId(100L);
                return 100L;
            });

            AgentDTO dto = service.create(req);

            assertThat(dto.getId()).isEqualTo(100L);
            assertThat(dto.getName()).isEqualTo("老王");
            assertThat(dto.getModelName()).isEqualTo("deepseek-chat");
            // apiKey 字段在 AgentDTO 中不存在，天然不会回传（设计上不暴露）
            assertThat(dto.getBaseUrl()).isEqualTo("https://api.deepseek.com");
            verify(agentRepository).save(any(Agent.class));
        }
    }

    @Nested
    @DisplayName("update 修改 Agent")
    class Update {

        @Test
        @DisplayName("Agent 不存在时抛 BizException")
        void shouldThrowWhenAgentNotFound() {
            when(agentRepository.findById(99L)).thenReturn(Optional.empty());
            UpdateAgentRequest req = new UpdateAgentRequest();
            req.setName("x");
            req.setBaseUrl("https://x");
            req.setModelName("m");

            assertThatThrownBy(() -> service.update(99L, req))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("Agent 不存在");
        }

        @Test
        @DisplayName("apiKey 为空时保留原 Key")
        void emptyApiKeyShouldKeepOriginal() {
            Agent existing = new Agent();
            existing.setId(1L);
            existing.setApiKey("sk-original");
            when(agentRepository.findById(1L)).thenReturn(Optional.of(existing));

            UpdateAgentRequest req = new UpdateAgentRequest();
            req.setName("老王");
            req.setBaseUrl("https://api.x.com");
            req.setApiKey("");   // 空字符串表示不修改
            req.setModelName("m");

            service.update(1L, req);

            assertThat(existing.getApiKey()).isEqualTo("sk-original");
            verify(agentRepository).update(existing);
        }

        @Test
        @DisplayName("apiKey 非空时更新为新 Key")
        void newApiKeyShouldReplace() {
            Agent existing = new Agent();
            existing.setId(1L);
            existing.setApiKey("sk-old");
            when(agentRepository.findById(1L)).thenReturn(Optional.of(existing));

            UpdateAgentRequest req = new UpdateAgentRequest();
            req.setName("老王");
            req.setBaseUrl("https://api.x.com");
            req.setApiKey("sk-new");
            req.setModelName("m");

            service.update(1L, req);

            assertThat(existing.getApiKey()).isEqualTo("sk-new");
        }
    }

    @Test
    @DisplayName("list 返回全部 Agent（DTO 不含 apiKey）")
    void listShouldReturnAllAgents() {
        Agent a1 = new Agent();
        a1.setId(1L);
        a1.setName("老王");
        a1.setModelName("m1");
        Agent a2 = new Agent();
        a2.setId(2L);
        a2.setName("小李");
        a2.setModelName("m2");
        when(agentRepository.findAll()).thenReturn(List.of(a1, a2));

        List<AgentDTO> result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result).extracting(AgentDTO::getName).containsExactly("老王", "小李");
    }
}
