-- Dedicated automation database, existing application account and no hardcoded secret.
CREATE DATABASE IF NOT EXISTS ops_ai CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON ops_ai.* TO 'opsagent_app'@'%';
