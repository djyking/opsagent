package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.core.PageResult;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 登录用户可读运维证据；只有管理员与运维人员可以启动固定工作流。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@RequestMapping("/api/platform/operations")
@PreAuthorize("isAuthenticated()")
public class OperationsController {
    private final OperationsOverviewService overview;
    private final OperationsWorkflowService workflows;

    OperationsController(OperationsOverviewService overview, OperationsWorkflowService workflows) {
        this.overview = overview;
        this.workflows = workflows;
    }

    @GetMapping("/overview")
    ApiResponse<OperationsDtos.Overview> overview(@RequestParam(defaultValue = "60") int windowMinutes) {
        return ApiResponse.success(overview.overview(windowMinutes, false));
    }

    @GetMapping("/context")
    ApiResponse<OperationsDtos.Overview> context() {
        return ApiResponse.success(overview.overview(60, false).context());
    }

    @GetMapping("/workflows")
    ApiResponse<List<OperationsDtos.Workflow>> workflows() {
        return ApiResponse.success(workflows.workflows());
    }

    @GetMapping("/runs")
    ApiResponse<PageResult<OperationsDtos.Run>> runs(
            @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.success(workflows.page(pageNum, pageSize));
    }

    @GetMapping("/runs/{id}")
    ApiResponse<OperationsDtos.RunDetail> run(@PathVariable long id) {
        return ApiResponse.success(workflows.detail(id));
    }

    @PostMapping("/runs")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    ApiResponse<OperationsDtos.RunDetail> start(@Valid @RequestBody OperationsDtos.StartRun request) {
        return ApiResponse.success(workflows.start(request));
    }
}
