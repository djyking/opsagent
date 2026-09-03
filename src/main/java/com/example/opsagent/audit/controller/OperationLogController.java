package com.example.opsagent.audit.controller;

import com.example.opsagent.audit.service.OperationLogService;
import com.example.opsagent.audit.vo.OperationLogVO;
import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.api.PageResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供系统操作日志查询接口。
 *
 * @author heyu
 * @since 2026/7/15
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/audit/operation-logs")
public class OperationLogController {

    private final OperationLogService operationLogService;

    @GetMapping
    public ApiResponse<PageResponse<OperationLogVO>> page(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize) {
        return ApiResponse.success(operationLogService.pageLogs(pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<OperationLogVO> detail(@PathVariable Long id) {
        return ApiResponse.success(operationLogService.detail(id));
    }
}
