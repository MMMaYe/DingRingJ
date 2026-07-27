import java.sql.*;

/**
 * 去专家 Agent 重构 —— 数据迁移：chat_group.group_member JSON 中历史 "role":"EXPERT" 改为 "role":"MEMBER"。
 * MemberRole 枚举已删除 EXPERT，不迁移会导致群成员反序列化失败。
 * 运行：java -cp ~/.m2/repository/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar DbMigrateExpertRole.java
 */
public class DbMigrateExpertRole {
    static final String URL = "jdbc:mysql://101.33.227.80:3306/ring_chat?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, "root", "MySQL@123456");
             Statement st = conn.createStatement()) {
            System.out.println("[迁移前] 含 EXPERT 的群:");
            printExpertGroups(st);
            int updated = st.executeUpdate(
                    "UPDATE chat_group SET group_member = REPLACE(group_member, '\"role\":\"EXPERT\"', '\"role\":\"MEMBER\"') " +
                    "WHERE group_member LIKE '%\"role\":\"EXPERT\"%'");
            System.out.println("[OK] 已迁移 " + updated + " 个群");
            System.out.println("[迁移后] 含 EXPERT 的群:");
            printExpertGroups(st);
        }
    }

    static void printExpertGroups(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT id, name, group_member FROM chat_group WHERE group_member LIKE '%\"role\":\"EXPERT\"%'")) {
            boolean any = false;
            while (rs.next()) {
                any = true;
                System.out.println("  id=" + rs.getLong(1) + " name=" + rs.getString(2) + " members=" + rs.getString(3));
            }
            if (!any) System.out.println("  （无）");
        }
    }
}
