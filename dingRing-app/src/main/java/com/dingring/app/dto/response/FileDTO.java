package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文件信息（不暴露 path 等内部存储细节）。
 */
@Data
@Builder
public class FileDTO {

    private Long id;
    private Long knowledgeBaseId;
    private String name;
    /** 文件类型：PDF / MARKDOWN / TXT */
    private String fileType;
    private Long fileSize;
    /** 处理状态：UPLOADED/CHUNKED/EMBEDDED/READY/FAILED */
    private String status;
    private Integer chunkCount;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
