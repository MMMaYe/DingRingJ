package com.dingring.domain.knowledgebase;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识库实体。
 * <p>群与库的关联由群侧绑定（chat_group.knowledge_base_config.kbIds）表达，
 * 本表不再持有 scope/groupId 作用域字段。
 * <p>文件内容存在 PostgreSQL vector_store 的 content 字段；元信息存 MySQL。
 */
@Data
public class KnowledgeBase {

    /** 状态：正常可用 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 状态：处理中 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 状态：失败 */
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    private String name;
    /** 知识库描述（用途说明，建库时填写） */
    private String description;
    /** 状态：ACTIVE / PROCESSING / FAILED */
    private String status;
    /** JSON 扩展字段 */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
