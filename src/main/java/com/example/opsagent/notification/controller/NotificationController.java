package com.example.opsagent.notification.controller;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.notification.service.NotificationRecordService;
import com.example.opsagent.notification.vo.NotificationRecordVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRecordService notificationRecordService;

    @GetMapping
    public ApiResponse<PageResponse<NotificationRecordVO>> page(
            @RequestParam(defaultValue = "1") Long pageNum,
            @RequestParam(defaultValue = "10") Long pageSize) {
        return ApiResponse.success(notificationRecordService.pageRecords(pageNum, pageSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationRecordVO> detail(@PathVariable Long id) {
        return ApiResponse.success(notificationRecordService.detail(id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<NotificationRecordVO> updateStatus(
            @PathVariable Long id,
            @RequestParam String status) {
        return ApiResponse.success(notificationRecordService.updateStatus(id, status));
    }
}
