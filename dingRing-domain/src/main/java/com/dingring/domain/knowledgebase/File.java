package com.dingring.domain.knowledgebase;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文件实体。
 * <p>Phase E 补全：fileType/fileSize/status/chunkCount/errorMsg。
 * <p>文件内容在摄入时被切片后存入 PostgreSQL vector_store，本实体只存元信息。
 */
@Data
public class File {

    /** 状态：已上传 */
    public static final String STATUS_UPLOADED = "UPLOADED";
    /** 状态：切片完成 */
    public static final String STATUS_CHUNKED = "CHUNKED";
    /** 状态：向量化完成 */
    public static final String STATUS_EMBEDDED = "EMBEDDED";
    /** 状态：就绪（可检索） */
    public static final String STATUS_READY = "READY";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_DELETING = "DELETING";
    public static final String CLEANING_SKIPPED = "SKIPPED";
    public static final String CLEANING_CLEANED = "CLEANED";
    public static final String CLEANING_FAILED = "FAILED";

    /** 文件类型：PDF */
    public static final String TYPE_PDF = "PDF";
    /** 文件类型：Markdown */
    public static final String TYPE_MARKDOWN = "MARKDOWN";
    /** 文件类型：TXT */
    public static final String TYPE_TXT = "TXT";

    private Long id;
    private Long knowledgeBaseId;
    private String name;
    /** 本地存储路径 */
    private String path;
    /** 文件类型：PDF / MARKDOWN / TXT */
    private String fileType;
    /** 文件大小（字节） */
    private Long fileSize;
    /** 处理状态：UPLOADED/CHUNKED/EMBEDDED/READY/FAILED */
    private String status;
    /** 切片数 */
    private Integer chunkCount;
    /** 失败原因（status=FAILED 时） */
    private String errorMsg;
    private String docContentHash;
    private Integer currentVersion;
    private String activeRunId;
    private String cleaningStatus;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
