-- 修复历史演示数据在未声明 UTF-8 客户端字符集时产生的可逆乱码。
-- WHERE 条件只命中典型 UTF-8/单字节字符集错解码标记，可重复执行。

SET NAMES utf8mb4;

USE ops_auth;
UPDATE sys_user
SET display_name=CONVERT(CAST(CONVERT(display_name USING latin1) AS BINARY) USING utf8mb4)
WHERE display_name REGEXP '[ÃÂæçåèé]';
UPDATE sys_role
SET name=CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4)
WHERE name REGEXP '[ÃÂæçåèé]';
UPDATE sys_permission
SET name=CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4)
WHERE name REGEXP '[ÃÂæçåèé]';

USE ops_ticket;
UPDATE ticket
SET title=CONVERT(CAST(CONVERT(title USING latin1) AS BINARY) USING utf8mb4)
WHERE title REGEXP '[ÃÂæçåèé]';
UPDATE ticket
SET description=CONVERT(CAST(CONVERT(description USING latin1) AS BINARY) USING utf8mb4)
WHERE description REGEXP '[ÃÂæçåèé]';
UPDATE ticket_comment
SET content=CONVERT(CAST(CONVERT(content USING latin1) AS BINARY) USING utf8mb4)
WHERE content REGEXP '[ÃÂæçåèé]';
UPDATE ticket_history
SET remark=CONVERT(CAST(CONVERT(remark USING latin1) AS BINARY) USING utf8mb4)
WHERE remark REGEXP '[ÃÂæçåèé]';

USE ops_knowledge;
UPDATE knowledge_base
SET name=CONVERT(CAST(CONVERT(name USING latin1) AS BINARY) USING utf8mb4)
WHERE name REGEXP '[ÃÂæçåèé]';
UPDATE knowledge_base
SET description=CONVERT(CAST(CONVERT(description USING latin1) AS BINARY) USING utf8mb4)
WHERE description REGEXP '[ÃÂæçåèé]';
UPDATE knowledge_document
SET original_name=CONVERT(CAST(CONVERT(original_name USING latin1) AS BINARY) USING utf8mb4)
WHERE original_name REGEXP '[ÃÂæçåèé]';
UPDATE knowledge_chunk
SET content=CONVERT(CAST(CONVERT(content USING latin1) AS BINARY) USING utf8mb4)
WHERE content REGEXP '[ÃÂæçåèé]';

USE ops_platform;
UPDATE system_announcement
SET title=CONVERT(CAST(CONVERT(title USING latin1) AS BINARY) USING utf8mb4)
WHERE title REGEXP '[ÃÂæçåèé]';
UPDATE system_announcement
SET content=CONVERT(CAST(CONVERT(content USING latin1) AS BINARY) USING utf8mb4)
WHERE content REGEXP '[ÃÂæçåèé]';
UPDATE platform_config
SET description=CONVERT(CAST(CONVERT(description USING latin1) AS BINARY) USING utf8mb4)
WHERE description REGEXP '[ÃÂæçåèé]';

CREATE TABLE IF NOT EXISTS notification_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_key VARCHAR(64) NOT NULL,
    ticket_id BIGINT NOT NULL,
    receiver_id BIGINT,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNREAD',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(id),
    UNIQUE KEY uk_notification_source(source_key),
    KEY idx_notification_status_time(status,create_time),
    KEY idx_notification_receiver_time(receiver_id,create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO notification_record(
    source_key,ticket_id,receiver_id,title,content,status,create_time,update_time
)
SELECT
    CONCAT('audit-',id),
    CAST(biz_id AS UNSIGNED),
    user_id,
    CASE operation
        WHEN 'CREATE' THEN '新工单已创建'
        WHEN 'CLAIM' THEN '工单已被接收'
        WHEN 'PROCESSING' THEN '工单开始处理'
        WHEN 'WAITING_CONFIRM' THEN '工单等待业务确认'
        WHEN 'RESOLVED' THEN '工单已解决'
        WHEN 'CLOSED' THEN '工单已关闭'
        ELSE '工单状态已更新'
    END,
    CONCAT('工单 #',biz_id,' 已执行“',operation,'”操作，请关注后续状态。'),
    'UNREAD',
    create_time,
    create_time
FROM operation_audit
WHERE biz_type='TICKET' AND biz_id REGEXP '^[0-9]+$';
