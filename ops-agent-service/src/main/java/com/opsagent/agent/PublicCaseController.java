package com.opsagent.agent;

import com.opsagent.common.core.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 精选公开案例只读入口，与所有者私有运行接口保持分离。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/automation/public-cases")
class PublicCaseController {
    private final PublicCaseService service;

    PublicCaseController(PublicCaseService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<PublicCaseDtos.CaseView>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    ApiResponse<PublicCaseDtos.CaseView> detail(@PathVariable String id) {
        return ApiResponse.success(service.detail(id));
    }
}
