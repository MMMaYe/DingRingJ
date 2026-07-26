package com.dingring.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GroupMember} 单元测试：type/role 判定方法。
 * <p>语义：用 type 区分 USER/AGENT，role 区分 OWNER/MEMBER/EXPERT。
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
    @DisplayName("role=EXPERT 时 isExpert 为 true")
    void expertRoleShouldBeExpert() {
        GroupMember m = new GroupMember(99L, MemberType.AGENT, MemberRole.EXPERT);

        assertThat(m.isExpert()).isTrue();
    }

    @Test
    @DisplayName("role=MEMBER 时 isExpert 为 false")
    void memberRoleShouldNotBeExpert() {
        GroupMember m = new GroupMember(11L, MemberType.AGENT, MemberRole.MEMBER);

        assertThat(m.isExpert()).isFalse();
    }

    @Test
    @DisplayName("专家本质是 AGENT + EXPERT 组合")
    void expertIsAgentWithExpertRole() {
        GroupMember expert = new GroupMember(99L, MemberType.AGENT, MemberRole.EXPERT);

        assertThat(expert.isAgent()).isTrue();
        assertThat(expert.isExpert()).isTrue();
    }
}
