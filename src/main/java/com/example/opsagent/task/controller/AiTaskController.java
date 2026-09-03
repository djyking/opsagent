package com.example.opsagent.task.controller;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.task.service.AiTaskService;
import com.example.opsagent.task.vo.AiTaskVO;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供 AI 异步任务查询与管理接口。
 *
 * @author heyu
 * @since 2026/7/15
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tasks/ai")
public class AiTaskController {

    private final AiTaskService aiTaskService;

    @GetMapping
    public ApiResponse<PageResponse<AiTaskVO>> page(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize) {
        return ApiResponse.success(aiTaskService.pageTasks(pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<AiTaskVO> detail(@PathVariable Long id) {
        return ApiResponse.success(aiTaskService.detail(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<AiTaskVO> updateStatus(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success(aiTaskService.updateStatus(id, status));
    }
}
