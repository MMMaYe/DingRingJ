package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题摘要（群详情/主题列表用）。
 */
@Data
@Builder
public class TopicSummary {

    private Long id;
    private String title;
    /** IN_PROGRESS / CONCLUDING / CLOSED / ARCHIVED */
    private String status;
    private long messageCount;
    /** 当前轮次（Agent 发言条数，与收束熔断口径一致） */
    private long round;
    /** 最大讨论轮次 */
    private int maxRounds;
    private LocalDateTime createTime;
}
