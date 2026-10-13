package com.opsagent.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.opsagent.common.core.ApiResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * File drafts, separate approval, publication and durable task queries for configured local
 * targets.
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/platform/configuration/files")
@PreAuthorize("hasAnyRole('ADMIN', 'OPS')")
class FileConfigurationController {
    private final FileConfigurationClient client;

    FileConfigurationController(FileConfigurationClient client) {
        this.client = client;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS', 'DEMO')")
    ResponseEntity<ApiResponse<JsonNode>> list() {
        return response(client.get("/files"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS', 'DEMO')")
    ResponseEntity<ApiResponse<JsonNode>> detail(@PathVariable String id) {
        return response(client.get("/files/" + id));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPS', 'DEMO')")
    ResponseEntity<ApiResponse<JsonNode>> history(@PathVariable String id) {
        return response(client.get("/files/" + id + "/history"));
    }

    @PostMapping("/{id}/drafts")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ApiResponse<JsonNode>> create(
            @PathVariable String id, @RequestBody JsonNode request) {
        return response(client.post("/files/" + id + "/drafts", request));
    }

    @GetMapping("/drafts/{id}")
    ResponseEntity<ApiResponse<JsonNode>> draft(@PathVariable String id) {
        return response(client.get("/drafts/" + id));
    }

    @PostMapping("/drafts/{id}/submit")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ApiResponse<JsonNode>> submit(
            @PathVariable String id, @RequestBody JsonNode request) {
        return response(client.post("/drafts/" + id + "/submit", request));
    }

    @PostMapping("/drafts/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ApiResponse<JsonNode>> approve(
            @PathVariable String id, @RequestBody JsonNode request) {
        return response(client.post("/drafts/" + id + "/approve", request));
    }

    @PostMapping("/drafts/{id}/execute")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ApiResponse<JsonNode>> execute(
            @PathVariable String id, @RequestBody JsonNode request) {
        return response(client.post("/drafts/" + id + "/execute", request));
    }

    @GetMapping("/tasks/{id}")
    ResponseEntity<ApiResponse<JsonNode>> task(@PathVariable String id) {
        return response(client.get("/tasks/" + id));
    }

    @PostMapping("/tasks/{id}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<ApiResponse<JsonNode>> verify(
            @PathVariable String id, @RequestBody JsonNode request) {
        return response(client.post("/tasks/" + id + "/verify", request));
    }

    private static ResponseEntity<ApiResponse<JsonNode>> response(JsonNode value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }
}
