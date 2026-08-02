package com.dingring.adapter.websocket;

import com.dingring.app.orchestrator.ChatOrchestrator;
import com.dingring.app.service.GroupAppService;
import com.dingring.app.service.TopicAppService;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * WS 入站消息分发器：解析 {type, data} 并路由到应用服务。
 * <p>C→S 消息类型：SEND_MESSAGE / REPLY_MESSAGE / CONCLUDE_TOPIC。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsMessageDispatcher {

    private final ObjectMapper objectMapper;
    private final ChatOrchestrator chatOrchestrator;
    private final TopicAppService topicAppService;
    private final WsSessionManager sessionManager;

    /**
     * @param groupId 该连接绑定的群（握手时从 query 解析）
     */
    public void dispatch(Long groupId, WebSocketSession session, String payload) {
        LogHelper.putTrace(groupId, null);
        LogHelper.printLog(WsMessageDispatcher.class, "WsMessageDispatcher.dispatch", "DISPATCH", "开始执行dispatch",
                "groupId={} sessionId={}", groupId, session);
        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText("");
            JsonNode data = root.path("data");
            LogHelper.printLog(WsMessageDispatcher.class, "WsMessageDispatcher.dispatch", "DISPATCH", "收到入站消息",
                    JsonHelper.mapToJsonStr(Map.of("groupId", groupId, "type", type, "payload", payload)));
            switch (type) {
                case WsConstants.SEND_MESSAGE -> chatOrchestrator.onUserMessage(
                        groupId, GroupAppService.DEFAULT_USER_ID,
                        data.path("content").asText(), null);
                case WsConstants.REPLY_MESSAGE -> chatOrchestrator.onUserMessage(
                        groupId, GroupAppService.DEFAULT_USER_ID,
                        data.path("content").asText(),
                        data.path("replyToMessageId").isNumber() ? data.path("replyToMessageId").asLong() : null);
                case WsConstants.CONCLUDE_TOPIC -> topicAppService.conclude(
                        data.path("topicId").asLong());
                default -> {
                    LogHelper.printWarnLog(WsMessageDispatcher.class, "WsMessageDispatcher.dispatch", "DISPATCH", "未知消息类型已忽略", "groupId={} type={}", groupId, type);
                    pushError(session, ErrorCode.PARAM_INVALID, "未知消息类型: " + type);
                }
            }
        } catch (BizException e) {
            LogHelper.printWarnLog(WsMessageDispatcher.class, "WsMessageDispatcher.dispatch", "DISPATCH", "消息处理业务异常",
                    "groupId={} errorCode={} message={}", groupId, e.getErrorCode(), e.getMessage());
            pushError(session, e.getErrorCode(), e.getMessage());
        } catch (Exception e) {
            LogHelper.printWarnLog(WsMessageDispatcher.class, "WsMessageDispatcher.dispatch", "DISPATCH", "消息处理异常", "groupId={} payload={}", groupId, payload, e);
            pushError(session, ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getDefaultMessage());
        } finally {
            LogHelper.clearTrace();
        }
    }

    private void pushError(WebSocketSession session, ErrorCode errorCode, String message) {
        sessionManager.pushToSession(session, WsConstants.ERROR, Map.of(
                "success", false,
                "errorCode", errorCode.name(),
                "message", message));
    }
}