package com.dingring.infrastructure.agent.tool;

import com.dingring.common.util.LogHelper;
import com.dingring.domain.discussion.Topic;
import com.dingring.domain.discussion.TopicRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 历史主题查询工具（Phase D）。
 * <p>Agent 收束讨论时可调用此工具查询群内已结束讨论的历史结论，
 * 用于参考过往共识、避免重复结论。
 * <p>迁移自 ContextBuilder#buildForConclusion（已删，见 GroupContextMemoryServiceImpl） 的历史记忆注入部分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicHistoryTool {

    private final TopicRepository topicRepository;

    /**
     * 查询群内已结束讨论的历史结论。
     *
     * @param groupId 群组 ID
     * @param limit   返回条数上限（默认 5）
     * @return 历史结论文本（无历史时返回提示语）
     */
    @Tool(description = "查询群内已结束讨论的历史结论，用于参考过往共识避免重复结论")
    public String queryTopicHistory(
            @ToolParam(description = "群组 ID") Long groupId,
            @ToolParam(description = "返回条数上限，建议 3-5 条") Integer limit) {
        int max = limit != null && limit > 0 ? Math.min(limit, 10) : 5;
        List<Topic> closed = topicRepository.findClosedByGroupId(groupId);
        if (closed.isEmpty()) {
            return "暂无历史讨论记录";
        }

        StringBuilder sb = new StringBuilder("群内历史讨论结论：");
        int count = 0;
        for (Topic t : closed) {
            if (count >= max) break;
            sb.append("\n\n## ").append(t.getTitle()).append("\n");
            sb.append(t.getConclusion() != null ? t.getConclusion() : "（无结论文本）");
            count++;
        }
        LogHelper.printLog(TopicHistoryTool.class, "TopicHistoryTool.queryTopicHistory",
                "TOOL_TOPIC_HISTORY", "历史主题查询",
                "groupId={} 返回条数={}/{}", groupId, count, closed.size());
        return sb.toString();
    }
}
