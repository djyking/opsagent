package com.example.opsagent.ticket.controller;

import java.util.List;

import com.example.opsagent.common.api.ApiResponse;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.ticket.dto.TicketActionRequest;
import com.example.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.ticket.service.TicketService;
import com.example.opsagent.ticket.vo.TicketStatusLogVO;
import com.example.opsagent.ticket.vo.TicketVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供当前用户数据范围内的工单查询和状态动作接口。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    public ApiResponse<TicketVO> create(@Valid @RequestBody TicketCreateRequest request) {
        return ApiResponse.success(ticketService.createTicket(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketVO> detail(@PathVariable Long id) {
        return ApiResponse.success(ticketService.detail(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<TicketVO>> page(@Valid @ModelAttribute TicketQueryRequest request) {
        return ApiResponse.success(ticketService.pageTickets(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TicketVO> update(@PathVariable Long id, @Valid @RequestBody TicketUpdateRequest request) {
        return ApiResponse.success(ticketService.updateTicket(id, request));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<TicketVO> accept(@PathVariable Long id, @Valid @RequestBody TicketActionRequest request) {
        return ApiResponse.success(ticketService.accept(id, request));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<TicketVO> resolve(@PathVariable Long id, @Valid @RequestBody TicketActionRequest request) {
        return ApiResponse.success(ticketService.resolve(id, request));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<TicketVO> close(@PathVariable Long id, @Valid @RequestBody TicketActionRequest request) {
        return ApiResponse.success(ticketService.close(id, request));
    }

    @GetMapping("/{id}/status-logs")
    public ApiResponse<List<TicketStatusLogVO>> statusLogs(@PathVariable Long id) {
        return ApiResponse.success(ticketService.listStatusLogs(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ticketService.deleteTicket(id);
        return ApiResponse.success();
    }
}
