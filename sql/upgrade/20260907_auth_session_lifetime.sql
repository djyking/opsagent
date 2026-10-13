-- Run once against ops_auth before starting the updated auth service.
-- Existing tokens keep their original create_time; lazy refresh applies the 2h/24h limits.
ALTER TABLE auth_refresh_token
  ADD COLUMN session_id CHAR(36) NULL,
  ADD COLUMN session_started_at DATETIME NULL,
  ADD COLUMN last_activity_at DATETIME NULL,
  ADD COLUMN absolute_expires_at DATETIME NULL,
  ADD KEY idx_refresh_session (session_id, revoked);
