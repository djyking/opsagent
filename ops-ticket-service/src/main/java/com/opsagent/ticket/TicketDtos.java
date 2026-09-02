package com.opsagent.ticket;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

/** 工单服务内部使用的请求与响应模型集合。 */
final class TicketDtos {
    private TicketDtos() {}

    record Create(
            @NotBlank @Size(max = 128) String title,
            @NotBlank String description,
            @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT") String priority) {}

    record Claim(@NotNull @Min(0) Integer version) {}

    record Action(
            @NotNull TicketStatus target,
            @NotNull @Min(0) Integer version,
            @Size(max = 512) String remark) {}

    record AddComment(@NotBlank @Size(max = 2000) String content) {}

    record View(
            Long id,
            String ticketNo,
            String title,
            String description,
            String priority,
            String status,
            Long creatorId,
            Long assigneeId,
            Integer version,
            LocalDateTime createTime,
            LocalDateTime updateTime) {}
}
