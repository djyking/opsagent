package com.opsagent.ticket;

import static com.opsagent.ticket.TicketDtos.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.opsagent.common.core.*;
import com.opsagent.common.security.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 工单领域服务，集中处理数据权限、并发接单和状态机约束。 */
@Service
public class TicketService {
    private final TicketMapper tickets;
    private final TicketAuditMapper audit;
    private final OutboxMapper outbox;

    TicketService(TicketMapper t, TicketAuditMapper a, OutboxMapper o) {
        tickets = t;
        audit = a;
        outbox = o;
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
        t.setVersion(0);
        t.setDeleted(0);
        tickets.insert(t);
        audit.history(t.getId(), u.userId(), "CREATE", null, t.getStatus(), null);
        outbox.add(
                UUID.randomUUID().toString(),
                t.getId(),
                "ticket.created",
                "{\"ticketId\":" + t.getId() + "}");
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
        audit.history(id, u.userId(), "CLAIM", "CREATED", "ASSIGNED", null);
        outbox.add(
                UUID.randomUUID().toString(),
                id,
                "ticket.assigned",
                "{\"ticketId\":" + id + ",\"assigneeId\":" + u.userId() + "}");
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
        audit.history(
                id, u.userId(), r.target().name(), source.name(), r.target().name(), r.remark());
        outbox.add(
                UUID.randomUUID().toString(),
                id,
                "ticket." + r.target().name().toLowerCase(),
                "{\"ticketId\":" + id + "}");
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
                t.getVersion(),
                t.getCreateTime(),
                t.getUpdateTime());
    }
}
