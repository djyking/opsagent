package com.example.opsagent.opsagent.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.opsagent.common.BusinessException;
import com.example.opsagent.opsagent.common.ErrorCode;
import com.example.opsagent.opsagent.common.PageResponse;
import com.example.opsagent.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.opsagent.ticket.dto.TicketResponse;
import com.example.opsagent.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.opsagent.ticket.entity.Ticket;
import com.example.opsagent.opsagent.ticket.mapper.TicketMapper;
import com.example.opsagent.opsagent.ticket.service.TicketService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements TicketService {

    @Override
    public TicketResponse createTicket(TicketCreateRequest request) {
        Ticket ticket = new Ticket();
        BeanUtils.copyProperties(request, ticket);
        save(ticket);
        return toResponse(ticket);
    }

    @Override
    public TicketResponse updateTicket(TicketUpdateRequest request) {
        Ticket existing = getById(request.getId());
        if (existing == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "ticket not found");
        }
        BeanUtils.copyProperties(request, existing);
        updateById(existing);
        return toResponse(getById(request.getId()));
    }

    @Override
    public void deleteTicket(Long id) {
        if (getById(id) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "ticket not found");
        }
        removeById(id);
    }

    @Override
    public TicketResponse getTicket(Long id) {
        Ticket ticket = getById(id);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "ticket not found");
        }
        return toResponse(ticket);
    }

    @Override
    public PageResponse<TicketResponse> pageTickets(TicketQueryRequest request) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .like(StringUtils.hasText(request.getTitle()), Ticket::getTitle, request.getTitle())
                .eq(StringUtils.hasText(request.getStatus()), Ticket::getStatus, request.getStatus())
                .eq(StringUtils.hasText(request.getPriority()), Ticket::getPriority, request.getPriority())
                .eq(StringUtils.hasText(request.getSourceSystem()), Ticket::getSourceSystem, request.getSourceSystem())
                .orderByDesc(Ticket::getCreatedAt);
        Page<Ticket> page = page(new Page<>(request.getPageNo(), request.getPageSize()), wrapper);
        List<TicketResponse> records = page.getRecords().stream().map(this::toResponse).toList();
        return PageResponse.of(page, records);
    }

    private TicketResponse toResponse(Ticket ticket) {
        TicketResponse response = new TicketResponse();
        BeanUtils.copyProperties(ticket, response);
        return response;
    }
}
