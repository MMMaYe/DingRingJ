package com.dingring.domain.user;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 用户实体（现阶段单用户，预留）。
 */
@Data
public class User {

    private Long id;
    /** 用户名 */
    private String name;
    /** 头像 URL */
    private String profilePicture;
    /** 扩展字段 */
    private Map<String, Object> feature;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
