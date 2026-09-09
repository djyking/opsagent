package com.opsagent.ticket;

import static com.opsagent.ticket.TicketQueueDtos.*;

import com.opsagent.common.core.PageResult;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 队列、筛选和统计复用同一可见事件集，阶段与详情使用同一生命周期判定。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class TicketQueueService {
    private final TicketService tickets;
    private final EventLifecycleService lifecycle;
    private final SlaService sla;
    private final TicketActorNames names;

    TicketQueueService(
            TicketService tickets,
            EventLifecycleService lifecycle,
            SlaService sla,
            TicketActorNames names) {
        this.tickets = tickets;
        this.lifecycle = lifecycle;
        this.sla = sla;
        this.names = names;
    }

    @Transactional(readOnly = true)
    PageResult<Row> page(Query query) {
        List<Entry> filtered =
                visible(query).stream()
                        .filter(row -> scope(row, query.eventScope()))
                        .filter(
                                row ->
                                        query.eventStage().isEmpty()
                                                || row.stage().equals(query.eventStage()))
                        .toList();
        long pageNumber =
                Math.min(
                        query.pageNum(),
                        Math.max(1, (filtered.size() + query.pageSize() - 1) / query.pageSize()));
        int start = (int) ((pageNumber - 1) * query.pageSize());
        Map<Long, String> labels = new HashMap<>();
        List<Row> rows =
                filtered.stream()
                        .skip(start)
                        .limit(query.pageSize())
                        .map(
                                row ->
                                        new Row(
                                                row.ticket(),
                                                row.stage(),
                                                row.ticket().assigneeId() == null
                                                        ? "待分配"
                                                        : labels.computeIfAbsent(
                                                                row.ticket().assigneeId(),
                                                                names::name),
                                                brief(
                                                        sla.detail(row.ticket().id()),
                                                        LocalDateTime.now())))
                        .toList();
        return new PageResult<>(rows, filtered.size(), pageNumber, query.pageSize());
    }

    @Transactional(readOnly = true)
    Summary summary(Query query) {
        List<Entry> rows = visible(query);
        long closed = rows.stream().filter(row -> row.stage().equals("CLOSED")).count();
        long archived = rows.stream().filter(row -> row.stage().equals("LEGACY_ARCHIVED")).count();
        var counts =
                new Counts(
                        rows.size(),
                        rows.size() - closed - archived,
                        count(rows, "HANDLING"),
                        count(rows, "VERIFYING"),
                        count(rows, "READY_TO_CLOSE"),
                        closed,
                        archived);
        List<Person> people =
                rows.stream()
                        .map(row -> row.ticket().assigneeId())
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .map(id -> new Person(id, names.name(id)))
                        .toList();
        List<String> services =
                rows.stream()
                        .map(row -> row.ticket().affectedCiCode())
                        .filter(Objects::nonNull)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .sorted()
                        .toList();
        return new Summary(counts, people, services, LocalDateTime.now());
    }

    private List<Entry> visible(Query query) {
        // TicketService.list 已应用 ADMIN/OPS/USER/DEMO 的既有可见性，统计不能绕过它。
        return tickets.list().stream()
                .filter(ticket -> !"mine".equals(query.scope()) || ownedByCurrentActor(ticket))
                .filter(ticket -> matches(ticket, query))
                .map(ticket -> new Entry(ticket, lifecycle.read(ticket.id()).stage()))
                .toList();
    }

    private static boolean ownedByCurrentActor(TicketDtos.View ticket) {
        var actor = SecurityUsers.current();
        return actor.roles().contains("DEMO")
                ? Objects.equals(ticket.ownerActorId(), actor.userId())
                : Objects.equals(ticket.assigneeId(), actor.userId());
    }

    static boolean matches(TicketDtos.View ticket, Query query) {
        String keyword = query.keyword().toLowerCase(Locale.ROOT);
        String searchable =
                (ticket.ticketNo() + " " + ticket.title() + " " + ticket.description())
                        .toLowerCase(Locale.ROOT);
        return (keyword.isEmpty() || searchable.contains(keyword))
                && (query.status().isEmpty() || query.status().equals(ticket.status()))
                && (query.priority().isEmpty() || query.priority().equals(ticket.priority()))
                && (query.affectedCiCode().isEmpty()
                        || query.affectedCiCode().equals(ticket.affectedCiCode()))
                && (query.assigneeId() == null || query.assigneeId().equals(ticket.assigneeId()));
    }

    private static long count(List<Entry> rows, String stage) {
        return rows.stream().filter(row -> row.stage().equals(stage)).count();
    }

    private static boolean scope(Entry row, String scope) {
        return switch (scope) {
            case "OPEN" -> !Set.of("CLOSED", "LEGACY_ARCHIVED").contains(row.stage());
            case "CLOSED" -> row.stage().equals("CLOSED");
            case "ARCHIVED" -> row.stage().equals("LEGACY_ARCHIVED");
            default -> true;
        };
    }

    static SlaBrief brief(Map<String, Object> value, LocalDateTime now) {
        if (value == null || value.isEmpty())
            return new SlaBrief(false, "无适用策略", null, null, "UNKNOWN", "UNKNOWN", false, false);
        String response = Objects.toString(value.get("responseStatus"), "UNKNOWN");
        String resolution = Objects.toString(value.get("resolutionStatus"), "UNKNOWN");
        LocalDateTime responseDeadline = time(value.get("responseDeadline"));
        LocalDateTime resolutionDeadline = time(value.get("resolutionDeadline"));
        boolean paused = response.equals("PAUSED") || resolution.equals("PAUSED");
        boolean breached =
                response.equals("BREACHED")
                        || resolution.equals("BREACHED")
                        || (response.equals("RUNNING")
                                && responseDeadline != null
                                && !responseDeadline.isAfter(now))
                        || (resolution.equals("RUNNING")
                                && resolutionDeadline != null
                                && !resolutionDeadline.isAfter(now));
        return new SlaBrief(
                true,
                Objects.toString(value.get("policyName"), "既有 SLA 策略"),
                responseDeadline,
                resolutionDeadline,
                response,
                resolution,
                paused,
                breached);
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime date) return date;
        if (value instanceof java.sql.Timestamp stamp) return stamp.toLocalDateTime();
        if (value != null) {
            try {
                return LocalDateTime.parse(value.toString().replace(' ', 'T'));
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private record Entry(TicketDtos.View ticket, String stage) {}
}
