-- =====================================================================
-- DingRing AI 群聊学习系统 DDL（H2 MODE=MySQL / MySQL 8 兼容，可直接在 MySQL 8 执行初始化）
-- 注意：生产环境表结构通过 MySQL MCP 手动管理，此文件作为新环境初始化与 H2 演示模式的参考。
-- 索引统一用建表语句内联 INDEX 写法（MySQL/H2 双兼容）；MySQL 8 不支持 CREATE INDEX IF NOT EXISTS。
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
    CONSTRAINT uk_topic_group_title UNIQUE (chat_group_id, title),
    INDEX idx_topic_group_status (chat_group_id, status)
);

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
    -- 消息标签(tag)与观点摘要(viewpoint)无独立列，统一存于 feature JSON：{"tag":"KEY|NOISE|VIEWPOINT","viewpoint":"摘要"}，
    -- 由 GroupMessage#getTag()/getViewpoint() 便捷读写；查询观点列表见 MessageMapper.findViewpointsByTopicId（LIKE 匹配）
    feature             TEXT        NULL COMMENT '扩展字段(JSON)：tag/viewpoint 标签亦存于此',
    create_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_message_group (chat_group_id, id),
    INDEX idx_message_topic (topic_id, id)
);

-- 知识卡片表（结论异步提取的 Q&A）
CREATE TABLE IF NOT EXISTS knowledge_card (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    topic_id    BIGINT       NOT NULL COMMENT '来源主题 ID',
    question    TEXT         NOT NULL COMMENT '问题',
    answer      TEXT         NOT NULL COMMENT '答案',
    category    VARCHAR(64)  NULL COMMENT '分类',
    feature     TEXT         NULL COMMENT '扩展字段(JSON)',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_card_topic (topic_id),
    INDEX idx_card_category (category)
);

-- 知识库表（群与库的关联由 chat_group.knowledge_base_config 表达；文件切片内容存 PostgreSQL 向量库）
CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(128) NOT NULL COMMENT '库名',
    description VARCHAR(200) NULL COMMENT '用途说明',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/PROCESSING/FAILED',
    feature     TEXT         NULL COMMENT '扩展字段(JSON)',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT uk_kb_name UNIQUE (name)
);

-- 知识库文件表（只存元信息，切片内容存 PostgreSQL kb_store 向量表）
CREATE TABLE IF NOT EXISTS kb_file (
    id                BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id BIGINT       NOT NULL COMMENT '所属知识库 ID',
    name              VARCHAR(255) NOT NULL COMMENT '文件名',
    path              VARCHAR(512) NOT NULL COMMENT '本地存储路径',
    file_type         VARCHAR(16)  NULL COMMENT '文件类型: PDF/MARKDOWN/TXT',
    file_size         BIGINT       NULL COMMENT '文件大小(字节)',
    status            VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED' COMMENT '状态: UPLOADED/CHUNKED/EMBEDDED/READY/FAILED',
    chunk_count       INT          NULL COMMENT '切片数',
    error_msg         TEXT         NULL COMMENT '失败原因(status=FAILED 时)',
    doc_content_hash  CHAR(64)     NULL COMMENT '原始文件 SHA-256',
    current_version   INT          NOT NULL DEFAULT 0 COMMENT '当前生效文档版本',
    active_run_id     VARCHAR(64)  NULL COMMENT '当前活跃摄入 runId',
    cleaning_status   VARCHAR(16)  NULL COMMENT 'SKIPPED/CLEANED/FAILED',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_kb_id (knowledge_base_id),
    UNIQUE KEY uk_kb_file_active_run (active_run_id)
);

CREATE TABLE IF NOT EXISTS kb_document_version (
    id                      BIGINT       PRIMARY KEY AUTO_INCREMENT,
    file_id                 BIGINT       NOT NULL,
    document_version        INT          NOT NULL,
    document_title          VARCHAR(255) NOT NULL,
    doc_content_hash        CHAR(64)     NOT NULL,
    raw_path                VARCHAR(512) NOT NULL,
    clean_path              VARCHAR(512) NULL,
    cleaning_model          VARCHAR(128) NULL,
    cleaning_prompt_version VARCHAR(64)  NULL,
    chunking_version        VARCHAR(64)  NULL,
    embedding_model         VARCHAR(128) NULL,
    chunk_count             INT          NULL,
    active                  TINYINT(1)   NOT NULL DEFAULT 0,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_kb_doc_version UNIQUE (file_id, document_version),
    INDEX idx_kb_doc_active (file_id, active)
);

