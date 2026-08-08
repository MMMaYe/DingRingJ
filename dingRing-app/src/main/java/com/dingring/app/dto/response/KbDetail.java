package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/kb/{id} 知识库详情（含文件列表）。
 */
@Data
@Builder
public class KbDetail {

    private Long id;
    private String name;
    private String scope;
    private Long groupId;
    private String status;
    /** 知识库下的文件列表 */
    private List<FileDTO> files;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
