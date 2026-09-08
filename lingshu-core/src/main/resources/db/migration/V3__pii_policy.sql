ALTER TABLE lingshu_tenant_policies
    ADD COLUMN IF NOT EXISTS pii_redaction_enabled BOOLEAN NOT NULL DEFAULT TRUE;
