package com.example.opsagent.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 承载当前认证用户创建工单的业务信息。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class TicketCreateRequest {

    @NotBlank
    @Size(max = 128)
    private String title;

    @NotBlank
    @Size(max = 10000)
    private String description;

    @NotBlank
    private String priority;
}
