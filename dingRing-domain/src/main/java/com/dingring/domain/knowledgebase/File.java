package com.dingring.domain.knowledgebase;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文件实体（v1.0 暂缓，随知识库一并实现）。
 */
@Data
public class File {

    private Long id;
    private Long knowledgeBaseId;
    private String name;
    private String path;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
