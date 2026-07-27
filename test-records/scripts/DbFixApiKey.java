import java.sql.*;

/**
 * 联调步骤 09：缺陷修复 —— agent.api_key 列 VARCHAR(255) 存不下 CloudBase 长 JWT（796 字符）。
 * 无损扩宽为 VARCHAR(1024)。
 * 运行：java -cp ~/.m2/repository/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar DbFixApiKey.java
 */
public class DbFixApiKey {
    static final String URL = "jdbc:mysql://101.33.227.80:3306/ring_chat?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "root", "MySQL@123456");
             Statement st = conn.createStatement()) {
            System.out.println("[修复前] api_key 列定义:");
            printColumn(st);
            st.executeUpdate("ALTER TABLE agent MODIFY COLUMN api_key VARCHAR(1024) NULL COMMENT 'LLM API Key'");
            System.out.println("[OK] ALTER TABLE agent MODIFY api_key VARCHAR(1024) 执行成功");
            System.out.println("[修复后] api_key 列定义:");
            printColumn(st);
        }
    }

    static void printColumn(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT COLUMN_TYPE FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA='ring_chat' AND TABLE_NAME='agent' AND COLUMN_NAME='api_key'")) {
            while (rs.next()) System.out.println("  api_key -> " + rs.getString(1));
        }
    }
}
