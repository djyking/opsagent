package com.example.opsagent.ticket.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Data;

/**
 * 工单分页查询请求参数。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Data
public class TicketQueryRequest {

    private String status;

    private String priority;

    private String keyword;

    @Min(1)
    private Long pageNum = 1L;

    @Min(1)
    @Max(100)
    private Long pageSize = 10L;
}
