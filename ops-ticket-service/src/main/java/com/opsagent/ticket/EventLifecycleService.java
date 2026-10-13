package com.opsagent.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.security.SecurityUsers;

import jakarta.validation.constraints.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * 独立记录事件处理结果、恢复确认与关闭，复用工单锁和不可变处置记录。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class EventLifecycleService {
    private final TicketService service;
    private final TicketMapper tickets;
    private final TicketAuditMapper audit;
    private final ObjectMapper json;
    private final Set<String> technicalOnlyServices;
    private final AlertEpisodeMapper episodes;
    private final EventRecoveryVerifier verifier;

    EventLifecycleService(
            TicketService service,
            TicketMapper tickets,
            TicketAuditMapper audit,
            ObjectMapper json,
            AlertEpisodeMapper episodes,
            EventRecoveryVerifier verifier,
            @Value("${ops.event.technical-only-services:}") String services) {
        this.service = service;
        this.tickets = tickets;
        this.audit = audit;
        this.json = json;
        this.episodes = episodes;
        this.verifier = verifier;
        technicalOnlyServices = new HashSet<>();
        Arrays.stream(services.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .forEach(technicalOnlyServices::add);
    }

    @Transactional(readOnly = true)
    View read(long id) {
        Ticket ticket = service.require(id);
        service.authorizeRead(ticket);
        return view(ticket, audit.workRecords(id));
    }

    @Transactional
    View act(long id, Action action) {
        // 先锁行再读取幂等记录，避免 REPEATABLE_READ 快照与 MyBatis 一级缓存复用锁前的旧记录。
        Ticket ticket = tickets.lock(id);
        if (ticket == null) throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在");
        service.authorizeWrite(ticket);
        OpsPrincipal actor = SecurityUsers.current();
        String payload = payload(action);
        // 请求身份与载荷同时核对；网络结果不确定时重试不会重复写入或推进状态。
        var prior =
                audit.operations(id).stream()
                        .filter(row -> action.requestId().equals(row.requestId()))
                        .findFirst();
        if (prior.isPresent()) {
            if (prior.get().operatorId() != actor.userId()
                    || !samePayload(payload, prior.get().detailJson()))
                throw new BusinessException(ErrorCode.CONFLICT, "请求标识已用于其他操作，请刷新核对");
            return view(ticket, audit.workRecords(id));
        }
        if (!Objects.equals(ticket.getVersion(), action.version()))
            throw new BusinessException(ErrorCode.CONFLICT, "事件已被更新，请保留填写内容并刷新核对");
        View current = view(ticket, audit.workRecords(id));
        if (!current.allowedActions().contains(action.action()))
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态或责任权限不允许此操作，请核对关闭条件");
        if (Set.of("TECH_PASS", "TECH_FAIL", "BUSINESS_CONFIRM").contains(action.action())
                && (action.evidence() == null || action.evidence().isBlank()))
            throw new BusinessException(ErrorCode.VALIDATION, "恢复确认必须填写实际检查结果或证据来源");
        if (Set.of("TECH_PASS", "CLOSE").contains(action.action()))
            verifier.verify(
                    ticket, current.result() == null ? null : current.result().createTime());
        if (Set.of("TECH_PASS", "CLOSE").contains(action.action())
                && ticket.getEpisodeId() != null) {
            var episode = episodes.lock(ticket.getEpisodeId());
            if (episode == null || !"resolved".equals(episode.currentStatus()))
                throw new BusinessException(ErrorCode.CONFLICT, "关联告警尚未确认恢复，不能覆盖已有告警反证");
        }
        if (tickets.touch(id, action.version()) != 1)
            throw new BusinessException(ErrorCode.CONFLICT, "事件版本已变化，请刷新核对");
        audit.workRecord(
                id,
                "EVENT_" + action.action(),
                (actor.roles().contains("DEMO") ? "【访客演练确认】" : "") + action.content().trim(),
                action.evidence() == null ? null : action.evidence().trim(),
                actor.userId());
        audit.operation(
                id, actor.userId(), "EVENT_" + action.action(), action.requestId(), payload);
        ticket.setVersion(ticket.getVersion() + 1);
        if (Set.of("RESULT", "TECH_FAIL", "REOPEN", "CLOSE").contains(action.action())
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            verifier.updateWatch(ticket, !"RESULT".equals(action.action()));
                        }
                    });
        }
        return view(ticket, audit.workRecords(id));
    }

    private View view(Ticket ticket, List<TicketAuditMapper.WorkRecord> records) {
        boolean required = !technicalOnlyServices.contains(ticket.getAffectedCiCode());
        State state = reduce(records);
        OpsPrincipal actor = SecurityUsers.current();
        boolean operator =
                actor.roles().contains("ADMIN")
                        || Objects.equals(ticket.getAssigneeId(), actor.userId());
        boolean visitorOwner =
                actor.roles().contains("DEMO")
                        && TicketService.ownIsolatedDrill(ticket, actor)
                        && ticket.getIncidentId() != null
                        && !ticket.getIncidentId().isBlank();
        boolean writable = !actor.roles().contains("DEMO") || visitorOwner;
        boolean businessActor =
                visitorOwner
                        || actor.roles().contains("ADMIN")
                        || (operator && actor.roles().contains("OPS"));
        List<String> allowed = new ArrayList<>();
        boolean workDone = Set.of("RESOLVED", "CLOSED").contains(ticket.getStatus());
        var episode = ticket.getEpisodeId() == null ? null : episodes.find(ticket.getEpisodeId());
        boolean alertReady =
                ticket.getEpisodeId() == null
                        || (episode != null && "resolved".equals(episode.currentStatus()));
        boolean ready =
                state.technical() != null
                        && (!required || state.business() != null)
                        && workDone
                        && alertReady;
        if (writable && state.closed() == null && !state.legacyArchived()) {
            if (businessActor && !Set.of("CREATED", "REJECTED").contains(ticket.getStatus())) {
                allowed.add("RESULT");
                if (state.result() != null) {
                    if (alertReady) allowed.add("TECH_PASS");
                    allowed.add("TECH_FAIL");
                }
                if (ready) allowed.add("CLOSE");
            }
            if (businessActor && state.technical() != null && required)
                allowed.add("BUSINESS_CONFIRM");
        } else if (writable
                && businessActor
                && state.closed() != null
                && !"CLOSED".equals(ticket.getStatus())) allowed.add("REOPEN");
        String stage =
                state.legacyArchived()
                        ? "LEGACY_ARCHIVED"
                        : state.closed() != null
                                ? "CLOSED"
                                : ready
                                        ? "READY_TO_CLOSE"
                                        : state.result() != null ? "VERIFYING" : "HANDLING";
        List<String> blockers = new ArrayList<>();
        if (state.legacyArchived()) blockers.add("升级前已终结的工单档案，未补造恢复验证；如问题再次出现请报告新事件");
        else if (state.closed() == null) {
            if (state.result() == null) blockers.add("尚未提交处理结果");
            if (state.technical() == null) blockers.add("尚未完成技术恢复确认");
            if (required && state.business() == null) blockers.add("尚未取得业务确认");
            if (!workDone) blockers.add("当前主工单尚未标记已解决");
            if (!alertReady) blockers.add("关联告警尚未确认恢复");
            if (!businessActor) blockers.add("正式恢复确认与关闭由担当运维人员或管理员完成");
        }
        return new View(
                "EVT-" + ticket.getId(),
                ticket.getId(),
                ticket.getVersion(),
                stage,
                required,
                visitorOwner
                        ? "本人隔离演练确认；不代表生产业务负责人签字"
                        : required ? "该服务未被明确豁免，默认需要业务确认；管理员可兼任并留痕" : "该服务明确列入技术验证类，无需独立业务确认",
                state.result(),
                state.technical(),
                state.business(),
                state.closed(),
                allowed,
                blockers,
                records.stream().filter(row -> row.recordType().startsWith("EVENT_")).toList(),
                visitorOwner ? "VISITOR_DRILL" : "OPERATIONS");
    }

    static State reduce(List<TicketAuditMapper.WorkRecord> records) {
        TicketAuditMapper.WorkRecord result = null;
        TicketAuditMapper.WorkRecord technical = null;
        TicketAuditMapper.WorkRecord business = null;
        TicketAuditMapper.WorkRecord closed = null;
        boolean legacyArchived = false;
        for (var row :
                records.stream()
                        .sorted(Comparator.comparingLong(TicketAuditMapper.WorkRecord::id))
                        .toList()) {
            switch (row.recordType()) {
                case "EVENT_LEGACY_ARCHIVE" -> {
                    legacyArchived = true;
                }
                case "EVENT_REOPEN", "EVENT_TECH_FAIL", "EVENT_RECOVERY_BINDING" -> {
                    result = null;
                    technical = null;
                    business = null;
                    closed = null;
                    legacyArchived = false;
                }
                case "EVENT_RESULT" -> {
                    result = row;
                    technical = null;
                    business = null;
                    closed = null;
                    legacyArchived = false;
                }
                case "EVENT_TECH_PASS" -> {
                    if (result != null && closed == null) {
                        technical = row;
                        business = null;
                    }
                }
                case "EVENT_BUSINESS_CONFIRM" -> {
                    if (technical != null && closed == null) business = row;
                }
                case "EVENT_CLOSE" -> {
                    if (technical != null) closed = row;
                }
                default -> {
                    /* 普通备注、工单状态与历史验证不推进事件生命周期。 */
                }
            }
        }
        return new State(result, technical, business, closed, legacyArchived);
    }

    private String payload(Action action) {
        try {
            return json.writeValueAsString(
                    Map.of(
                            "action",
                            action.action(),
                            "content",
                            action.content().trim(),
                            "evidence",
                            action.evidence() == null ? "" : action.evidence().trim()));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "事件记录序列化失败");
        }
    }

    private boolean samePayload(String left, String right) {
        try {
            return json.readTree(left).equals(json.readTree(right));
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    record State(
            TicketAuditMapper.WorkRecord result,
            TicketAuditMapper.WorkRecord technical,
            TicketAuditMapper.WorkRecord business,
            TicketAuditMapper.WorkRecord closed,
            boolean legacyArchived) {}

    record Action(
            @NotBlank @Pattern(regexp = "RESULT|TECH_PASS|TECH_FAIL|BUSINESS_CONFIRM|CLOSE|REOPEN")
                    String action,
            @NotNull @Min(0) Integer version,
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 2000) String content,
            @Size(max = 1000) String evidence) {}

    record View(
            String eventId,
            long ticketId,
            int version,
            String stage,
            boolean businessRequired,
            String businessRule,
            TicketAuditMapper.WorkRecord result,
            TicketAuditMapper.WorkRecord technical,
            TicketAuditMapper.WorkRecord business,
            TicketAuditMapper.WorkRecord closed,
            List<String> allowedActions,
            List<String> blockers,
            List<TicketAuditMapper.WorkRecord> history,
            String confirmationScope) {}
}
