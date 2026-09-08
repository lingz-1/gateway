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
