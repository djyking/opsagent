-- Additive migration; keeps all existing visitor IDs and historical ownership intact.
USE ops_auth;
CREATE TABLE IF NOT EXISTS visitor_experience (
    credential_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_visitor_experience_user(user_id),
    KEY idx_visitor_experience_expiry(expires_at)
);
