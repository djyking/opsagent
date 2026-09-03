package com.example.opsagent.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.opsagent.common.api.PageResponse;
import com.example.opsagent.common.exception.BusinessException;
import com.example.opsagent.common.exception.ErrorCode;
import com.example.opsagent.security.current.CurrentUserContext;
import com.example.opsagent.ticket.dao.TicketDao;
import com.example.opsagent.ticket.dto.TicketActionRequest;
import com.example.opsagent.ticket.dto.TicketCreateRequest;
import com.example.opsagent.ticket.dto.TicketQueryRequest;
import com.example.opsagent.ticket.dto.TicketUpdateRequest;
import com.example.opsagent.ticket.entity.Ticket;
import com.example.opsagent.ticket.entity.TicketStatusLog;
import com.example.opsagent.ticket.enums.TicketPriority;
import com.example.opsagent.ticket.enums.TicketStatus;
import com.example.opsagent.ticket.event.TicketStatusChangedEvent;
import com.example.opsagent.ticket.service.TicketService;
import com.example.opsagent.ticket.service.TicketStatusLogService;
import com.example.opsagent.ticket.vo.TicketStatusLogVO;
import com.example.opsagent.ticket.vo.TicketVO;

import lombok.RequiredArgsConstructor;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 实现基于当前认证用户的工单数据权限、状态机和核心操作记录。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Service
@RequiredArgsConstructor
public class TicketServiceImpl extends ServiceImpl<TicketDao, Ticket> implements TicketService {

    private final TicketStatusLogService statusLogService;

    private final ApplicationEventPublisher eventPublisher;

    private final CurrentUserContext currentUser;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO createTicket(TicketCreateRequest request) {
        Long userId = currentUser.userId();
        Ticket ticket = new Ticket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setTitle(request.getTitle().trim());
        ticket.setDescription(request.getDescription().trim());
        ticket.setPriority(TicketPriority.normalize(request.getPriority()));
        ticket.setStatus(TicketStatus.CREATED.name());
        ticket.setCreatorId(userId);
        ticket.setVersion(0);
        ticket.setDeleted(0);
        if (!save(ticket)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建工单失败");
        }
        saveOperation(ticket.getId(), userId, "CREATE", null, TicketStatus.CREATED, null);
        return toVO(requireTicket(ticket.getId()));
    }

    @Override
    public TicketVO detail(Long id) {
        return toVO(requireAccessibleTicket(id));
    }

