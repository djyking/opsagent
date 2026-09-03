package com.example.opsagent.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Data;

/**
 * 承载工单问答记录的文档筛选和分页参数。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class AiChatLogQueryRequest {

    private Long documentId;

    @Min(1)
    private Long pageNum = 1L;

    @Min(1)
    @Max(100)
    private Long pageSize = 10L;
}
