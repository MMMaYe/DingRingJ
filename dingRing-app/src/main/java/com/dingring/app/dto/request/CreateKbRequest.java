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

    /** 作用域：GLOBAL / GROUP，留空默认 GLOBAL */
    private String scope;

    /** scope=GROUP 时关联群 ID，GLOBAL 时为 null */
    private Long groupId;
}
