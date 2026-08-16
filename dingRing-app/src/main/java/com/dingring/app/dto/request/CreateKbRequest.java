package com.dingring.app.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * POST /api/kb 创建知识库请求。
 */
@Data
public class CreateKbRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    /** 知识库描述（用途说明，建库时必填） */
    @NotBlank(message = "知识库描述不能为空")
    private String description;
}
