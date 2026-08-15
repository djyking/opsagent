package com.example.opsagent.ticket.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.ticket.dto.TicketStatusUpdateRequest;
import com.example.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.ticket.entity.Ticket;
import com.example.opsagent.ticket.vo.TicketStatusLogVO;
import com.example.opsagent.ticket.vo.TicketVO;
import java.util.List;

public interface TicketService extends IService<Ticket> {

    TicketVO createTicket(TicketCreateRequest request);

    TicketVO detail(Long id);

    PageResponse<TicketVO> pageTickets(TicketQueryRequest request);

    TicketVO updateTicket(Long id, TicketUpdateRequest request);

    TicketVO updateStatus(Long id, TicketStatusUpdateRequest request);

    List<TicketStatusLogVO> listStatusLogs(Long id);

    void deleteTicket(Long id);
}
