package com.example.opsagent.ticket.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在工单主事务中记录关键状态操作。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
@TableName("ticket_status_log")
public class TicketStatusLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;

    private Long operatorId;

    private String operationType;

    private String fromStatus;

    private String toStatus;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