    @Override
    public PageResponse<TicketVO> pageTickets(TicketQueryRequest request) {
        validatePage(request.getPageNum(), request.getPageSize());
        Long userId = currentUser.userId();
        LambdaQueryWrapper<Ticket> query = new LambdaQueryWrapper<>();
        if (currentUser.hasRole("ADMIN")) {
            // 管理员查看全部工单。
        } else if (currentUser.hasRole("OPS")) {
            query.and(
                    wrapper ->
                            wrapper.eq(Ticket::getStatus, TicketStatus.CREATED.name())
                                    .or()
                                    .eq(Ticket::getAssigneeId, userId));
        } else {
            query.eq(Ticket::getCreatorId, userId);
        }
        if (StringUtils.hasText(request.getStatus())) {
            query.eq(Ticket::getStatus, TicketStatus.parse(request.getStatus()).name());
        }
        if (StringUtils.hasText(request.getPriority())) {
            query.eq(Ticket::getPriority, TicketPriority.normalize(request.getPriority()));
        }
        if (StringUtils.hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            query.and(
                    wrapper ->
                            wrapper.like(Ticket::getTicketNo, keyword)
                                    .or()
                                    .like(Ticket::getTitle, keyword)
                                    .or()
                                    .like(Ticket::getDescription, keyword));
        }
        query.orderByDesc(Ticket::getCreateTime);
        Page<Ticket> page = page(new Page<>(request.getPageNum(), request.getPageSize()), query);
        return PageResponse.from(page, this::toVO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO updateTicket(Long id, TicketUpdateRequest request) {
        Ticket ticket = requireTicket(id);
        Long userId = currentUser.userId();
        if (!currentUser.hasRole("ADMIN") && !Objects.equals(ticket.getCreatorId(), userId)) {
            throw forbidden();
        }
        requireStatus(ticket, TicketStatus.CREATED);
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
        if (!updateById(ticket)) {
            throw conflict();
        }
        return toVO(requireTicket(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO accept(Long id, TicketActionRequest request) {
        requireRole("OPS", "ADMIN");
        Ticket ticket = requireTicket(id);
        requireStatus(ticket, TicketStatus.CREATED);
        ticket.setAssigneeId(currentUser.userId());
        return transition(ticket, TicketStatus.PROCESSING, "ACCEPT", request.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO resolve(Long id, TicketActionRequest request) {
        Ticket ticket = requireTicket(id);
        Long userId = currentUser.userId();
        if (!currentUser.hasRole("ADMIN") && !Objects.equals(ticket.getAssigneeId(), userId)) {
            throw forbidden();
        }
        requireStatus(ticket, TicketStatus.PROCESSING);
        return transition(ticket, TicketStatus.RESOLVED, "RESOLVE", request.getRemark());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TicketVO close(Long id, TicketActionRequest request) {
        Ticket ticket = requireTicket(id);
        Long userId = currentUser.userId();
        if (!currentUser.hasRole("ADMIN") && !Objects.equals(ticket.getCreatorId(), userId)) {
            throw forbidden();
        }
        requireStatus(ticket, TicketStatus.RESOLVED);
        return transition(ticket, TicketStatus.CLOSED, "CLOSE", request.getRemark());
    }

    @Override
    public List<TicketStatusLogVO> listStatusLogs(Long id) {
        requireAccessibleTicket(id);
        return statusLogService
                .list(
                        new LambdaQueryWrapper<TicketStatusLog>()
                                .eq(TicketStatusLog::getTicketId, id)
                                .orderByAsc(TicketStatusLog::getCreateTime))
                .stream()
                .map(this::toStatusLogVO)
                .toList();
    }

    @Override
    public Ticket requireAccessibleTicket(Long id) {
        Ticket ticket = requireTicket(id);
        Long userId = currentUser.userId();
        boolean accessible =
                currentUser.hasRole("ADMIN")
                        || Objects.equals(ticket.getCreatorId(), userId)
                        || Objects.equals(ticket.getAssigneeId(), userId)
                        || (currentUser.hasRole("OPS")
                                && TicketStatus.CREATED.name().equals(ticket.getStatus()));
        if (!accessible) {
            throw forbidden();
        }
        return ticket;
    }

    @Override
    public void requireDocumentPermission(Long id) {
        Ticket ticket = requireTicket(id);
        Long userId = currentUser.userId();
        if (!currentUser.hasRole("ADMIN")
                && !Objects.equals(ticket.getCreatorId(), userId)
                && !Objects.equals(ticket.getAssigneeId(), userId)) {
            throw forbidden();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTicket(Long id) {
        requireRole("ADMIN");
        requireTicket(id);
        if (!removeById(id)) {
            throw conflict();
        }
    }

    private TicketVO transition(
            Ticket ticket, TicketStatus target, String operationType, String remark) {
        TicketStatus current = TicketStatus.parse(ticket.getStatus());
        if (!current.canTransitionTo(target)) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "不允许将工单状态从 " + current + " 修改为 " + target);
        }
        ticket.setStatus(target.name());
        if (!updateById(ticket)) {
            throw conflict();
        }
        Long operatorId = currentUser.userId();
        String normalizedRemark = trimToNull(remark);
        saveOperation(ticket.getId(), operatorId, operationType, current, target, normalizedRemark);
        eventPublisher.publishEvent(
                new TicketStatusChangedEvent(
                        ticket.getId(),
                        ticket.getTitle(),
                        current.name(),
                        target.name(),
                        operatorId,
                        ticket.getCreatorId(),
                        ticket.getAssigneeId(),
                        normalizedRemark,
                        LocalDateTime.now()));
        return toVO(requireTicket(ticket.getId()));
    }

    private void saveOperation(
            Long ticketId,
            Long operatorId,
            String operationType,
            TicketStatus from,
            TicketStatus to,
            String remark) {
        TicketStatusLog operation = new TicketStatusLog();
        operation.setTicketId(ticketId);
        operation.setOperatorId(operatorId);
        operation.setOperationType(operationType);
        operation.setFromStatus(from == null ? null : from.name());
        operation.setToStatus(to.name());
        operation.setRemark(remark);
        if (!statusLogService.save(operation)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存工单操作记录失败");
        }
    }

    private Ticket requireTicket(Long id) {
        Ticket ticket = getById(id);
        if (ticket == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在");
        }
        return ticket;
    }

    private void requireStatus(Ticket ticket, TicketStatus expected) {
        TicketStatus actual = TicketStatus.parse(ticket.getStatus());
        if (actual != expected) {
            throw new BusinessException(
                    ErrorCode.CONFLICT, "当前工单状态为 " + actual + "，要求状态为 " + expected);
        }
    }

    private void requireRole(String... roles) {
        for (String role : roles) {
            if (currentUser.hasRole(role)) {
                return;
            }
        }
        throw forbidden();
    }

    private BusinessException forbidden() {
        return new BusinessException(ErrorCode.FORBIDDEN, "无权操作该工单");
    }

    private BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "工单数据已变更，请刷新后重试");
    }

    private TicketVO toVO(Ticket ticket) {
        TicketVO result = new TicketVO();
        result.setId(ticket.getId());
        result.setTicketNo(ticket.getTicketNo());
        result.setTitle(ticket.getTitle());
        result.setDescription(ticket.getDescription());
        result.setPriority(ticket.getPriority());
        result.setStatus(ticket.getStatus());
        result.setCreatorId(ticket.getCreatorId());
        result.setAssigneeId(ticket.getAssigneeId());
        result.setVersion(ticket.getVersion());
        result.setCreateTime(ticket.getCreateTime());
        result.setUpdateTime(ticket.getUpdateTime());
        return result;
    }

    private TicketStatusLogVO toStatusLogVO(TicketStatusLog log) {
        TicketStatusLogVO result = new TicketStatusLogVO();
        result.setId(log.getId());
        result.setTicketId(log.getTicketId());
        result.setOperatorId(log.getOperatorId());
        result.setOperationType(log.getOperationType());
        result.setFromStatus(log.getFromStatus());
        result.setToStatus(log.getToStatus());
        result.setRemark(log.getRemark());
        result.setCreateTime(log.getCreateTime());
        return result;
    }

    private String generateTicketNo() {
        return "OPS-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
