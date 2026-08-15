package com.example.opsagent.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.ticket.dto.TicketStatusUpdateRequest;
import com.example.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.ticket.entity.Ticket;
import com.example.opsagent.ticket.dao.TicketDao;
import com.example.opsagent.ticket.entity.TicketStatusLog;
import com.example.opsagent.ticket.enums.TicketPriority;
import com.example.opsagent.ticket.enums.TicketStatus;
import com.example.opsagent.ticket.event.TicketStatusChangedEvent;
import com.example.opsagent.ticket.service.TicketService;
import com.example.opsagent.ticket.service.TicketStatusLogService;
import com.example.opsagent.ticket.vo.TicketStatusLogVO;
import com.example.opsagent.ticket.vo.TicketVO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 实现工单 CRUD、合法状态流转、状态日志和业务事件发布。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketDao, Ticket> implements TicketService {

    private final TicketStatusLogService statusLogService;

    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO createTicket(TicketCreateRequest request) {
        Ticket ticket = new Ticket();
        ticket.setTitle(request.getTitle().trim());
        ticket.setDescription(request.getDescription().trim());
        ticket.setPriority(TicketPriority.normalize(request.getPriority()));
        ticket.setStatus(TicketStatus.OPEN.name());
        ticket.setCreator(request.getCreator().trim());
        ticket.setAssignee(trimToNull(request.getAssignee()));
        ticket.setDeleted(0);
        if (!save(ticket)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建工单失败");
        }
        return toVO(requireTicket(ticket.getId()));
    }

    @Override
    public TicketVO detail(Long id) {
        return toVO(requireTicket(id));
    }

    @Override
    public PageResponse<TicketVO> pageTickets(TicketQueryRequest request) {
        validatePage(request.getPageNum(), request.getPageSize());
        LambdaQueryWrapper<Ticket> query = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(request.getStatus())) {
            query.eq(Ticket::getStatus, TicketStatus.parse(request.getStatus()).name());
        }
        if (StringUtils.hasText(request.getPriority())) {
            query.eq(Ticket::getPriority, TicketPriority.normalize(request.getPriority()));
        }
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            query.and(wrapper -> wrapper.like(Ticket::getTitle, keyword)
                .or().like(Ticket::getDescription, keyword));
        }
        query.orderByDesc(Ticket::getCreateTime);
        Page<Ticket> page = page(new Page<>(request.getPageNum(), request.getPageSize()), query);
        return PageResponse.from(page, this::toVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO updateTicket(Long id, TicketUpdateRequest request) {
        Ticket ticket = requireTicket(id);
        if (request.getTitle() != null) {
            requireText(request.getTitle(), "工单标题不能为空");
            ticket.setTitle(request.getTitle().trim());
        }
        if (request.getDescription() != null) {
            requireText(request.getDescription(), "工单描述不能为空");
            ticket.setDescription(request.getDescription().trim());
        }
        if (request.getPriority() != null) {
            ticket.setPriority(TicketPriority.normalize(request.getPriority()));
        }
        if (request.getAssignee() != null) {
            ticket.setAssignee(trimToNull(request.getAssignee()));
        }
        if (!updateById(ticket)) {
            throw new BusinessException(ErrorCode.CONFLICT, "工单更新失败，请刷新后重试");
        }
        return toVO(requireTicket(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO updateStatus(Long id, TicketStatusUpdateRequest request) {
        Ticket ticket = requireTicket(id);
        TicketStatus current = TicketStatus.parse(ticket.getStatus());
        TicketStatus target = TicketStatus.parse(request.getTargetStatus());
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "不允许将工单状态从 " + current + " 修改为 " + target);
        }

        ticket.setStatus(target.name());
        if (!updateById(ticket)) {
            throw new BusinessException(ErrorCode.CONFLICT, "工单状态更新失败，请刷新后重试");
        }

        TicketStatusLog statusLog = new TicketStatusLog();
        statusLog.setTicketId(id);
        statusLog.setFromStatus(current.name());
        statusLog.setToStatus(target.name());
        statusLog.setOperator(request.getOperator().trim());
        statusLog.setReason(trimToNull(request.getReason()));
        if (!statusLogService.save(statusLog)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存工单状态日志失败");
        }

        eventPublisher.publishEvent(new TicketStatusChangedEvent(id, ticket.getTitle(), current.name(), target.name(),
            request.getOperator().trim(), ticket.getAssignee(), trimToNull(request.getReason()),
            java.time.LocalDateTime.now()));
        return toVO(requireTicket(id));
    }

    @Override
    public List<TicketStatusLogVO> listStatusLogs(Long id) {
        requireTicket(id);
        return statusLogService.list(new LambdaQueryWrapper<TicketStatusLog>()
                .eq(TicketStatusLog::getTicketId, id)
                .orderByAsc(TicketStatusLog::getCreateTime))
            .stream()
            .map(this::toStatusLogVO)
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTicket(Long id) {
        requireTicket(id);
        if (!removeById(id)) {
            throw new BusinessException(ErrorCode.CONFLICT, "删除工单失败");
        }
    }

    private Ticket requireTicket(Long id) {
        Ticket ticket = getById(id);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在");
        }
        return ticket;
    }

    private TicketVO toVO(Ticket ticket) {
        TicketVO result = new TicketVO();
        result.setId(ticket.getId());
        result.setTitle(ticket.getTitle());
        result.setDescription(ticket.getDescription());
        result.setPriority(ticket.getPriority());
        result.setStatus(ticket.getStatus());
        result.setCreator(ticket.getCreator());
        result.setAssignee(ticket.getAssignee());
        result.setCreateTime(ticket.getCreateTime());
        result.setUpdateTime(ticket.getUpdateTime());
        return result;
    }

    private TicketStatusLogVO toStatusLogVO(TicketStatusLog log) {
        TicketStatusLogVO result = new TicketStatusLogVO();
        result.setId(log.getId());
        result.setTicketId(log.getTicketId());
        result.setFromStatus(log.getFromStatus());
        result.setToStatus(log.getToStatus());
        result.setOperator(log.getOperator());
        result.setReason(log.getReason());
        result.setCreateTime(log.getCreateTime());
        return result;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void validatePage(Long pageNum, Long pageSize) {
        if (pageNum == null || pageNum < 1 || pageSize == null || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("pageNum 必须大于 0，pageSize 必须在 1 到 100 之间");
        }
    }
}
