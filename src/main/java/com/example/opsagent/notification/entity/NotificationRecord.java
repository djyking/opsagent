package com.example.opsagent.notification.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户通知记录持久化实体。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
@TableName("notification_record")
public class NotificationRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private String receiver;

    private String title;

    private String content;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
