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
    /** 清洗结果状态：SKIPPED（未启用）/CLEANED（已完成）/FAILED（取消或失败）/null（进行中或未开始） */
    private String cleaningStatus;
    /** 活跃摄入 run 的状态：CLEANING_WAITING/CLEANING_RUNNING/EMBEDDING 等，无活跃 run 为 null（前端徽章依据） */
    private String runStatus;
    private Integer chunkCount;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
