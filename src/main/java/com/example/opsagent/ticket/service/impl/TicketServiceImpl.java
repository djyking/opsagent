package com.example.opsagent.ticket.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.ticket.dto.TicketStatusUpdateRequest;
import com.example.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.ticket.entity.Ticket;
import com.example.opsagent.ticket.dao.TicketDao;
import com.example.opsagent.ticket.service.TicketService;
import com.example.opsagent.ticket.vo.TicketStatusLogVO;
import com.example.opsagent.ticket.vo.TicketVO;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TicketServiceImpl extends ServiceImpl<TicketDao, Ticket> implements TicketService {

    @Override
    public TicketVO createTicket(TicketCreateRequest request) {
        return new TicketVO();
    }

    @Override
    public TicketVO detail(Long id) {
        return new TicketVO();
    }

    @Override
    public PageResponse<TicketVO> pageTickets(TicketQueryRequest request) {
        return PageResponse.empty(request.getPageNum(), request.getPageSize());
    }

    @Override
    public TicketVO updateTicket(Long id, TicketUpdateRequest request) {
        return new TicketVO();
    }

    @Override
    public TicketVO updateStatus(Long id, TicketStatusUpdateRequest request) {
        return new TicketVO();
    }

    @Override
    public List<TicketStatusLogVO> listStatusLogs(Long id) {
        return Collections.emptyList();
    }
}
