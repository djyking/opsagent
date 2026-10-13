package com.opsagent.ticket;

import static com.opsagent.ticket.TicketDtos.*;

import com.opsagent.common.core.ApiResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 提供工单创建、查询、接单、流转、评论和历史接口。
 *
 * @author heyu
 * @since 2026/8/10
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService service;

    TicketController(TicketService s) {
        service = s;
    }

    @PostMapping
    ApiResponse<View> create(@Valid @RequestBody Create r) {
        return ApiResponse.success(service.create(r));
    }

    @GetMapping
    ApiResponse<List<View>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    ApiResponse<View> detail(@PathVariable long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping("/{id}/claim")
    ApiResponse<View> claim(@PathVariable long id, @Valid @RequestBody Claim r) {
        return ApiResponse.success(service.claim(id, r));
    }

    @PostMapping("/{id}/transition")
    ApiResponse<View> transition(@PathVariable long id, @Valid @RequestBody Action r) {
        return ApiResponse.success(service.transition(id, r));
    }

    @PostMapping("/{id}/comments")
    ApiResponse<TicketAuditMapper.Comment> comment(
            @PathVariable long id, @Valid @RequestBody AddComment r) {
        return ApiResponse.success(service.comment(id, r));
    }

    @GetMapping("/{id}/comments")
    ApiResponse<List<TicketAuditMapper.Comment>> comments(@PathVariable long id) {
        return ApiResponse.success(service.comments(id));
    }

    @GetMapping("/{id}/history")
    ApiResponse<List<TicketAuditMapper.History>> history(@PathVariable long id) {
        return ApiResponse.success(service.history(id));
    }

    @PostMapping("/{id}/work-records")
    ApiResponse<TicketAuditMapper.WorkRecord> addWorkRecord(
            @PathVariable long id, @Valid @RequestBody AddWorkRecord request) {
        return ApiResponse.success(service.addWorkRecord(id, request));
    }

    @GetMapping("/{id}/work-records")
    ApiResponse<List<TicketAuditMapper.WorkRecord>> workRecords(@PathVariable long id) {
        return ApiResponse.success(service.workRecords(id));
    }

    @GetMapping("/{id}/trace")
    ApiResponse<Map<String, Object>> trace(@PathVariable long id) {
        return ApiResponse.success(service.trace(id));
    }
}
