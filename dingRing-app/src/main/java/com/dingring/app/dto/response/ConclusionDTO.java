package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GET /api/topics/{id}/conclusion 结论响应。
 */
@Data
@Builder
public class ConclusionDTO {

    private Long topicId;
    private String title;
    /** STAR 框架 Markdown */
    private String conclusion;
    private long messageCount;
    private LocalDateTime closedAt;
    /** 总结 Agent 花名 */
    private String concluderAgentName;
}
