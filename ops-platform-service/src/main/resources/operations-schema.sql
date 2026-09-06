CREATE TABLE IF NOT EXISTS operations_workflow_run (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    workflow_code VARCHAR(32) NOT NULL,
    title VARCHAR(100) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    status VARCHAR(24) NOT NULL,
    summary VARCHAR(1500) NOT NULL DEFAULT '',
    actor_id BIGINT NOT NULL,
    actor VARCHAR(80) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at TIMESTAMP(3) NULL
);
CREATE TABLE IF NOT EXISTS operations_workflow_step (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    step_sequence INT NOT NULL,
    title VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL,
    detail VARCHAR(2000) NOT NULL DEFAULT '',
    evidence VARCHAR(6000) NOT NULL DEFAULT '',
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at TIMESTAMP(3) NULL,
    UNIQUE KEY uk_operations_step(run_id, step_sequence)
);
CREATE TABLE IF NOT EXISTS operations_workflow_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    detail VARCHAR(2000) NOT NULL,
    actor VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
