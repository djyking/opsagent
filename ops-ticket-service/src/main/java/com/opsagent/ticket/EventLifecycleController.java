package com.opsagent.ticket;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/**
 * 事件生命周期入口；与原工单流转接口分离。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/tickets/{id}/event-lifecycle")
class EventLifecycleController {
    private final EventLifecycleService service;

    EventLifecycleController(EventLifecycleService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<EventLifecycleService.View> read(@PathVariable long id) {
        return ApiResponse.success(service.read(id));
    }

    @PostMapping
    ApiResponse<EventLifecycleService.View> act(
            @PathVariable long id, @Valid @RequestBody EventLifecycleService.Action action) {
        return ApiResponse.success(service.act(id, action));
    }
}
