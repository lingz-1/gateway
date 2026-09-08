CREATE TABLE IF NOT EXISTS lingshu_tenant_policies (
    tenant_id VARCHAR(128) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    allowed_models TEXT[] NOT NULL DEFAULT '{}',
    exact_cache_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    semantic_cache_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    requests_per_minute INTEGER NOT NULL DEFAULT 0 CHECK (requests_per_minute >= 0),
    max_concurrent_requests INTEGER NOT NULL DEFAULT 0 CHECK (max_concurrent_requests >= 0),
    input_price_usd_per_million NUMERIC(20, 9) NOT NULL CHECK (input_price_usd_per_million >= 0),
    output_price_usd_per_million NUMERIC(20, 9) NOT NULL CHECK (output_price_usd_per_million >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
