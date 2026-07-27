package com.dingring.domain.group;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群成员（chat_group.group_member JSON 数组元素）。
 *
 * <pre>{"id": 10, "type": "AGENT", "role": "MEMBER"}</pre>
 * <p>用 type 区分 USER / AGENT（两表 id 可能重复），role 区分群主/成员。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupMember {

    private Long id;
    private MemberType type;
    private MemberRole role;

    public boolean isAgent() {
        return type == MemberType.AGENT;
    }
}
