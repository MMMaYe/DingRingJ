package com.dingring.domain.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Group} 聚合根单元测试：成员分类查询方法（区分普通 Agent 与专家）。
 * <p>核心不变式：expertAgentId 仅返回 EXPERT 角色 Agent，memberAgentIds 排除专家。
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
    @DisplayName("memberAgentIds 普通成员 Agent")
    class MemberAgentIds {

        @Test
        @DisplayName("仅返回 AGENT + 非 EXPERT 的 id")
        void shouldReturnOnlyNonExpertAgents() {
            Group g = new Group();
            g.setGroupMember(List.of(
                    user(1L, MemberRole.OWNER),
                    agent(10L, MemberRole.MEMBER),
                    agent(11L, MemberRole.MEMBER),
                    agent(99L, MemberRole.EXPERT)
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
        @DisplayName("仅有专家 Agent 时返回空列表")
        void onlyExpertShouldReturnEmpty() {
            Group g = new Group();
            g.setGroupMember(List.of(
                    user(1L, MemberRole.OWNER),
                    agent(99L, MemberRole.EXPERT)
            ));

            assertThat(g.memberAgentIds()).isEmpty();
        }
    }

    @Nested
    @DisplayName("expertAgentId 专家 Agent")
    class ExpertAgentId {

        @Test
        @DisplayName("返回 EXPERT 角色 Agent 的 id")
        void shouldReturnExpertAgentId() {
            Group g = new Group();
            g.setGroupMember(List.of(
                    agent(10L, MemberRole.MEMBER),
                    agent(99L, MemberRole.EXPERT)
            ));

            Optional<Long> expertId = g.expertAgentId();

            assertThat(expertId).hasValue(99L);
        }

        @Test
        @DisplayName("无专家时返回 empty")
        void noExpertShouldReturnEmpty() {
            Group g = new Group();
            g.setGroupMember(List.of(
                    user(1L, MemberRole.OWNER),
                    agent(10L, MemberRole.MEMBER)
            ));

            assertThat(g.expertAgentId()).isEmpty();
        }

        @Test
        @DisplayName("成员列表为 null 时返回 empty")
        void nullMembersShouldReturnEmpty() {
            Group g = new Group();
            g.setGroupMember(null);

            assertThat(g.expertAgentId()).isEmpty();
        }

        @Test
        @DisplayName("多个专家时返回第一个（防御边界，业务上不应出现）")
        void multipleExpertsShouldReturnFirst() {
            Group g = new Group();
            g.setGroupMember(List.of(
                    agent(98L, MemberRole.EXPERT),
                    agent(99L, MemberRole.EXPERT)
            ));

            assertThat(g.expertAgentId()).hasValue(98L);
        }
    }
}
