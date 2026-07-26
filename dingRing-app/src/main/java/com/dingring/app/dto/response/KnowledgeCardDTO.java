package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识卡片 DTO（GET /api/topics/{id}/cards | GET /api/cards）。
 */
@Data
@Builder
public class KnowledgeCardDTO {

    private Long id;
    private Long topicId;
    /** 冗余，前端卡片列表展示来源 */
    private String topicTitle;
    private String question;
    private String answer;
    /** LLM 识别的分类 */
    private String category;
    private LocalDateTime createTime;
}
