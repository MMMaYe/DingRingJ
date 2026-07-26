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
    private LocalDateTime createTime;
}
