package com.dingring.infrastructure.persistence.typehandler;

import com.dingring.domain.group.GroupMember;
import com.dingring.domain.group.MemberRole;
import com.dingring.domain.group.MemberType;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupMemberListTypeHandler} 单元测试。
 * <p>验证 List&lt;GroupMember&gt; 与 JSON 字符串之间的双向转换，以及 null/空串等边界。
 */
@DisplayName("GroupMemberListTypeHandler JSON 转换")
class GroupMemberListTypeHandlerTest {

    private final GroupMemberListTypeHandler handler = new GroupMemberListTypeHandler();

    @Test
    @DisplayName("setNonNullParameter 将 List 序列化为 JSON 写入 PreparedStatement")
    void shouldSerializeListToJsonParameter() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        List<GroupMember> members = List.of(
                new GroupMember(1L, MemberType.USER, MemberRole.OWNER),
                new GroupMember(10L, MemberType.AGENT, MemberRole.MEMBER)
        );

        handler.setNonNullParameter(ps, 3, members, JdbcType.VARCHAR);

        // 验证写入的 JSON 包含关键字段
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(3), captor.capture());
        String json = captor.getValue();
        assertThat(json).contains("\"id\":1").contains("\"id\":10")
                .contains("\"type\":\"USER\"").contains("\"type\":\"AGENT\"")
                .contains("\"role\":\"OWNER\"").contains("\"role\":\"MEMBER\"");
    }

    @Test
    @DisplayName("getNullableResult 将 JSON 反序列化为 List")
    void shouldDeserializeJsonToList() throws SQLException {
        String json = "[{\"id\":1,\"type\":\"USER\",\"role\":\"OWNER\"}]";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("group_member")).thenReturn(json);

        List<GroupMember> result = handler.getNullableResult(rs, "group_member");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getType()).isEqualTo(MemberType.USER);
        assertThat(result.get(0).getRole()).isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("JSON null 时返回 null")
    void nullJsonShouldReturnNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("group_member")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "group_member")).isNull();
    }

    @Test
    @DisplayName("JSON 空串时返回 null")
    void blankJsonShouldReturnNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("group_member")).thenReturn("   ");

        assertThat(handler.getNullableResult(rs, "group_member")).isNull();
    }

    @Test
    @DisplayName("JSON 含未知字段时反序列化不抛异常（兼容派生属性 agent/expert）")
    void unknownFieldsShouldBeIgnored() throws SQLException {
        String json = "[{\"id\":1,\"type\":\"USER\",\"role\":\"OWNER\",\"agent\":false,\"expert\":false}]";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("group_member")).thenReturn(json);

        List<GroupMember> result = handler.getNullableResult(rs, "group_member");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("JSON 格式错误时抛 SQLException")
    void malformedJsonShouldThrowSQLException() throws SQLException {
        String json = "{not a valid json";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("group_member")).thenReturn(json);

        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> handler.getNullableResult(rs, "group_member"));
    }
}
