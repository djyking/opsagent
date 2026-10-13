-- One row per assistant answer, including all outbound continuations and retries.
-- charged_tokens is conservative when usage_known=0; it is not a claimed provider bill.
USE ops_rag;
CREATE TABLE IF NOT EXISTS ai_operation_budget_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(128),
    user_id BIGINT NOT NULL,
    question_hash CHAR(64) NOT NULL,
    budget_limit INT NOT NULL,
    charged_tokens INT NOT NULL,
    usage_known TINYINT(1) NOT NULL,
    request_attempts INT NOT NULL,
    create_time DATETIME NOT NULL,
    INDEX idx_operation_budget_user_time (user_id, create_time)
);
