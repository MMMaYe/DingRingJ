# 群聊页面加载慢修复计划 — MessageAssembler N+1 查询消除

## 一、现状

### 问题描述
每次打开群聊页面 `http://localhost:8080/#/?groupId=10`，页面加载明显卡顿，用户体感等待 4-5 秒才能看到消息列表。

### 发现方式

通过 Chrome DevTools Performance 工具对页面加载进行了完整追踪，并逐个 API 测量响应时间：

| API 接口 | 响应时间 | 数据量 |
|----------|---------|--------|
| `GET /api/groups` | 167ms | 1 个群 |
| `GET /api/groups/10` | 204ms | 群详情 |
| `GET /api/groups/10/topics` | 346ms | 4 个主题 |
| **`GET /api/groups/10/messages?page=1&pageSize=200`** | **4030ms** | **111 条消息** |
| `GET /api/groups/10/messages?page=1&pageSize=50` | 2404ms | 50 条消息 |
| `GET /api/groups/10/messages?page=1&pageSize=10` | 518ms | 10 条消息 |

响应时间与消息条数成**线性关系**（约 36ms/条），这是 N+1 查询的经典特征。

通过 MySQL 查询确认：群 10 共 111 条消息（78 条 Agent、30 条 User、5 条有引用回复），数据库索引正常（`idx_message_group(chat_group_id, id)`、`idx_message_topic(topic_id, id)` 均存在）。

### 根因分析

瓶颈在 [MessageAssembler.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/service/MessageAssembler.java) 的 `toDtos()` 方法，它对每条消息逐条调用 `toDto()`，而 `toDto()` 内部会：

1. **`fillSender()`**（第49-63行）— 每条消息查一次发送者：
   - Agent 消息 → `agentRepository.findById()` → 1 次 SQL
   - User 消息 → `userRepository.findById()` → 1 次 SQL

2. **`fillReply()`**（第65-77行）— 有回复的消息额外查 2 次：
   - `messageRepository.findById()` 查被引用消息 → 1 次 SQL
   - `resolveSenderName()` 查被引用消息的发送者 → 1 次 SQL

**111 条消息的 SQL 查询次数**：
- 108 条查发送者（78 Agent + 30 User）= 108 次
- 5 条有回复 × 2 = 10 次
- 主查询 + count = 2 次
- **总计约 120 次 SQL**，串行执行累加到 4 秒+

调用链路：
```
前端 Chat/index.tsx:188  GET /api/groups/10/messages?pageSize=200
  → TopicController.groupMessages():40
    → TopicAppService.groupMessages():56
      → messageRepository.findByGroupId()     // 1 次 SQL
      → messageAssembler.toDtos()             // 118 次 SQL ← 瓶颈
```

## 二、为什么要这样调整

N+1 查询是 ORM 层最常见的性能反模式。当前实现中，`AgentRepository` 已有批量方法 `findByIds(List<Long>)`，但 `MessageAssembler` 没有使用它，而是逐条 `findById`。

**调整思路**：将 `toDtos()` 改为批量装配 —— 一次性收集所有需要的 senderId 和 replyToMessageId，分别做一次批量查询，在内存中用 Map 组装。120 次 SQL 降为 4 次（主查询 + count + 批量查 Agent + 批量查 User + 批量查被引用消息）。

**约束**：
- `toDto()` 单条方法保留不变（被 ChatOrchestrator/DiscussionEngine 用于实时推送单条消息，不存在 N+1 问题）
- `resolveSenderName()` 保留不变（被 ModeratorService/ProfileEventHandler/DiscussionEngine/ChatOrchestrator 多处引用）
- `UserRepository` 目前缺少批量方法 `findByIds()`，需要新增

## 三、具体改动措施

### 改动 1：UserRepository 新增批量查询接口

**文件**：[dingRing-domain/.../user/UserRepository.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-domain/src/main/java/com/dingring/domain/user/UserRepository.java)

新增方法：
```java
List<User> findByIds(List<Long> ids);
```

**文件**：[dingRing-infrastructure/.../persistence/mapper/UserMapper.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/mapper/UserMapper.java)

新增方法：
```java
List<User> findByIds(@Param("ids") List<Long> ids);
```

**文件**：[dingRing-infrastructure/.../persistence/repository/UserRepositoryImpl.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/repository/UserRepositoryImpl.java)

实现 `findByIds()`，空列表返回 `List.of()`（与 AgentRepositoryImpl 一致）。

**文件**：[dingRing-infrastructure/.../resources/mapper/UserMapper.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/resources/mapper/UserMapper.xml)

新增 SQL（参照 AgentMapper.xml 的 findByIds 写法）：
```xml
<select id="findByIds" resultMap="userMap">
    SELECT <include refid="columns"/>
    FROM `user`
    WHERE id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</select>
```

