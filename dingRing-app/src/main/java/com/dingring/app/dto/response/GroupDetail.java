package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/groups/{id} 群详情。
 */
@Data
@Builder
public class GroupDetail {

    private Long id;
    private String name;
    private Long ownerId;
    /** 成员列表（含名称头像） */
    private List<MemberInfo> members;
    /** 绑定的知识库 ID 列表（chat_group.knowledge_base_config.kbIds，未绑定为空列表） */
    private List<Long> kbIds;
    /** 当前进行中的主题（无则 null） */
    private TopicSummary activeTopic;
    private LocalDateTime createTime;
}
