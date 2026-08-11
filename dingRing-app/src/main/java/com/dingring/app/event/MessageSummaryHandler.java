package com.dingring.app.event;

import com.dingring.app.orchestrator.MessageTagger;
import com.dingring.common.util.JsonHelper;
import com.dingring.common.util.LogHelper;
import com.dingring.domain.agent.Agent;
import com.dingring.domain.agent.AgentRepository;
import com.dingring.domain.event.MessageSent;
import com.dingring.domain.group.GroupMessage;
import com.dingring.domain.group.MessageRepository;
import com.dingring.domain.group.MessageTag;
import com.dingring.domain.service.LlmService;
import com.dingring.infrastructure.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 消息摘要处理器：订阅 {@link MessageSent}，对消息打标签并按需生成观点摘要（方案 6.3.6）。
 * <p>设计要点：
 * <ul>
 *   <li>规则标签（KEY/NOISE）同步落库——保证下一次 advance 读取"观点列表"时标签已就绪
 *       （MessageSent 在节点内同步发布，@EventListener 默认同步执行，先于下一轮上下文构建）</li>
 *   <li>VIEWPOINT 标签的消息异步调一次轻量 LLM：有观点则输出一句话摘要，无观点输出 NO_VIEWPOINT 并改标 NOISE</li>
 *   <li>失败不阻塞主流程（摘要为增强，非依赖）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageSummaryHandler {

    private final MessageRepository messageRepository;
    private final AgentRepository agentRepository;
    private final MessageTagger messageTagger;
    private final LlmService llmService;
    private final PromptTemplateLoader promptLoader;

    @EventListener
    public void onMessageSent(MessageSent event) {
        GroupMessage msg = messageRepository.findById(event.getMessageId()).orElse(null);
        if (msg == null) {
            return;
        }
        // 1. 规则打标签（同步落库，零 LLM 成本）
        MessageTag tag = messageTagger.tag(msg);
        if (tag != MessageTag.VIEWPOINT) {
            messageRepository.updateTagAndViewpoint(msg.getId(), tag, null);
            return;
        }
        // 2. VIEWPOINT：先标记占位（摘要异步补写），再异步调 LLM 判断是否有观点 + 摘要
        messageRepository.updateTagAndViewpoint(msg.getId(), MessageTag.VIEWPOINT, null);
        summarizeAsync(msg);
    }

    /** VIEWPOINT 消息异步 LLM 摘要：有观点则写摘要，无观点则改标 NOISE */
    private void summarizeAsync(GroupMessage msg) {
        Thread.ofVirtual().name("msg-summary-" + msg.getId()).start(() -> {
            try {
                Agent summarizer = agentRepository.findById(msg.getSenderId()).orElse(null);
                if (summarizer == null) {
                    // 无摘要 Agent（如消息已删除）：保留 VIEWPOINT 标签，上下文构建时回退到原文
                    return;
                }
                String prompt = promptLoader.render("message-viewpoint", Map.of("message", msg.getContent()));
                if (prompt == null || prompt.isBlank()) {
                    return;
                }
                String raw = llmService.chat(summarizer, prompt, List.of(),
                        new LlmService.CallOptions(0.0, 200, null, true, false));
                // 模板输出 JSON：{"has_viewpoint": true|false, "summary": "..."}
                // （jsonMode=true 强制合法 JSON，先容忍 ```json 代码块包裹）
                Map<String, Object> parsed = JsonHelper.toMap(stripCodeFence(raw));
                if (parsed.isEmpty()) {
                    // 解析失败：保留 VIEWPOINT 标签，上下文构建回退到原文
                    return;
                }
                boolean hasViewpoint = Boolean.TRUE.equals(parsed.get("has_viewpoint"))
                        || "true".equalsIgnoreCase(String.valueOf(parsed.get("has_viewpoint")));
                if (hasViewpoint) {
                    Object summary = parsed.get("summary");
                    messageRepository.updateTagAndViewpoint(msg.getId(), MessageTag.VIEWPOINT,
                            summary == null ? "" : summary.toString().trim());
                } else {
                    // LLM 判定无观点：改标 NOISE，不摘要
                    messageRepository.updateTagAndViewpoint(msg.getId(), MessageTag.NOISE, null);
                }
            } catch (Exception e) {
                // 摘要失败仅日志，不影响主流程（VIEWPOINT 标签保留，上下文构建回退原文）
                LogHelper.printWarnLog(MessageSummaryHandler.class, "MessageSummaryHandler.summarizeAsync",
                        "MESSAGE_SUMMARY", "消息摘要失败跳过", "messageId={}", msg.getId(), e);
            }
        });
    }

    /** 剥离 LLM 输出可能的 ```json 代码块包裹 */
    private String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String json = raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```(json)?\\s*", "").replaceAll("```\\s*$", "").trim();
        }
        return json;
    }
}
