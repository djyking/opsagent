package com.example.opsagent.audit.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统操作日志响应模型。
 *
 * @author heyu
 * @since 2026/7/16
 */
@Data
public class OperationLogVO {

    private Long id;

    private String bizType;

    private Long bizId;

    private String operationType;

    private String operator;

    private String content;

    private LocalDateTime createTime;
}
