package com.dingring.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Group} 聚合根单元测试：成员查询方法。
 * <p>核心不变式：memberAgentIds 返回全部 AGENT 成员（任意成员均可参与讨论与总结）。
 */
@DisplayName("Group 聚合根")
class GroupTest {

    private GroupMember user(Long id, MemberRole role) {
        return new GroupMember(id, MemberType.USER, role);
    }

    private GroupMember agent(Long id, MemberRole role) {
        return new GroupMember(id, MemberType.AGENT, role);
    }

    @Nested
    @DisplayName("memberAgentIds 成员 Agent")
    class MemberAgentIds {

        @Test
        @DisplayName("返回全部 AGENT 的 id，排除 USER")
        void shouldReturnAllAgents() {
            Group g = new Group();
            g.setGroupMember(List.of(
                    user(1L, MemberRole.OWNER),
                    agent(10L, MemberRole.MEMBER),
                    agent(11L, MemberRole.MEMBER)
            ));

            List<Long> ids = g.memberAgentIds();

            assertThat(ids).containsExactlyInAnyOrder(10L, 11L);
        }

        @Test
        @DisplayName("成员列表为 null 时返回空列表")
        void nullMembersShouldReturnEmpty() {
            Group g = new Group();
            g.setGroupMember(null);

            assertThat(g.memberAgentIds()).isEmpty();
        }

        @Test
        @DisplayName("仅有 USER 成员时返回空列表")
        void onlyUserShouldReturnEmpty() {
            Group g = new Group();
            g.setGroupMember(List.of(user(1L, MemberRole.OWNER)));

            assertThat(g.memberAgentIds()).isEmpty();
        }
    }
}
