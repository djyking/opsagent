package com.example.opsagent.ticket.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 承载工单接收、解决和关闭操作的备注。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Data
public class TicketActionRequest {

    @Size(max = 512)
    private String remark;
}
