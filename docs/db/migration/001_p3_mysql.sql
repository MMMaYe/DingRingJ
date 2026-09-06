-- P3 RAG MySQL expand migration. Execute explicitly after taking a backup.

ALTER TABLE kb_file
    ADD COLUMN doc_content_hash CHAR(64) NULL COMMENT '原始文件 SHA-256' AFTER error_msg,
    ADD COLUMN current_version INT NOT NULL DEFAULT 0 COMMENT '当前生效文档版本' AFTER doc_content_hash,
    ADD COLUMN active_run_id VARCHAR(64) NULL COMMENT '当前活跃摄入 runId' AFTER current_version,
    ADD COLUMN cleaning_status VARCHAR(16) NULL COMMENT 'SKIPPED/CLEANED/FAILED' AFTER active_run_id,
    ADD UNIQUE KEY uk_kb_file_active_run (active_run_id);

CREATE TABLE kb_document_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_id BIGINT NOT NULL,
    document_version INT NOT NULL,
    document_title VARCHAR(255) NOT NULL,
    doc_content_hash CHAR(64) NOT NULL,
    raw_path VARCHAR(512) NOT NULL,
    clean_path VARCHAR(512) NULL,
    cleaning_model VARCHAR(128) NULL,
    cleaning_prompt_version VARCHAR(64) NULL,
    chunking_version VARCHAR(64) NULL,
    embedding_model VARCHAR(128) NULL,
    chunk_count INT NULL,
    active TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_kb_doc_version UNIQUE (file_id, document_version),
    INDEX idx_kb_doc_active (file_id, active)
);

CREATE TABLE kb_ingestion_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id VARCHAR(64) NOT NULL,
    file_id BIGINT NOT NULL,
    doc_content_hash CHAR(64) NOT NULL,
    document_version INT NOT NULL,
    executor VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt INT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    submitted_at DATETIME NULL,
    active_slot BIGINT NULL COMMENT '活跃时等于 file_id，终态置 NULL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_kb_ingestion_run_id UNIQUE (run_id),
    CONSTRAINT uk_kb_ingestion_active_slot UNIQUE (active_slot),
    INDEX idx_kb_ingestion_file (file_id, created_at),
    INDEX idx_kb_ingestion_status (status, updated_at)
);

INSERT INTO kb_document_version
(file_id, document_version, document_title, doc_content_hash, raw_path, clean_path,
 chunking_version, chunk_count, active, created_at, updated_at)
SELECT id, 1, name, COALESCE(doc_content_hash, REPEAT('0', 64)), path, NULL,
       'legacy-fixed-v1', chunk_count, IF(status = 'READY', 1, 0), create_time, update_time
FROM kb_file
WHERE current_version = 0;

UPDATE kb_file
SET current_version = 1,
    cleaning_status = 'SKIPPED'
WHERE current_version = 0;
