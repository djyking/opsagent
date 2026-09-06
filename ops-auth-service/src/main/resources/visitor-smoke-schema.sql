CREATE TABLE IF NOT EXISTS visitor_lease (
    user_id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_visitor_expiry(expires_at)
);
