package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题沉淀区摘要（跨群已关闭主题列表用）。
 */
@Data
@Builder
public class TopicDigest {

    private Long id;
    private String title;
    /** 来源群 ID */
    private Long groupId;
    /** 来源群名 */
    private String groupName;
    private long messageCount;
    private LocalDateTime closedAt;
    private LocalDateTime createTime;
}
