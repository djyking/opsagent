package com.opsagent.demo;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 固定订单业务与受控配置接口；错误只返回安全的原因标识。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
public class DemoController {
    private final DemoRuntime runtime;

    DemoController(DemoRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/demo/orders/preview")
    ResponseEntity<DemoRuntime.BusinessResult> preview() {
        var result = runtime.preview();
        return ResponseEntity.status(result.httpStatus()).body(result);
    }

    @GetMapping("/internal/demo/snapshot")
    Map<String, Object> snapshot() {
        return runtime.snapshot();
    }

    @GetMapping("/internal/demo/configuration/{id}")
    Map<String, Object> configuration(@PathVariable String id) throws Exception {
        return runtime.managedConfiguration(id);
    }

    @PostMapping("/internal/demo/configuration/order-business")
    Map<String, Object> publishBusiness(@Valid @RequestBody BusinessPublication request)
            throws Exception {
        return runtime.publishBusinessConfiguration(
                request.content(), request.expectedRevision(), request.requestId());
    }

    @PostMapping("/internal/demo/scenarios")
    Map<String, Object> inject(@Valid @RequestBody Fault request) throws Exception {
        return runtime.inject(request.incidentId(), request.scenarioCode(), request.expiresAt());
    }

    @PostMapping("/internal/demo/actions")
    Map<String, Object> restore(@Valid @RequestBody Action request) throws Exception {
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

    /**
     * @author heyu
     */
    public record Fault(
            @Pattern(regexp = "[a-f0-9-]{36}") String incidentId,
            @NotBlank String scenarioCode,
            Instant expiresAt) {}

    /**
     * @author heyu
     */
    public record Action(
            @NotBlank String incidentId,
            @NotBlank String action,
            @Pattern(regexp = "[a-f0-9]{64}") String expectedRevision,
            @Pattern(regexp = "AGENT_TOOL|MANUAL") String recoverySource) {}

    /**
     * @author heyu
     */
    public record BusinessPublication(
            JsonNode content,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedRevision,
            @NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String requestId) {}
}
