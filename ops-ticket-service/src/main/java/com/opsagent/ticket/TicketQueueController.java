package com.opsagent.ticket;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.PageResult;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/**
 * 提供权限一致的事件队列和摘要，不再由浏览器对全量工单分页。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/tickets/queue")
class TicketQueueController {
    private final TicketQueueService service;

    TicketQueueController(TicketQueueService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<PageResult<TicketQueueDtos.Row>> page(
            @Valid @ModelAttribute TicketQueueDtos.Query query) {
        return ApiResponse.success(service.page(query));
    }

    @GetMapping("/summary")
    ApiResponse<TicketQueueDtos.Summary> summary(
            @Valid @ModelAttribute TicketQueueDtos.Query query) {
        return ApiResponse.success(service.summary(query));
    }
}
