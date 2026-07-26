package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * POST /api/groups/{id}/topics 创建主题请求。
 */
@Data
public class CreateTopicRequest {

    @NotBlank(message = "主题标题不能为空")
    private String title;
}
