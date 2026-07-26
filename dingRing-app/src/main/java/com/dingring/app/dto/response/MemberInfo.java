package com.dingring.app.dto.response;

import lombok.Builder;
import lombok.Data;

/**
 * 群成员信息（GroupDetail.members 元素）。
 */
@Data
@Builder
public class MemberInfo {

    private Long id;
    /** USER / AGENT */
    private String type;
    /** OWNER / MEMBER / EXPERT */
    private String role;
    private String name;
    private String avatar;
}
