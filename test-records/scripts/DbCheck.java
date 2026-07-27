import java.sql.*;

/**
 * 联调步骤 02：检查 MySQL 表结构与种子数据。
 * 运行：java -cp ~/.m2/repository/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar DbCheck.java
 */
public class DbCheck {
    static final String URL = "jdbc:mysql://101.33.227.80:3306/ring_chat?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    static final String USER = "root";
    static final String PASS = "MySQL@123456";
    static final String[] EXPECTED = {"user", "agent", "chat_group", "topic", "message", "knowledge_card"};

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            System.out.println("[OK] 数据库连接成功: " + conn.getMetaData().getURL());
            // 1. 现有表清单
            var tables = new java.util.ArrayList<String>();
            try (ResultSet rs = conn.getMetaData().getTables("ring_chat", null, "%", new String[]{"TABLE"})) {
                while (rs.next()) tables.add(rs.getString("TABLE_NAME").toLowerCase());
            }
            System.out.println("现有表: " + tables);
            for (String t : EXPECTED) {
                System.out.println("  表 " + t + " -> " + (tables.contains(t) ? "存在" : "!! 缺失 !!"));
            }
            // 2. 各表行数 + 关键种子数据
            try (Statement st = conn.createStatement()) {
                for (String t : EXPECTED) {
                    if (!tables.contains(t)) continue;
                    try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM `" + t + "`")) {
                        rs.next();
                        System.out.println("  行数 " + t + " = " + rs.getLong(1));
                    }
                }
                if (tables.contains("user")) {
                    try (ResultSet rs = st.executeQuery("SELECT id, name FROM `user` WHERE id = 1")) {
                        System.out.println(rs.next()
                                ? "[OK] 种子用户 id=1 存在: " + rs.getString("name")
                                : "[WARN] 种子用户 id=1 不存在（单用户模式必需）");
                    }
                }
                if (tables.contains("agent")) {
                    try (ResultSet rs = st.executeQuery("SELECT id, name, base_url, model_name FROM agent ORDER BY id")) {
                        System.out.println("现有 Agent:");
                        while (rs.next()) {
                            System.out.println("  id=" + rs.getLong(1) + " name=" + rs.getString(2)
                                    + " baseUrl=" + rs.getString(3) + " model=" + rs.getString(4));
                        }
                    }
                }
            }
        }
    }
}
