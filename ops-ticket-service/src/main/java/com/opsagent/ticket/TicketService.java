package com.opsagent.ticket;

import static com.opsagent.ticket.TicketDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsagent.common.core.*;
import com.opsagent.common.security.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 工单领域服务，集中处理数据权限、并发接单和状态机约束。
 *
 * @author heyu
 * @since 2026/8/12
 */
@Service
public class TicketService {
    private final TicketMapper tickets;
    private final TicketAuditMapper audit;
    private final OutboxMapper outbox;
    private final ObjectMapper json;
    private final SlaService sla;

    TicketService(
            TicketMapper t,
            TicketAuditMapper a,
            OutboxMapper o,
            ObjectMapper json,
            SlaService sla) {
        tickets = t;
        audit = a;
        outbox = o;
        this.json = json;
        this.sla = sla;
    }

    @Transactional
    View create(Create r) {
        OpsPrincipal u = SecurityUsers.current();
        Ticket t = new Ticket();
        t.setTicketNo(
                "OPS-"
                        + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        t.setTitle(r.title().trim());
        t.setDescription(r.description().trim());
        t.setPriority(r.priority());
        t.setStatus(TicketStatus.CREATED.name());
        t.setCreatorId(u.userId());
        t.setAffectedCiCode(normalize(r.affectedCiCode()));
        t.setSourceType("MANUAL");
        t.setVersion(0);
        t.setDeleted(0);
        tickets.insert(t);
        sla.start(t.getId(), t.getPriority());
        audit.history(t.getId(), u.userId(), "CREATE", null, t.getStatus(), null);
        audit.operation(t.getId(), u.userId(), "CREATE", null, payload(t.getId(), u.userId()));
        addEvent(t.getId(), "ticket.created", u.userId());
        return view(t);
    }

    View detail(long id) {
        Ticket t = require(id);
        authorizeRead(t);
        return view(t);
    }

    List<View> list() {
        OpsPrincipal u = SecurityUsers.current();
        var q = new LambdaQueryWrapper<Ticket>();
        if (!u.roles().contains("ADMIN")) {
            if (u.roles().contains("OPS"))
                q.and(
                        x ->
                                x.eq(Ticket::getStatus, "CREATED")
                                        .or()
                                        .eq(Ticket::getAssigneeId, u.userId()));
            else q.eq(Ticket::getCreatorId, u.userId());
        }
        return tickets.selectList(q.orderByDesc(Ticket::getCreateTime)).stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    View claim(long id, Claim r) {
        OpsPrincipal u = SecurityUsers.current();
        requireRole(u, "OPS", "ADMIN");
        // 单条条件 UPDATE 同时校验状态和版本，保证多实例并发接单只有一个请求成功。
        if (tickets.claim(id, u.userId(), r.version()) != 1)
            throw new BusinessException(ErrorCode.CONFLICT, "工单已被他人接单或版本已变化");
        sla.responseCompleted(id);
        audit.history(id, u.userId(), "CLAIM", "CREATED", "ASSIGNED", null);
        audit.assignment(id, u.userId(), u.userId(), "CLAIM");
        audit.operation(id, u.userId(), "CLAIM", null, payload(id, u.userId()));
        addEvent(id, "ticket.claimed", u.userId());
        return view(require(id));
    }

    @Transactional
    View transition(long id, Action r) {
        OpsPrincipal u = SecurityUsers.current();
        Ticket t = require(id);
        TicketStatus source = TicketStatus.valueOf(t.getStatus());
        if (!source.allows(r.target()))
            throw new BusinessException(
                    ErrorCode.CONFLICT, "不允许从 " + source + " 流转到 " + r.target());
        if (r.target() == TicketStatus.CLOSED) {
            if (!Objects.equals(t.getCreatorId(), u.userId()) && !u.roles().contains("ADMIN"))
                forbidden();
        } else if (!Objects.equals(t.getAssigneeId(), u.userId()) && !u.roles().contains("ADMIN"))
            forbidden();
        // 再次把源状态和版本放入更新条件，避免读取后被其他实例抢先流转。
        if (tickets.transition(id, source.name(), r.target().name(), r.version()) != 1)
            throw new BusinessException(ErrorCode.CONFLICT, "工单版本已变化，请刷新后重试");
        if (r.target() == TicketStatus.RESOLVED) {
            sla.resolutionCompleted(id);
        }
        audit.history(
                id, u.userId(), r.target().name(), source.name(), r.target().name(), r.remark());
        audit.operation(
                id,
                u.userId(),
                r.target().name(),
                null,
                payload(id, u.userId(), "fromStatus", source.name(), "toStatus", r.target().name()));
        addEvent(id, "ticket." + r.target().name().toLowerCase(), u.userId());
        return view(require(id));
    }

    @Transactional
    TicketAuditMapper.Comment comment(long id, AddComment r) {
        Ticket t = require(id);
        authorizeRead(t);
        long user = SecurityUsers.current().userId();
        audit.comment(id, user, r.content().trim());
        return audit.comments(id).get(audit.comments(id).size() - 1);
    }

    List<TicketAuditMapper.History> history(long id) {
        authorizeRead(require(id));
        return audit.historyList(id);
    }

    List<TicketAuditMapper.Comment> comments(long id) {
        authorizeRead(require(id));
        return audit.comments(id);
    }

    @Transactional
    TicketAuditMapper.WorkRecord addWorkRecord(long id, AddWorkRecord request) {
        Ticket ticket = require(id);
        authorizeRead(ticket);
        OpsPrincipal user = SecurityUsers.current();
        audit.workRecord(
                id,
                request.recordType(),
                request.content().trim(),
                normalize(request.evidence()),
                user.userId());
        List<TicketAuditMapper.WorkRecord> records = audit.workRecords(id);
        return records.get(records.size() - 1);
    }

    List<TicketAuditMapper.WorkRecord> workRecords(long id) {
        authorizeRead(require(id));
        return audit.workRecords(id);
    }

    Map<String, Object> trace(long id) {
        Ticket ticket = require(id);
        authorizeRead(ticket);
        return Map.of(
                "ticket",
                view(ticket),
                "history",
                audit.historyList(id),
                "assignments",
                audit.assignments(id),
                "operations",
                audit.operations(id),
                "outboxEvents",
                outbox.traces(id));
    }

    private Ticket require(long id) {
        Ticket t = tickets.selectById(id);
        if (t == null) throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在");
        return t;
    }

    private void authorizeRead(Ticket t) {
        OpsPrincipal u = SecurityUsers.current();
        if (!u.roles().contains("ADMIN")
                && !Objects.equals(t.getCreatorId(), u.userId())
                && !Objects.equals(t.getAssigneeId(), u.userId())
                && !(u.roles().contains("OPS") && "CREATED".equals(t.getStatus()))) forbidden();
    }

    private void requireRole(OpsPrincipal u, String... roles) {
        if (Arrays.stream(roles).noneMatch(u.roles()::contains)) forbidden();
    }

    private void forbidden() {
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作该工单");
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private View view(Ticket t) {
        return new View(
                t.getId(),
                t.getTicketNo(),
                t.getTitle(),
                t.getDescription(),
                t.getPriority(),
                t.getStatus(),
                t.getCreatorId(),
                t.getAssigneeId(),
                t.getAffectedCiCode(),
                t.getSourceType(),
                t.getVersion(),
                t.getCreateTime(),
                t.getUpdateTime());
    }

    private void addEvent(long ticketId, String eventType, long actorId) {
        outbox.add(
                UUID.randomUUID().toString(),
                ticketId,
                eventType,
                payload(ticketId, actorId));
    }

    Ticket createFromAlert(
            String title,
            String description,
            String priority,
            String affectedCiCode) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo(
                "ALT-"
                        + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                        + "-"
                        + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setPriority(priority);
        ticket.setStatus(TicketStatus.CREATED.name());
        ticket.setCreatorId(1L);
        ticket.setAffectedCiCode(normalize(affectedCiCode));
        ticket.setSourceType("ALERTMANAGER");
        ticket.setVersion(0);
        ticket.setDeleted(0);
        tickets.insert(ticket);
        sla.start(ticket.getId(), priority);
        audit.history(
                ticket.getId(),
                1L,
                "ALERT_CREATE",
                null,
                TicketStatus.CREATED.name(),
                "监控告警自动建单");
        audit.operation(
                ticket.getId(),
                1L,
                "ALERT_CREATE",
                null,
                payload(ticket.getId(), 1L, "sourceType", "ALERTMANAGER"));
        addEvent(ticket.getId(), "ticket.alert.created", 1L);
        return ticket;
    }

    private String payload(long ticketId, long actorId, String... entries) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticketId);
        data.put("actorId", actorId);
        for (int i = 0; i + 1 < entries.length; i += 2) data.put(entries[i], entries[i + 1]);
        try {
            return json.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "工单事件序列化失败");
        }
    }
}
