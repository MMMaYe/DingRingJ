-- =====================================================================
-- DingRing AI 群聊学习系统 DDL（H2 MODE=MySQL / MySQL 8 兼容）
-- 注意：生产环境表结构通过 MySQL MCP 手动管理，此文件仅作为 H2 演示模式的参考。
-- =====================================================================

-- 用户表（单用户模式，种子数据固定 id=1）
CREATE TABLE IF NOT EXISTS `user` (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    name            VARCHAR(64)  NOT NULL COMMENT '用户名',
    profile_picture VARCHAR(512) NULL COMMENT '头像 URL',
    feature         TEXT         NULL COMMENT '扩展字段(JSON)',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- Agent 表（AI 成员：花名/人设/模型配置）
CREATE TABLE IF NOT EXISTS agent (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    name            VARCHAR(64)  NOT NULL COMMENT '花名',
    profile_picture VARCHAR(512) NULL COMMENT '头像 URL',
    description     VARCHAR(255) NULL COMMENT '一句话简介',
    base_url        VARCHAR(255) NULL COMMENT 'LLM API Base URL',
    api_key         VARCHAR(1024) NULL COMMENT 'LLM API Key（部分网关用长 JWT，需 >255）',
    model_name      VARCHAR(64)  NULL COMMENT '模型名',
    call_type       VARCHAR(32)  NOT NULL DEFAULT 'API' COMMENT '调用方式: API/CLI',
    system_prompt   TEXT         NULL COMMENT '人设提示词',
    feature         TEXT         NULL COMMENT '扩展字段(JSON)',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- 群表（成员快照存 JSON：[{memberId,memberType,memberRole}]）
CREATE TABLE IF NOT EXISTS chat_group (
    id                    BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    name                  VARCHAR(128) NOT NULL COMMENT '群名',
    owner_id              BIGINT       NOT NULL COMMENT '群主用户 ID',
    group_member          TEXT         NULL COMMENT '成员列表(JSON)',
    knowledge_base_config TEXT         NULL COMMENT '知识库配置(JSON)',
    feature               TEXT         NULL COMMENT '扩展字段(JSON)',
    deleted               TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常1已删',
    create_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

-- 主题表（一个群同时只有一个 IN_PROGRESS；version 乐观锁）
CREATE TABLE IF NOT EXISTS topic (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    chat_group_id BIGINT       NOT NULL COMMENT '所属群 ID',
    title         VARCHAR(255) NOT NULL COMMENT '主题标题',
    status        VARCHAR(32)  NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS/CONCLUDING/CLOSED/ARCHIVED',
    conclusion    TEXT         NULL COMMENT '总结者 STAR 结论',
    closed_at     DATETIME     NULL COMMENT '关闭时间',
    version       INT          NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    feature       TEXT         NULL COMMENT '扩展字段(JSON)',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT uk_topic_group_title UNIQUE (chat_group_id, title)
);

CREATE INDEX IF NOT EXISTS idx_topic_group_status ON topic (chat_group_id, status);

-- 消息表（用户/Agent/系统消息统一存储）
CREATE TABLE IF NOT EXISTS message (
    id                  BIGINT      PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    chat_group_id       BIGINT      NOT NULL COMMENT '所属群 ID',
    topic_id            BIGINT      NULL COMMENT '所属主题 ID（闲聊为空）',
    sender_id           BIGINT      NULL COMMENT '发送者 ID（系统消息为空）',
    sender_type         VARCHAR(16) NOT NULL COMMENT 'USER/AGENT/SYSTEM',
    message_type        VARCHAR(16) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/FILE/SYSTEM_NOTICE',
    content             TEXT        NOT NULL COMMENT '消息内容',
    reply_to_message_id BIGINT      NULL COMMENT '引用回复的消息 ID',
    feature             TEXT        NULL COMMENT '扩展字段(JSON)',
    create_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

CREATE INDEX IF NOT EXISTS idx_message_group ON message (chat_group_id, id);
CREATE INDEX IF NOT EXISTS idx_message_topic ON message (topic_id, id);

-- 知识卡片表（结论异步提取的 Q&A）
CREATE TABLE IF NOT EXISTS knowledge_card (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    topic_id    BIGINT       NOT NULL COMMENT '来源主题 ID',
    question    TEXT         NOT NULL COMMENT '问题',
    answer      TEXT         NOT NULL COMMENT '答案',
    category    VARCHAR(64)  NULL COMMENT '分类',
    feature     TEXT         NULL COMMENT '扩展字段(JSON)',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

CREATE INDEX IF NOT EXISTS idx_card_topic ON knowledge_card (topic_id);
CREATE INDEX IF NOT EXISTS idx_card_category ON knowledge_card (category);

-- 用户画像表（跨群全局画像，版本化写回：每次提炼后标记旧记录为失效，插入新记录，保留历史轨迹）
CREATE TABLE IF NOT EXISTS user_profile (
    id           BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    user_id      BIGINT   NOT NULL COMMENT '用户 ID',
    profile_text TEXT     NULL COMMENT '画像要点（纯文本）',
    feature      TEXT     NULL COMMENT '扩展字段(JSON)',
    status       TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=有效, 0=失效（历史版本）',
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_profile_user (user_id)
);
