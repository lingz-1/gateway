ALTER TABLE lingshu_virtual_billing_usage
    ADD COLUMN IF NOT EXISTS usage_source VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE lingshu_virtual_billing_usage
    DROP CONSTRAINT IF EXISTS chk_lingshu_virtual_billing_usage_source;

ALTER TABLE lingshu_virtual_billing_usage
    ADD CONSTRAINT chk_lingshu_virtual_billing_usage_source
    CHECK (usage_source IN ('PROVIDER_REPORTED', 'ESTIMATED', 'MIXED', 'UNKNOWN'));
