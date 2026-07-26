package com.dingring.domain.discussion;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识卡片实体（表 knowledge_card）。一个 Topic 对应多张卡片，每张是一个 Q&A 对。
 */
@Data
public class KnowledgeCard {

    private Long id;
    /** 来源 Topic */
    private Long topicId;
    /** 问题 */
    private String question;
    /** 参考答案 */
    private String answer;
    /** LLM 识别的分类 */
    private String category;
    /** 扩展字段 */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
