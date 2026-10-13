-- Existing observability_topology_layout rows remain the team default.
-- Personal layouts are isolated by authenticated actor and environment.
CREATE TABLE IF NOT EXISTS observability_personal_layout (
    environment VARCHAR(32) NOT NULL,
    actor_id BIGINT NOT NULL,
    positions_json MEDIUMTEXT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (environment, actor_id)
);
