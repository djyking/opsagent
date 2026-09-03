package com.opsagent.ticket;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;

/**
 * 工单服务内部使用的请求与响应模型集合。
 *
 * @author heyu
 * @since 2026/8/11
 */
final class TicketDtos {
    private TicketDtos() {}

    /**
     * 创建工单请求参数。
     *
     * @author heyu
     * @since 2026/8/11
     */
    record Create(
            @NotBlank @Size(max = 128) String title,
            @NotBlank String description,
            @NotBlank @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT") String priority) {}

    /**
     * 领取工单请求参数。
     *
     * @author heyu
     * @since 2026/8/11
     */
    record Claim(@NotNull @Min(0) Integer version) {}

    /**
     * 工单状态操作请求参数。
     *
     * @author heyu
     * @since 2026/8/11
     */
    record Action(
            @NotNull TicketStatus target,
            @NotNull @Min(0) Integer version,
            @Size(max = 512) String remark) {}

    /**
     * 新增工单评论请求参数。
     *
     * @author heyu
     * @since 2026/8/11
     */
    record AddComment(@NotBlank @Size(max = 2000) String content) {}

    /**
     * 工单详情响应数据。
     *
     * @author heyu
     * @since 2026/8/11
     */
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
