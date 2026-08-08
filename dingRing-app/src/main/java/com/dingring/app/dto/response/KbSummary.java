package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GET /api/kb 知识库列表项。
 */
@Data
@Builder
public class KbSummary {

    private Long id;
    private String name;
    /** 作用域：GLOBAL / GROUP */
    private String scope;
    private Long groupId;
    /** 状态：ACTIVE / PROCESSING / FAILED */
    private String status;
    private LocalDateTime createTime;
}
