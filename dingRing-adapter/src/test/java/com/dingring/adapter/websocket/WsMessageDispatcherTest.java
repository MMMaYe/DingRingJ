package com.dingring.adapter.websocket;

import com.dingring.app.orchestrator.ChatOrchestrator;
import com.dingring.app.service.TopicAppService;
import com.dingring.common.constant.WsConstants;
import com.dingring.common.exception.BizException;
import com.dingring.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link WsMessageDispatcher} 单元测试。
 * <p>验证 {type,data} 路由到不同应用服务，以及异常分支的错误推送。
 */
@DisplayName("WsMessageDispatcher 入站消息分发")
@ExtendWith(MockitoExtension.class)
class WsMessageDispatcherTest {

    @Spy
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ChatOrchestrator chatOrchestrator;

    @Mock
    private TopicAppService topicAppService;

    @Mock
    private WsSessionManager sessionManager;

    @Mock
    private WebSocketSession session;

    @InjectMocks
    private WsMessageDispatcher dispatcher;

    private String payload(String type, String dataJson) {
        return "{\"type\":\"" + type + "\",\"data\":" + dataJson + "}";
    }

    @Nested
    @DisplayName("消息路由")
    class Routing {

        @Test
        @DisplayName("SEND_MESSAGE 路由到 chatOrchestrator.onUserMessage（无 replyTo）")
        void shouldRouteSendMessage() {
            dispatcher.dispatch(1L, session, payload(WsConstants.SEND_MESSAGE,
                    "{\"content\":\"你好\"}"));

            verify(chatOrchestrator).onUserMessage(eq(1L), eq(1L), eq("你好"), eq(null));
        }

        @Test
        @DisplayName("REPLY_MESSAGE 路由到 chatOrchestrator.onUserMessage（带 replyToMessageId）")
        void shouldRouteReplyMessage() {
            dispatcher.dispatch(1L, session, payload(WsConstants.REPLY_MESSAGE,
                    "{\"content\":\"回复\",\"replyToMessageId\":42}"));

            verify(chatOrchestrator).onUserMessage(eq(1L), eq(1L), eq("回复"), eq(42L));
        }

        @Test
        @DisplayName("REPLY_MESSAGE 中 replyToMessageId 非数字时传 null")
        void shouldRouteReplyMessageWithNonNumericReplyTo() {
            dispatcher.dispatch(1L, session, payload(WsConstants.REPLY_MESSAGE,
                    "{\"content\":\"回复\",\"replyToMessageId\":\"abc\"}"));

            verify(chatOrchestrator).onUserMessage(eq(1L), eq(1L), eq("回复"), eq(null));
        }

        @Test
        @DisplayName("CONCLUDE_TOPIC 路由到 topicAppService.conclude")
        void shouldRouteConcludeTopic() {
            dispatcher.dispatch(1L, session, payload(WsConstants.CONCLUDE_TOPIC,
                    "{\"topicId\":100}"));

            verify(topicAppService).conclude(eq(100L));
        }

        @Test
        @DisplayName("未知消息类型推送 PARAM_INVALID 错误")
        void unknownTypeShouldPushError() {
            dispatcher.dispatch(1L, session, payload("UNKNOWN_TYPE", "{}"));

            verify(sessionManager).pushToSession(eq(session), eq(WsConstants.ERROR), any());
            verify(chatOrchestrator, never()).onUserMessage(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("异常处理")
    class ErrorHandling {

        @Test
        @DisplayName("应用服务抛 BizException 时推送对应 ErrorCode")
        void bizExceptionShouldPushError() {
            doThrow(new BizException(ErrorCode.NOT_FOUND, "主题不存在"))
                    .when(topicAppService).conclude(100L);

            dispatcher.dispatch(1L, session, payload(WsConstants.CONCLUDE_TOPIC,
                    "{\"topicId\":100}"));

            verify(sessionManager).pushToSession(eq(session), eq(WsConstants.ERROR), any());
        }

        @Test
        @DisplayName("JSON 格式错误时推送 INTERNAL_ERROR")
        void malformedJsonShouldPushInternalError() {
            dispatcher.dispatch(1L, session, "not a valid json");

            verify(sessionManager).pushToSession(eq(session), eq(WsConstants.ERROR), any());
        }

        @Test
        @DisplayName("type 字段缺失时按未知类型处理")
        void missingTypeShouldPushError() {
            dispatcher.dispatch(1L, session, "{\"data\":{\"content\":\"hi\"}}");

            verify(sessionManager).pushToSession(eq(session), eq(WsConstants.ERROR), any());
        }
    }
}
