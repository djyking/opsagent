package com.opsagent.rag;

import com.opsagent.common.core.ApiResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 向管理员提供脱敏的 AI Provider 配置和连通性诊断接口。
 *
 * @author heyu
 * @since 2026/9/2
 */
@RestController
@RequestMapping("/api/rag/admin/providers")
@PreAuthorize("hasRole('ADMIN')")
public class AiProviderHealthController {
    private final AiProviderHealthService service;

    AiProviderHealthController(AiProviderHealthService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<AiProviderHealthService.ProviderHealth>> configurations() {
        return ApiResponse.success(service.configurations());
    }

    @PostMapping("/{provider}/probe")
    ApiResponse<AiProviderHealthService.ProviderHealth> probe(@PathVariable String provider) {
        return ApiResponse.success(service.probe(provider));
    }
}
