CREATE TABLE IF NOT EXISTS observability_recovery_watch (
    ticket_id BIGINT NOT NULL PRIMARY KEY,
    ci_code VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    result_at DATETIME(3) NOT NULL,
    actor_id BIGINT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
);
CREATE TABLE IF NOT EXISTS observability_recovery_sample (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    ci_code VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    result_at DATETIME(3) NOT NULL,
    checked_at DATETIME(3) NOT NULL,
    result VARCHAR(16) NOT NULL,
    evidence_json JSON NOT NULL,
    KEY idx_recovery_target (ticket_id,result_at,checked_at)
);
