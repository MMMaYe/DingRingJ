package com.dingring.domain.knowledgebase;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识库实体。
 * <p>Phase E 补全：scope(全局/群专属) + status(处理状态) + groupId(群专属关联)。
 * <p>文件内容存在 PostgreSQL vector_store 的 content 字段；元信息存 MySQL。
 */
@Data
public class KnowledgeBase {

    /** 作用域：全局知识（所有群可检索） */
    public static final String SCOPE_GLOBAL = "GLOBAL";
    /** 作用域：群专属知识（仅指定群可检索） */
    public static final String SCOPE_GROUP = "GROUP";
    /** 状态：正常可用 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：处理中 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private String name;
    /** 作用域：GLOBAL / GROUP */
    private String scope;
    /** scope=GROUP 时关联群 ID，GLOBAL 时为 null */
    private Long groupId;
    /** 状态：ACTIVE / PROCESSING / FAILED */
    private String status;
    /** JSON 扩展字段 */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
