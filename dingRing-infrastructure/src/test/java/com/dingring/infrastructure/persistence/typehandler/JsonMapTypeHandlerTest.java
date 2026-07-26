package com.dingring.infrastructure.persistence.typehandler;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link JsonMapTypeHandler} 单元测试。
 * <p>验证 Map&lt;String,Object&gt; 与 JSON 字符串的双向转换，覆盖嵌套对象与边界场景。
 */
@DisplayName("JsonMapTypeHandler JSON 转换")
class JsonMapTypeHandlerTest {

    private final JsonMapTypeHandler handler = new JsonMapTypeHandler();

    @Test
    @DisplayName("setNonNullParameter 将 Map 序列化为 JSON 写入 PreparedStatement")
    void shouldSerializeMapToJsonParameter() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        // 用 LinkedHashMap 保证序列化顺序稳定
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("temperature", 0.7);
        feature.put("maxTokens", 4096);
        feature.put("enabled", true);

        handler.setNonNullParameter(ps, 1, feature, JdbcType.VARCHAR);

        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ps).setString(eq(1), captor.capture());
        String json = captor.getValue();
        assertThat(json).contains("\"temperature\":0.7")
                .contains("\"maxTokens\":4096")
                .contains("\"enabled\":true");
    }

    @Test
    @DisplayName("getNullableResult 将 JSON 反序列化为 Map")
    void shouldDeserializeJsonToMap() throws SQLException {
        String json = "{\"temperature\":0.5,\"maxTokens\":2048}";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("feature")).thenReturn(json);

        Map<String, Object> result = handler.getNullableResult(rs, "feature");

        assertThat(result)
                .containsEntry("temperature", 0.5)
                .containsEntry("maxTokens", 2048);
    }

    @Test
    @DisplayName("JSON null 时返回 null")
    void nullJsonShouldReturnNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("feature")).thenReturn(null);

        assertThat(handler.getNullableResult(rs, "feature")).isNull();
    }

    @Test
    @DisplayName("JSON 空串时返回 null")
    void blankJsonShouldReturnNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("feature")).thenReturn("");

        assertThat(handler.getNullableResult(rs, "feature")).isNull();
    }

    @Test
    @DisplayName("JSON 含嵌套对象时反序列化为 Map 内的 LinkedHashMap")
    void nestedObjectShouldBeLinkedHashMap() throws SQLException {
        String json = "{\"advanced\":{\"nested\":42}}";
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("feature")).thenReturn(json);

        Map<String, Object> result = handler.getNullableResult(rs, "feature");

        assertThat(result).containsKey("advanced");
        assertThat(result.get("advanced")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) result.get("advanced");
        assertThat(nested).containsEntry("nested", 42);
    }

    @Test
    @DisplayName("JSON 格式错误时抛 SQLException")
    void malformedJsonShouldThrowSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("feature")).thenReturn("not a valid json {");

        org.junit.jupiter.api.Assertions.assertThrows(SQLException.class,
                () -> handler.getNullableResult(rs, "feature"));
    }
}
