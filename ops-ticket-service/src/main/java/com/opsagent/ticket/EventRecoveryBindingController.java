package com.opsagent.ticket;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

/**
 * Authorized, versioned recovery binding entry point.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/tickets/{id}/event-lifecycle/recovery-binding")
class EventRecoveryBindingController {
    private final EventRecoveryBindingService service;

    EventRecoveryBindingController(EventRecoveryBindingService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<EventRecoveryBindingService.View> read(@PathVariable long id) {
        return ApiResponse.success(service.read(id));
    }

    @PostMapping
    ApiResponse<EventRecoveryBindingService.View> update(
            @PathVariable long id,
            @Valid @RequestBody EventRecoveryBindingService.Binding binding) {
        return ApiResponse.success(service.update(id, binding));
    }
}