### 改动 2：MessageRepository 新增批量查询接口

**文件**：[dingRing-domain/.../group/MessageRepository.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-domain/src/main/java/com/dingring/domain/group/MessageRepository.java)

新增方法：
```java
List<GroupMessage> findByIds(List<Long> ids);
```

**文件**：[dingRing-infrastructure/.../persistence/mapper/MessageMapper.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/mapper/MessageMapper.java)

新增方法：
```java
List<GroupMessage> findByIds(@Param("ids") List<Long> ids);
```

**文件**：[dingRing-infrastructure/.../persistence/repository/MessageRepositoryImpl.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/java/com/dingring/infrastructure/persistence/repository/MessageRepositoryImpl.java)

实现 `findByIds()`，空列表返回 `List.of()`。

**文件**：[dingRing-infrastructure/.../resources/mapper/MessageMapper.xml](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-infrastructure/src/main/resources/mapper/MessageMapper.xml)

新增 SQL：
```xml
<select id="findByIds" resultMap="messageMap">
    SELECT <include refid="columns"/>
    FROM message
    WHERE id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</select>
```

### 改动 3：MessageAssembler 新增批量装配方法 `toBatchDtos()`

**文件**：[dingRing-app/.../service/MessageAssembler.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/service/MessageAssembler.java)

新增 `toBatchDtos(List<GroupMessage>)` 方法，逻辑：
1. 收集所有 Agent senderId → `agentRepository.findByIds()` 一次查出 → `Map<Long, Agent>`
2. 收集所有 User senderId → `userRepository.findByIds()` 一次查出 → `Map<Long, User>`
3. 收集所有 replyToMessageId → `messageRepository.findByIds()` 一次查出 → `Map<Long, GroupMessage>`
4. 对被引用消息再收集 senderId，复用步骤 1/2 的 Map 补齐 replyToSenderName
5. 遍历消息列表，用 Map 填充字段，生成 `List<MessageDTO>`

关键实现细节：
- senderId 为 null 的 SYSTEM 消息跳过查询，直接设置 senderName="系统"
- Agent/User 查询结果可能为空（成员被删除），保持 name/avatar 为 null（与现有 `toDto()` 行为一致）
- 被引用消息不存在时，replyToSenderName/replyToContent 保持 null
- 被引用消息内容超过 50 字符截断加 "…"（复用 `REPLY_PREVIEW_LEN` 常量）

保留 `toDto()` 和 `toDtos()` 不变（向后兼容，单条场景无性能问题）。

### 改动 4：TopicAppService 改用批量装配

**文件**：[dingRing-app/.../service/TopicAppService.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/main/java/com/dingring/app/service/TopicAppService.java)

将 `groupMessages()` 和 `messages()` 中的 `messageAssembler.toDtos()` 替换为 `messageAssembler.toBatchDtos()`：
```java
// 第50行
List<MessageDTO> items = messageAssembler.toBatchDtos(
        messageRepository.findByTopicId(topicId, (page - 1) * pageSize, pageSize));

// 第58行
List<MessageDTO> items = messageAssembler.toBatchDtos(
        messageRepository.findByGroupId(groupId, (page - 1) * pageSize, pageSize));
```

### 改动 5：更新单元测试

**文件**：[dingRing-app/.../test/service/MessageAssemblerTest.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/test/java/com/dingring/app/service/MessageAssemblerTest.java)

新增 `toBatchDtos` 测试用例：
- 混合 Agent/User/System 消息批量装配，验证 `findByIds` 被调用一次（而非 N 次）
- 有引用回复的消息批量装配
- 被引用消息不存在时不报错
- 空列表输入返回空列表

**文件**：[dingRing-app/.../test/service/TopicAppServiceTest.java](file:///Users/Zhuanz/IdeaProjects/DingRingJ/dingRing-app/src/test/java/com/dingring/app/service/TopicAppServiceTest.java)

将 mock 从 `toDtos(any())` 改为 `toBatchDtos(any())`。

## 四、预期效果

| 指标 | 改动前 | 改动后（预期） |
|------|--------|--------------|
| SQL 查询次数（111条消息） | ~120 次 | 4-5 次 |
| `/messages?pageSize=200` 响应时间 | 4030ms | < 300ms |
| `/messages?pageSize=50` 响应时间 | 2404ms | < 200ms |

## 五、验证步骤

1. 运行单元测试：`mvn test -pl dingRing-app -Dtest=MessageAssemblerTest,TopicAppServiceTest`
2. 运行全量测试：`mvn test`
3. 通过 Chrome DevTools 重新测量 `/api/groups/10/messages?pageSize=200` 响应时间，确认从 4s 降至 < 300ms
4. 测量完整页面加载 LCP，确认从 806ms 降至 < 300ms
