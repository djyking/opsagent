package com.example.opsagent.task.vo;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class AiTaskVO {

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
