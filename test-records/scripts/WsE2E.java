import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.LocalTime;
import java.util.concurrent.*;

/**
 * 联调步骤 06：WebSocket 端到端联调客户端（模拟前端 ws://.../ws/chat?groupId=4 协议）。
 * 流程：发送讨论消息 -> 等待 Agent 真实 LLM 回复(最多2条) -> @苏教授 触发收束 -> 等待 TOPIC_CLOSED 与 CARD_GENERATED。
 * 运行：java WsE2E.java <groupId>
 */
public class WsE2E {

    static final CountDownLatch closed = new CountDownLatch(1);
    static volatile int agentMsgCount = 0;
    static volatile boolean topicClosed = false;
    static volatile boolean cardGenerated = false;
    static final StringBuilder partial = new StringBuilder();

    public static void main(String[] args) throws Exception {
        String groupId = args.length > 0 ? args[0] : "4";
        String url = "ws://localhost:8080/ws/chat?groupId=" + groupId;
        log("连接 WebSocket: " + url);

        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log("[OK] WebSocket 已连接");
                        WebSocket.Listener.super.onOpen(webSocket);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            String frame = partial.toString();
                            partial.setLength(0);
                            handleFrame(frame);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        log("[WS关闭] code=" + statusCode + " reason=" + reason);
                        closed.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log("[WS错误] " + error);
                        closed.countDown();
                    }
                }).join();

        // ---- 阶段1：发送讨论消息，触发 Agent 真实 LLM 回复 ----
        String msg1 = "{\"type\":\"SEND_MESSAGE\",\"data\":{\"content\":\"大家聊聊 MySQL 索引失效都有哪些常见场景？最好举个实际例子\"}}";
        log(">>> 发送用户消息: " + msg1);
        ws.sendText(msg1, true).join();

        // 等待最多 180s 收到 2 条 Agent 回复（auto-replies=2）
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline && agentMsgCount < 2) {
            Thread.sleep(1000);
        }
        log("阶段1结束: 收到 Agent 回复 " + agentMsgCount + " 条");

        // ---- 阶段2：@专家 触发收束，等待 TOPIC_CLOSED ----
        String msg2 = "{\"type\":\"SEND_MESSAGE\",\"data\":{\"content\":\"@苏教授 请总结一下今天的讨论\"}}";
        log(">>> 发送收束消息: " + msg2);
        ws.sendText(msg2, true).join();

        deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline && !topicClosed) {
            Thread.sleep(1000);
        }
        log("阶段2结束: TOPIC_CLOSED=" + topicClosed);

        // ---- 阶段3：等待知识卡片异步生成 ----
        deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline && !cardGenerated) {
            Thread.sleep(1000);
        }
        log("阶段3结束: CARD_GENERATED=" + cardGenerated);

        log("联调汇总: agent回复=" + agentMsgCount + ", 主题收束=" + topicClosed + ", 卡片生成=" + cardGenerated);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        closed.await(5, TimeUnit.SECONDS);
    }

    static void handleFrame(String frame) {
        log("<<< 收到帧: " + (frame.length() > 900 ? frame.substring(0, 900) + "...(截断,总长" + frame.length() + ")" : frame));
        if (frame.contains("\"type\":\"NEW_MESSAGE\"") && frame.contains("\"senderType\":\"AGENT\"")) {
            agentMsgCount++;
            log("*** Agent 回复计数 = " + agentMsgCount);
        }
        if (frame.contains("\"type\":\"TOPIC_CLOSED\"")) {
            topicClosed = true;
            log("*** 主题已收束（专家结论已生成）");
        }
        if (frame.contains("\"type\":\"CARD_GENERATED\"")) {
            cardGenerated = true;
            log("*** 知识卡片已生成");
        }
    }

    static void log(String s) {
        System.out.println("[" + LocalTime.now().withNano(0) + "] " + s);
    }
}
