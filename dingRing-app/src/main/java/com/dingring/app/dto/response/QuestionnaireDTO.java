package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 问卷 schema + 当前有效答案（GET /api/questionnaire）。
 */
@Data
@Builder
public class QuestionnaireDTO {

    /** 题目 schema（有序） */
    private List<QuestionDTO> questions;
    /** 当前有效答案（未填过为空 Map） */
    private Map<String, Object> answers;

    @Data
    @Builder
    public static class QuestionDTO {
        private String key;
        private String label;
        private String dimension;
        /** SINGLE / MULTI / TEXT / AREA_LEVELS */
        private String type;
        private Boolean required;
        private List<OptionDTO> options;
    }

    @Data
    @Builder
    public static class OptionDTO {
        private String value;
        private String label;
    }
}
