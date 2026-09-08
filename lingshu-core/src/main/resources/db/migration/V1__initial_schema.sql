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
    ON lingshu_semantic_cache_2560 USING hnsw (embedding halfvec_cosine_ops);

CREATE TABLE IF NOT EXISTS lingshu_budget_ledger (
    reservation_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    units BIGINT NOT NULL CHECK (units > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'RELEASED', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_lingshu_budget_ledger_tenant_status
    ON lingshu_budget_ledger (tenant_id, status, expires_at);

CREATE TABLE IF NOT EXISTS lingshu_budget_outbox (
    event_id BIGSERIAL PRIMARY KEY,
    reservation_id VARCHAR(128) NOT NULL REFERENCES lingshu_budget_ledger(reservation_id),
    event_type VARCHAR(16) NOT NULL CHECK (event_type IN ('CONFIRMED', 'RELEASED', 'EXPIRED')),
    tenant_id VARCHAR(128) NOT NULL,
    units BIGINT NOT NULL CHECK (units > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    claim_token UUID,
    UNIQUE (reservation_id, event_type)
);

CREATE INDEX IF NOT EXISTS idx_lingshu_budget_outbox_unpublished
    ON lingshu_budget_outbox (published_at, event_id);

CREATE TABLE IF NOT EXISTS lingshu_budget_event_consumption (
    event_id BIGINT PRIMARY KEY,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lingshu_virtual_billing_accounts (
    tenant_id VARCHAR(128) PRIMARY KEY,
    balance_cny NUMERIC(20, 9) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lingshu_virtual_billing_usage (
    trace_id VARCHAR(128) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    provider VARCHAR(64),
    model VARCHAR(128),
    cache_status VARCHAR(16),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    input_tokens BIGINT NOT NULL CHECK (input_tokens >= 0),
    output_tokens BIGINT NOT NULL CHECK (output_tokens >= 0),
    cost_cny NUMERIC(20, 9) NOT NULL CHECK (cost_cny >= 0),
    remaining_balance_cny NUMERIC(20, 9) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lingshu_virtual_billing_usage_tenant_time
    ON lingshu_virtual_billing_usage (tenant_id, created_at DESC);
