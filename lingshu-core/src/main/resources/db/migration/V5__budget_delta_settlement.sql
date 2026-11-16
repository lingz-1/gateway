ALTER TABLE lingshu_budget_ledger
    ADD COLUMN IF NOT EXISTS actual_units BIGINT;

ALTER TABLE lingshu_budget_ledger
    ADD COLUMN IF NOT EXISTS released_units BIGINT;

ALTER TABLE lingshu_budget_ledger
    ADD COLUMN IF NOT EXISTS usage_source VARCHAR(32);

ALTER TABLE lingshu_budget_ledger
    DROP CONSTRAINT IF EXISTS chk_lingshu_budget_ledger_actual_units;

ALTER TABLE lingshu_budget_ledger
    ADD CONSTRAINT chk_lingshu_budget_ledger_actual_units
    CHECK (actual_units IS NULL OR actual_units >= 0);

ALTER TABLE lingshu_budget_ledger
    DROP CONSTRAINT IF EXISTS chk_lingshu_budget_ledger_usage_source;

ALTER TABLE lingshu_budget_ledger
    ADD CONSTRAINT chk_lingshu_budget_ledger_usage_source
    CHECK (usage_source IS NULL OR usage_source IN (
        'PROVIDER_REPORTED', 'ESTIMATED', 'MIXED', 'UNKNOWN'
    ));

ALTER TABLE lingshu_budget_outbox
    ADD COLUMN IF NOT EXISTS usage_source VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE lingshu_budget_outbox
    DROP CONSTRAINT IF EXISTS lingshu_budget_outbox_units_check;

ALTER TABLE lingshu_budget_outbox
    ADD CONSTRAINT lingshu_budget_outbox_units_check CHECK (units >= 0);

ALTER TABLE lingshu_budget_outbox
    DROP CONSTRAINT IF EXISTS chk_lingshu_budget_outbox_usage_source;

ALTER TABLE lingshu_budget_outbox
    ADD CONSTRAINT chk_lingshu_budget_outbox_usage_source
    CHECK (usage_source IN ('PROVIDER_REPORTED', 'ESTIMATED', 'MIXED', 'UNKNOWN'));
