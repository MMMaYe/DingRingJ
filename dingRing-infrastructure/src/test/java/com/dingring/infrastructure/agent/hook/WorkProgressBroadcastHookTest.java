package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.dingring.common.constant.WsConstants;
import com.dingring.domain.service.GroupBroadcastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkProgressBroadcastHook} 进度广播单测。
 * <p>验证：委派子 Agent 前推送 WORK_PROGRESS、完成后推送 WORK_RESULT；
 * groupId 缺失（极端场景）时跳过广播但不阻断工具执行。
 */
@DisplayName("WorkProgressBroadcastHook 进度广播")
class WorkProgressBroadcastHookTest {

    private GroupBroadcastService broadcastService;
    private WorkProgressBroadcastHook hook;

    @BeforeEach
    void setUp() {
        broadcastService = mock(GroupBroadcastService.class);
        hook = new WorkProgressBroadcastHook(broadcastService);
    }

    private ToolCallRequest requestWithState(Map<String, Object> stateData, String toolName) {
        OverAllState state = new OverAllState(stateData);
        ToolCallExecutionContext ctx = new ToolCallExecutionContext(RunnableConfig.builder().build(), state);
        return ToolCallRequest.builder()
                .toolName(toolName)
                .arguments("{\"task\":\"调研缓存方案\"}")
                .toolCallId("call-1")
                .executionContext(ctx)
                .build();
    }

    @Test
    @DisplayName("委派前推送 WORK_PROGRESS，完成后推送 WORK_RESULT（携带子 Agent 名与结果摘要）")
    void shouldBroadcastProgressAndResult() {
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolCallResponse.builder()
                        .content("子 Agent 执行结果：使用 Redis 集群")
                        .toolName("worker-a")
                        .toolCallId("call-1")
                        .build());
        ToolCallRequest request = requestWithState(Map.of("groupId", 1L), "worker-a");

        ToolCallResponse response = hook.getToolInterceptors().get(0)
                .interceptToolCall(request, handler);

        assertThat(response.getResult()).contains("Redis 集群");

        ArgumentCaptor<Map<String, Object>> progressCaptor = ArgumentCaptor.forClass(Map.class);
        verify(broadcastService).broadcast(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(WsConstants.WORK_PROGRESS), progressCaptor.capture());
        assertThat(progressCaptor.getValue())
                .containsEntry("step", "DELEGATE")
                .containsEntry("agentName", "worker-a");

        ArgumentCaptor<Map<String, Object>> resultCaptor = ArgumentCaptor.forClass(Map.class);
        verify(broadcastService).broadcast(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(WsConstants.WORK_RESULT), resultCaptor.capture());
        assertThat(resultCaptor.getValue())
                .containsEntry("step", "DELEGATE_DONE")
                .containsEntry("agentName", "worker-a")
                .containsEntry("result", "子 Agent 执行结果：使用 Redis 集群");
    }

    @Test
    @DisplayName("groupId 缺失时跳过广播但工具正常执行")
    void shouldSkipBroadcastWhenGroupIdMissing() {
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolCallResponse.builder().content("ok").toolName("worker-a").toolCallId("call-1").build());
        ToolCallRequest request = requestWithState(Map.of(), "worker-a");

        ToolCallResponse response = hook.getToolInterceptors().get(0)
                .interceptToolCall(request, handler);

        assertThat(response.getResult()).isEqualTo("ok");
        verify(broadcastService, never()).broadcast(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("子 Agent 执行异常时推送失败结果并向上抛出")
    void shouldBroadcastFailureAndRethrow() {
        ToolCallHandler handler = mock(ToolCallHandler.class);
        when(handler.call(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("LLM 调用失败"));
        ToolCallRequest request = requestWithState(Map.of("groupId", 1L), "worker-a");

        try {
            hook.getToolInterceptors().get(0).interceptToolCall(request, handler);
        } catch (IllegalStateException e) {
            assertThat(e).hasMessage("LLM 调用失败");
        }

        ArgumentCaptor<Map<String, Object>> resultCaptor = ArgumentCaptor.forClass(Map.class);
        verify(broadcastService).broadcast(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(WsConstants.WORK_RESULT), resultCaptor.capture());
        assertThat(resultCaptor.getValue())
                .containsEntry("step", "DELEGATE_FAILED")
                .containsEntry("error", "LLM 调用失败");
    }
}
