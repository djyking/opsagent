package com.example.opsagent.ticket.dto;

import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 承载工单进入处理前可修改的基础信息。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class TicketUpdateRequest {

    @Size(max = 128)
    private String title;

    @Size(max = 10000)
    private String description;

    private String priority;
}
