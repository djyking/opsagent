package com.opsagent.platform;

import com.opsagent.common.core.ApiResponse;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登录用户读取登记主机范围的资源指标。
 *
 * @author heyu
 * @since 2026/9/3
 */
@RestController
@PreAuthorize("isAuthenticated()")
class HostResourceController {
    private final HostResourceService hosts;

    HostResourceController(HostResourceService hosts) {
        this.hosts = hosts;
    }

    @GetMapping("/api/platform/operations/host-resources")
    ApiResponse<HostResourceService.Snapshot> read(
            @RequestParam(defaultValue = "60") int windowMinutes,
            @RequestParam(required = false) String ciCode) {
        return ApiResponse.success(hosts.read(windowMinutes, ciCode, false));
    }
}
