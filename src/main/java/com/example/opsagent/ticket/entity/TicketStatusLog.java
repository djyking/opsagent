package com.example.opsagent.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ticket_status_log")
public class TicketStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private String fromStatus;

    private String toStatus;

    private String operator;

    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
