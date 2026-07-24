package com.example.opsagent.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ticket")
public class Ticket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;

    private String description;

    private String priority;

    private String status;

    private String creator;

    private String assignee;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
