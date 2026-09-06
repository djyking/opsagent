package com.opsagent.demo;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 隔离通知业务与固定恢复接口，复用既有内部控制 token 过滤器。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
class DemoNotificationController {
    private final DemoNotificationRuntime runtime;

    DemoNotificationController(DemoNotificationRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/demo/notifications/preview")
    ResponseEntity<Map<String, Object>> preview() {
        var result = runtime.preview();
        return ResponseEntity.status(((Number) result.get("httpStatus")).intValue()).body(result);
    }

    @GetMapping("/internal/demo/notification/snapshot")
    Map<String, Object> snapshot() {
        return runtime.snapshot();
    }

    @PostMapping("/internal/demo/notification/scenarios")
    Map<String, Object> inject(@Valid @RequestBody DemoController.Fault request) throws Exception {
        return runtime.inject(request.incidentId(), request.scenarioCode(), request.expiresAt());
    }

    @PostMapping("/internal/demo/notification/actions")
    Map<String, Object> restore(@Valid @RequestBody DemoController.Action request)
            throws Exception {
        return runtime.restore(
                request.incidentId(),
                request.action(),
                request.expectedRevision(),
                request.recoverySource());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> failure(Exception exception) {
        String reason = exception.getMessage();
        if (reason == null || !reason.matches("[A-Z_]{1,64}")) reason = "TARGET_OPERATION_FAILED";
        return ResponseEntity.status(exception instanceof IllegalArgumentException ? 400 : 409)
                .body(Map.of("reasonCode", reason));
    }
}
