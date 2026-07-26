package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GET /api/groups 群列表项。
 */
@Data
@Builder
public class GroupSummary {

    private Long id;
    private String name;
    private int memberCount;
    /** 当前进行中的主题标题（无则 null） */
    private String activeTopicTitle;
    /** 最后一条消息预览（截断） */
    private String lastMessagePreview;
    private LocalDateTime lastMessageTime;
}
