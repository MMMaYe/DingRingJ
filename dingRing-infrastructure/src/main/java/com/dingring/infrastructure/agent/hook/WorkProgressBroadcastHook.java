package com.dingring.infrastructure.agent.hook;

import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.service.GroupBroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * WORK 进度广播 Hook（Phase F Supervisor 模式）。
 * <p>挂载在 Supervisor Agent 上，通过 {@link #getToolInterceptors()} 提供 ToolInterceptor——
 * Supervisor 的每次工具调用即「委派子 Agent」，在调用前后推送进度到群聊。
 * <p>为什么要用 ToolInterceptor 而不是 afterModel：
 * <ul>
 *   <li>afterModel 在每次模型调用后触发（含最终回答），无法精确区分「委派子 Agent」时机</li>
 *   <li>ToolInterceptor 只在工具执行路径上触发，且可通过
 *       {@link ToolCallExecutionContext#state()} 拿到 OverAllState（内含 groupId）</li>
 *   <li>SAA ReactAgent 构造时会合并 Hook.getToolInterceptors()（collectAndMergeToolInterceptors），
 *       因此以 Hook 形式注册即可被 AgentToolNode 装配</li>
 * </ul>
 * <p>推送内容：
 * <ul>
 *   <li>调用前：WORK_PROGRESS（step=委派中，携带子 Agent 名与参数摘要）</li>
 *   <li>调用后：WORK_RESULT（step=完成，携带子 Agent 产出截断摘要）</li>
 * </ul>
 * <p>WORK_CONFIRM_REQUEST 类型已定义（WsConstants），供后续接入 SAA InterruptionHook
 * 实现「子 Agent 向用户提问」时推送，本阶段不主动触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkProgressBroadcastHook extends ModelHook {

    /** 群聊广播端口（infrastructure 层 WsBroadcastAdapter 实现） */
    private final GroupBroadcastService groupBroadcastService;

    /** 进度拦截器单例（Hook 无状态，拦截器也无状态，可共享） */
    private final ToolInterceptor progressInterceptor = new WorkProgressInterceptor();

    /** 结果摘要最大长度（避免把子 Agent 完整产出刷进 WS 帧） */
    private static final int RESULT_SUMMARY_LEN = 200;

    @Override
    public String getName() {
        return "work-progress-broadcast-hook";
    }

    /**
     * 提供 ToolInterceptor：ReactAgent 构造时会调用此方法收集并装配到 AgentToolNode。
     */
    @Override
    public List<ToolInterceptor> getToolInterceptors() {
        return List.of(progressInterceptor);
    }

    /**
     * 进度拦截器：包装 Supervisor 的每次工具调用（子 Agent 委派）。
     * <p>无法拿到 state 时（极端场景 executionContext 为空）跳过广播，不阻断工具执行。
     */
    private class WorkProgressInterceptor extends ToolInterceptor {

        @Override
        public String getName() {
            return "work-progress-broadcast-interceptor";
        }

        @Override
        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
            Long groupId = resolveGroupId(request);
            if (groupId == null) {
                return handler.call(request);
            }

            broadcastProgress(groupId, request);
            try {
                ToolCallResponse response = handler.call(request);
                broadcastResult(groupId, request, response);
                return response;
            } catch (Exception e) {
                LogHelper.printWarnLog(WorkProgressBroadcastHook.class, "interceptToolCall", "WORK_PROGRESS",
                        "子 Agent 执行失败", "tool={} 错误: {}", request.getToolName(), e.getMessage());
                broadcastFailure(groupId, request, e.getMessage());
                throw e;
            }
        }

        /** 从执行上下文 state 解析 groupId（读不到则跳过广播） */
        private Long resolveGroupId(ToolCallRequest request) {
            return request.getExecutionContext()
                    .flatMap(ctx -> ctx.state().<Long>value(StateKeyHolder.GROUP_ID))
                    .orElse(null);
        }

        private void broadcastProgress(Long groupId, ToolCallRequest request) {
            groupBroadcastService.broadcast(groupId, WsConstants.WORK_PROGRESS, Map.of(
                    "step", "DELEGATE",
                    "agentName", request.getToolName(),
                    "arguments", summarize(request.getArguments()),
                    "progress", "正在委派子 Agent 执行任务"));
            LogHelper.printLog(WorkProgressBroadcastHook.class, "broadcastProgress", "WORK_PROGRESS",
                    "委派子 Agent", "groupId={} tool={}", groupId, request.getToolName());
        }

        private void broadcastResult(Long groupId, ToolCallRequest request, ToolCallResponse response) {
            groupBroadcastService.broadcast(groupId, WsConstants.WORK_RESULT, Map.of(
                    "step", "DELEGATE_DONE",
                    "agentName", request.getToolName(),
                    "result", summarize(response.getResult())));
            LogHelper.printLog(WorkProgressBroadcastHook.class, "broadcastResult", "WORK_RESULT",
                    "子 Agent 完成", "groupId={} tool={}", groupId, request.getToolName());
        }

        private void broadcastFailure(Long groupId, ToolCallRequest request, String error) {
            groupBroadcastService.broadcast(groupId, WsConstants.WORK_RESULT, Map.of(
                    "step", "DELEGATE_FAILED",
                    "agentName", request.getToolName(),
                    "error", summarize(error)));
        }

        /** 截断长文本（参数/结果），避免 WS 帧过大 */
        private String summarize(String text) {
            if (text == null) {
                return "";
            }
            return text.length() <= RESULT_SUMMARY_LEN ? text : text.substring(0, RESULT_SUMMARY_LEN) + "...";
        }
    }

    /** state key 本地持有（避免重复字符串；与 WorkNode 写入的 groupId 一致） */
    private static final class StateKeyHolder {
        static final String GROUP_ID = "groupId";
        private StateKeyHolder() {}
    }
}
