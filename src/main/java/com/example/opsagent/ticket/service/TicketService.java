package com.example.opsagent.ticket.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.ticket.dto.TicketActionRequest;
import com.example.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.ticket.entity.Ticket;
import com.example.opsagent.ticket.vo.TicketStatusLogVO;
import com.example.opsagent.ticket.vo.TicketVO;

import java.util.List;

/**
 * 定义工单数据权限、基础修改和业务状态动作。
 *
 * @author heyu
 * @since 2026/8/16
 */
public interface TicketService extends IService<Ticket> {

    TicketVO createTicket(TicketCreateRequest request);

    TicketVO detail(Long id);

    PageResponse<TicketVO> pageTickets(TicketQueryRequest request);

    TicketVO updateTicket(Long id, TicketUpdateRequest request);

    TicketVO accept(Long id, TicketActionRequest request);

    TicketVO resolve(Long id, TicketActionRequest request);

    TicketVO close(Long id, TicketActionRequest request);

    List<TicketStatusLogVO> listStatusLogs(Long id);

    Ticket requireAccessibleTicket(Long id);

    void requireDocumentPermission(Long id);

    void deleteTicket(Long id);
}
