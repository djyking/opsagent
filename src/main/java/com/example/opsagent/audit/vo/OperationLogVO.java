package com.example.opsagent.audit.vo;

import java.time.LocalDateTime;
import lombok.Data;

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
