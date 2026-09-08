CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS lingshu_semantic_cache_2560 (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    scope_key CHAR(64) NOT NULL,
    model VARCHAR(255) NOT NULL,
    prompt_version VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    request_text TEXT NOT NULL,
    embedding halfvec(2560) NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, scope_key, request_hash)
);

CREATE INDEX IF NOT EXISTS idx_lingshu_semantic_cache_2560_lookup
    ON lingshu_semantic_cache_2560 (tenant_id, scope_key, expires_at);

CREATE INDEX IF NOT EXISTS idx_lingshu_semantic_cache_2560_embedding_hnsw
    ON lingshu_semantic_cache_2560
    USING hnsw (embedding halfvec_cosine_ops);

INSERT INTO lingshu_semantic_cache_2560 (
    tenant_id, scope_key, model, prompt_version, request_hash,
    request_text, embedding, response_json, created_at, expires_at
)
SELECT tenant_id, scope_key, model, prompt_version, request_hash,
       request_text, embedding::halfvec, response_json, created_at, expires_at
FROM lingshu_semantic_cache
WHERE vector_dims(embedding) = 2560
ON CONFLICT (tenant_id, scope_key, request_hash) DO NOTHING;
