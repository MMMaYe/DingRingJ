package com.dingring.app.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * GET /api/cards/review 复习卡片响应。
 */
@Data
@AllArgsConstructor
public class ReviewCardDTO {

    private List<KnowledgeCardDTO> cards;
    private long total;
}
