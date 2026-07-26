package com.dingring.domain.knowledgebase;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识库实体（v1.0 暂缓，P2 阶段实现）。
 */
@Data
public class KnowledgeBase {

    private Long id;
    private String name;
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
