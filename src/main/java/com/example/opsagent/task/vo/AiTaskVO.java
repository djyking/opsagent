package com.example.opsagent.task.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 异步任务响应模型。
 *
 * @author heyu
 * @since 2026/7/16
 */
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
