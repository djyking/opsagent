package com.example.opsagent.opsagent.ticket.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.opsagent.ticket.dto.TicketResponse;
import com.example.opsagent.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.opsagent.ticket.entity.Ticket;

public interface TicketService extends IService<Ticket> {

    TicketResponse createTicket(TicketCreateRequest request);

    TicketResponse updateTicket(TicketUpdateRequest request);

    void deleteTicket(Long id);

    TicketResponse getTicket(Long id);

    PageResponse<TicketResponse> pageTickets(TicketQueryRequest request);
}
