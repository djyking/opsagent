-- Existing phase 1/2 bridge tables only. Fresh installs already include these columns.
-- Execute once during the reviewed migration window. Never run both fresh and upgrade scripts.
ALTER TABLE ops_harness_application ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ops_harness_grant ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
