package com.example.opsagent.opsagent.ticket.controller;

import com.example.opsagent.opsagent.common.ApiResponse;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.opsagent.ticket.dto.TicketResponse;
import com.example.opsagent.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.opsagent.ticket.service.TicketService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    public ApiResponse<TicketResponse> create(@Valid @RequestBody TicketCreateRequest request) {
        return ApiResponse.success(ticketService.createTicket(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TicketResponse> update(@PathVariable Long id, @Valid @RequestBody TicketUpdateRequest request) {
        request.setId(id);
        return ApiResponse.success(ticketService.updateTicket(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @NotNull Long id) {
        ticketService.deleteTicket(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketResponse> detail(@PathVariable @NotNull Long id) {
        return ApiResponse.success(ticketService.getTicket(id));
    }

    @GetMapping
    public ApiResponse<PageResponse<TicketResponse>> page(@Valid TicketQueryRequest request) {
        return ApiResponse.success(ticketService.pageTickets(request));
    }
}
