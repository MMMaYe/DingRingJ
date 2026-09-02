package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 提交问卷答案请求。
 */
@Data
public class SubmitQuestionnaireRequest {

    @NotNull(message = "answers 不能为空")
    private Map<String, Object> answers;
}
