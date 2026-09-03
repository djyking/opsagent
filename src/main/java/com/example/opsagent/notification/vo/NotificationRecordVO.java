package com.example.opsagent.notification.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知记录响应模型。
 *
 * @author heyu
 * @since 2026/7/16
 */
@Data
public class NotificationRecordVO {

    private Long id;

    private Long ticketId;

    private String receiver;

    private String title;

    private String content;

    private String status;

    private LocalDateTime createTime;
}
