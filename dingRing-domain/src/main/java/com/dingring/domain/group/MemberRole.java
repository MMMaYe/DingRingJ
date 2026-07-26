package com.dingring.domain.group;

/**
 * 群成员角色。专家（EXPERT）是群里的"第五个人"，本质是特殊角色的 Agent，
 * 不参与普通调度，仅在讨论结束时生成结论。
 */
public enum MemberRole {
    OWNER,
    MEMBER,
    EXPERT
}
