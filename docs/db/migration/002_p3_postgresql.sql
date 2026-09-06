-- P3 RAG PostgreSQL expand migration. Execute explicitly against the vector database.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS kb_store_content_trgm_idx
    ON kb_store USING gin (content gin_trgm_ops);

CREATE INDEX IF NOT EXISTS kb_store_kb_active_idx
    ON kb_store (((metadata->>'kbId')::bigint), ((metadata->>'active')::boolean));

CREATE INDEX IF NOT EXISTS kb_store_file_version_idx
    ON kb_store (((metadata->>'fileId')::bigint), ((metadata->>'documentVersion')::integer));

-- 注意：kb_store.metadata 列是 json 类型（Spring AI PgVectorStore 默认建表 DDL），
-- 无 jsonb 的 ? / || 操作符——谓词用 ->> IS NOT NULL，拼接走 ::jsonb 转换
CREATE UNIQUE INDEX IF NOT EXISTS kb_store_chunk_id_uidx
    ON kb_store ((metadata->>'chunkId'))
    WHERE metadata->>'chunkId' IS NOT NULL;

UPDATE kb_store
SET metadata = (metadata::jsonb
    || jsonb_build_object(
        'documentVersion', COALESCE((metadata->>'documentVersion')::integer, 1),
        'active', COALESCE((metadata->>'active')::boolean, true),
        'chunkingVersion', COALESCE(metadata->>'chunkingVersion', 'legacy-fixed-v1')))::json
WHERE NOT (metadata::jsonb ? 'documentVersion')
   OR NOT (metadata::jsonb ? 'active')
   OR NOT (metadata::jsonb ? 'chunkingVersion');
