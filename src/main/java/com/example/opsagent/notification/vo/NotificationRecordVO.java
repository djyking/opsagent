package com.example.opsagent.notification.vo;

import java.time.LocalDateTime;
import lombok.Data;

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
