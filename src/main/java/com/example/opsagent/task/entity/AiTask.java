package com.example.opsagent.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_task")
public class AiTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;

    private Long bizId;

    private String taskType;

    private String status;

    private String requestPayload;

    private String result;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
