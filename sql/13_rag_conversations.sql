USE ops_rag;

CREATE TABLE IF NOT EXISTS rag_conversation (
    id CHAR(36) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_conversation_owner (user_id, deleted, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rag_conversation_turn (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    question TEXT NOT NULL,
    answer LONGTEXT,
    result_json LONGTEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
    error_message VARCHAR(500),
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_turn_conversation (conversation_id, id),
    CONSTRAINT fk_turn_conversation FOREIGN KEY (conversation_id) REFERENCES rag_conversation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
