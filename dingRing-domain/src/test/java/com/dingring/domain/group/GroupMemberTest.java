package com.dingring.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GroupMember} 单元测试：type/role 判定方法。
 * <p>语义：用 type 区分 USER/AGENT，role 区分 OWNER/MEMBER。
 */
@DisplayName("GroupMember 群成员")
class GroupMemberTest {

    @Test
    @DisplayName("type=AGENT 时 isAgent 为 true")
    void agentTypeShouldBeAgent() {
        GroupMember m = new GroupMember(10L, MemberType.AGENT, MemberRole.MEMBER);

        assertThat(m.isAgent()).isTrue();
    }

    @Test
    @DisplayName("type=USER 时 isAgent 为 false")
    void userTypeShouldNotBeAgent() {
        GroupMember m = new GroupMember(1L, MemberType.USER, MemberRole.OWNER);

        assertThat(m.isAgent()).isFalse();
    }

    @Test
    @DisplayName("群主本质是 USER + OWNER 组合")
    void ownerIsUserWithOwnerRole() {
        GroupMember owner = new GroupMember(1L, MemberType.USER, MemberRole.OWNER);

        assertThat(owner.isAgent()).isFalse();
        assertThat(owner.getRole()).isEqualTo(MemberRole.OWNER);
    }
}
