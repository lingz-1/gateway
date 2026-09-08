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

ALTER TABLE lingshu_budget_outbox
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ;

ALTER TABLE lingshu_budget_outbox
    ADD COLUMN IF NOT EXISTS claim_token UUID;

CREATE INDEX IF NOT EXISTS idx_lingshu_budget_outbox_unpublished
    ON lingshu_budget_outbox (published_at, event_id);

CREATE TABLE IF NOT EXISTS lingshu_budget_event_consumption (
    event_id BIGINT PRIMARY KEY,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