CREATE TABLE IF NOT EXISTS kb_ingestion_run (
    id                 BIGINT      PRIMARY KEY AUTO_INCREMENT,
    run_id             VARCHAR(64) NOT NULL,
    file_id            BIGINT      NOT NULL,
    doc_content_hash   CHAR(64)    NOT NULL,
    document_version   INT         NOT NULL,
    executor           VARCHAR(32) NOT NULL,
    status             VARCHAR(32) NOT NULL,
    attempt            INT         NOT NULL DEFAULT 0,
    error_message      TEXT        NULL,
    submitted_at       DATETIME    NULL,
    active_slot        BIGINT      NULL COMMENT '活跃时等于 file_id，终态置 NULL',
    created_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_kb_ingestion_run_id UNIQUE (run_id),
    CONSTRAINT uk_kb_ingestion_active_slot UNIQUE (active_slot),
    INDEX idx_kb_ingestion_file (file_id, created_at),
    INDEX idx_kb_ingestion_status (status, updated_at)
);

-- 用户画像表（跨群全局画像，版本化写回：每次提炼后标记旧记录为失效，插入新记录，保留历史轨迹）
CREATE TABLE IF NOT EXISTS user_profile (
    id           BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    user_id      BIGINT   NOT NULL COMMENT '用户 ID',
    profile_text TEXT     NULL COMMENT '画像要点（纯文本）',
    feature      TEXT     NULL COMMENT '扩展字段(JSON)',
    status       TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=有效, 0=失效（历史版本）',
    create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_status (user_id, status)
);

-- 话题级用户画像表（每次 TopicClosed 追加一条记录，多条记录=用户在该话题的进步轨迹）
-- 不加唯一约束：同一话题标题允许多条记录（不同 topic_id），按 user_id + topic_title 回溯历史
CREATE TABLE IF NOT EXISTS user_topic_profile (
    id                 BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    user_id            BIGINT       NOT NULL COMMENT '用户 ID',
    topic_id           BIGINT       NOT NULL COMMENT '话题 ID',
    group_id           BIGINT       NOT NULL COMMENT '群 ID',
    topic_title        VARCHAR(200) NULL COMMENT '话题标题（按标题回溯历史）',
    understanding_level VARCHAR(20) NULL COMMENT '理解程度: BEGINNER/INTERMEDIATE/ADVANCED',
    weak_points        TEXT         NULL COMMENT '薄弱点（具体到行为，非笼统评价）',
    strong_points      TEXT         NULL COMMENT '亮点',
    suggested_focus    TEXT         NULL COMMENT '建议提升方向（可操作的建议）',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_upt_user_title (user_id, topic_title, create_time)
);

-- 用户问卷答案表（画像主数据事实源，版本化写回：重填时标记旧记录失效，插入新记录 version+1）
CREATE TABLE IF NOT EXISTS user_questionnaire (
    id          BIGINT   PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    user_id     BIGINT   NOT NULL COMMENT '用户 ID',
    answers     TEXT     NOT NULL COMMENT '问卷答案(JSON, 题目key→枚举编码)',
    version     INT      NOT NULL DEFAULT 1 COMMENT '填写版本号(每次重填+1)',
    status      TINYINT(1) NOT NULL DEFAULT 1 COMMENT '1=有效, 0=失效（历史版本）',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_status (user_id, status)
);

-- SKILL 表（技能 = 工具组 + 附加系统提示词；技能经 REST CRUD 创建，scope=SCENE 绑定沉淀场景，uk_name 兜底）
CREATE TABLE IF NOT EXISTS skill (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    name          VARCHAR(64)  NOT NULL COMMENT '技能名称(唯一标识)',
    description   VARCHAR(255) NULL COMMENT '技能描述(注入提示词)',
    tool_names    VARCHAR(512) NULL COMMENT '工具集标识(逗号分隔)',
    system_prompt TEXT         NULL COMMENT '附加系统提示词',
    scope         VARCHAR(16)  NOT NULL DEFAULT 'GLOBAL' COMMENT '作用域: GLOBAL/AGENT/SCENE',
    agent_id      BIGINT       NULL COMMENT 'scope=AGENT 时绑定 Agent ID',
    scene_key     VARCHAR(32)  NULL COMMENT 'scope=SCENE 时绑定沉淀场景: conclude/card/topic-profile',
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/INACTIVE',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT uk_name UNIQUE (name),
    INDEX idx_agent (agent_id),
    INDEX idx_scene (scene_key)
);
