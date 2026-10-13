package com.opsagent.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.SecurityUsers;

import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Repair ordinary event recovery associations without changing incident identity or evidence.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class EventRecoveryBindingService {
    private final TicketService service;
    private final TicketMapper tickets;
    private final TicketAuditMapper audit;
    private final EventRecoveryRules rules;
    private final EventRecoveryVerifier verifier;
    private final ObjectMapper json;

    EventRecoveryBindingService(
            TicketService service,
            TicketMapper tickets,
            TicketAuditMapper audit,
            EventRecoveryRules rules,
            EventRecoveryVerifier verifier,
            ObjectMapper json) {
        this.service = service;
        this.tickets = tickets;
        this.audit = audit;
        this.rules = rules;
        this.verifier = verifier;
        this.json = json;
    }

    @Transactional(readOnly = true)
    View read(long id) {
        Ticket ticket = service.require(id);
        service.authorizeRead(ticket);
        return view(ticket);
    }

    @Transactional
    View update(long id, Binding action) {
        Ticket ticket = tickets.lock(id);
        if (ticket == null) throw new BusinessException(ErrorCode.NOT_FOUND, "工单不存在");
        service.authorizeWrite(ticket);
        if (!operator(ticket))
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员或担当运维人员可补全恢复关联");
        var actor = SecurityUsers.current();
        String payload =
                encode(
                        Map.of(
                                "targetCode",
                                action.targetCode(),
                                "environment",
                                action.environment(),
                                "reason",
                                action.reason().trim()));
        var previous =
                audit.operations(id).stream()
                        .filter(row -> action.requestId().equals(row.requestId()))
                        .findFirst();
        if (previous.isPresent()) {
            if (!"EVENT_RECOVERY_BINDING".equals(previous.get().operation())
                    || previous.get().operatorId() != actor.userId()
                    || !samePayload(payload, previous.get().detailJson()))
                throw new BusinessException(ErrorCode.CONFLICT, "请求标识已用于其他操作，请刷新核对");
            return view(ticket);
        }
        if (!Objects.equals(ticket.getVersion(), action.version()))
            throw new BusinessException(ErrorCode.CONFLICT, "事件已被更新，请保留填写内容并刷新核对");
        var current = view(ticket);
        if (!current.canEdit()) throw new BusinessException(ErrorCode.CONFLICT, current.editHint());
        if ("ISOLATED".equals(action.environment()) || action.targetCode().startsWith("ops-demo-"))
            throw new BusinessException(ErrorCode.CONFLICT, "普通事件不能改绑为隔离演练，也不能借用其他 incident 的恢复证据");
        String observedEnvironment = rules.environment(action.environment(), action.targetCode());
        if (Objects.equals(ticket.getAffectedCiCode(), action.targetCode())
                && Objects.equals(ticket.getEnvironment(), action.environment()))
            throw new BusinessException(ErrorCode.CONFLICT, "恢复关联没有变化，无需重新保存");
        verifier.validateBindingTarget(action.targetCode(), observedEnvironment);
        // Stop the old watch while its ticket association is still visible. Failure remains safe:
        // the binding record invalidates all previous confirmations and requires a new result.
        verifier.updateWatch(ticket, true);
        String before =
                Objects.toString(ticket.getAffectedCiCode(), "未关联")
                        + " / "
                        + Objects.toString(ticket.getEnvironment(), "未设置");
        if (tickets.bindRecovery(id, action.version(), action.targetCode(), action.environment())
                != 1) throw new BusinessException(ErrorCode.CONFLICT, "事件版本已变化，请刷新核对");
        audit.workRecord(
                id,
                "EVENT_RECOVERY_BINDING",
                action.reason().trim(),
                before
                        + " → "
                        + action.targetCode()
                        + " / "
                        + action.environment()
                        + "；原处理结果及技术/业务确认失效，需提交新结果后重新观察",
                actor.userId());
        audit.operation(id, actor.userId(), "EVENT_RECOVERY_BINDING", action.requestId(), payload);
        ticket.setAffectedCiCode(action.targetCode());
        ticket.setEnvironment(action.environment());
        ticket.setVersion(ticket.getVersion() + 1);
        return view(ticket);
    }

    private View view(Ticket ticket) {
        boolean isolated = isolated(ticket);
        var records = audit.workRecords(ticket.getId());
        var state = EventLifecycleService.reduce(records);
        boolean finished =
                state.closed() != null
                        || state.legacyArchived()
                        || "CLOSED".equals(ticket.getStatus());
        List<String> blockers = new ArrayList<>();
        if (isolated) {
            if (ticket.getAffectedCiCode() == null || ticket.getAffectedCiCode().isBlank())
                blockers.add("原演练缺少目标服务关联");
            if (!"ISOLATED".equals(ticket.getEnvironment()))
                blockers.add("原演练环境不是 ISOLATED，请核对原始事件数据");
            if (ticket.getIncidentId() == null || ticket.getIncidentId().isBlank())
                blockers.add("缺少原演练 incident 标识，无法证明是同一次现场");
        } else {
            blockers.addAll(
                    rules.bindingBlockers(ticket.getEnvironment(), ticket.getAffectedCiCode()));
            if (ticket.getAffectedCiCode() == null || ticket.getAffectedCiCode().isBlank()) {
                records.stream()
                        .filter(record -> "ALERT_BINDING_PENDING".equals(record.recordType()))
                        .max(Comparator.comparingLong(TicketAuditMapper.WorkRecord::id))
                        .ifPresent(
                                record -> {
                                    blockers.removeIf(value -> value.startsWith("缺少关联服务"));
                                    blockers.add(0, record.content());
                                });
            }
        }
        String hint =
                isolated
                        ? "隔离演练使用原 incident 和原目标，不允许改绑其他现场；请在关联演练中恢复。原始关联缺失时需核对原始事件记录。"
                        : finished
                                ? "已关闭或历史归档事件保留原关联；如故障重现请创建新事件"
                                : !operator(ticket)
                                        ? "由管理员或担当运维人员补全恢复关联"
                                        : "保存后原处理结果和恢复确认失效；请重新提交实际处理结果并等待新的真实样本";
        String observed =
                isolated
                        ? "ISOLATED"
                        : ticket.getEnvironment() == null
                                ? null
                                : rules.availableEnvironments().get(ticket.getEnvironment());
        return new View(
                ticket.getId(),
                ticket.getVersion(),
                isolated ? "ISOLATED" : "REGULAR",
                ticket.getAffectedCiCode(),
                ticket.getEnvironment(),
                observed,
                ticket.getIncidentId(),
                !isolated && !finished && operator(ticket),
                hint,
                blockers,
                isolated ? List.of() : rules.availableTargets(),
                isolated ? Map.of() : rules.availableEnvironments(),
                isolated ? "同一 incident、当前业务探针连续成功、关联告警恢复；历史或其他现场证据无效" : rules.summary());
    }

    static boolean isolated(Ticket ticket) {
        return "ISOLATED".equals(ticket.getEnvironment())
                || "ISOLATED_DRILL".equals(ticket.getSourceType())
                || ticket.getIncidentId() != null && !ticket.getIncidentId().isBlank();
    }

    private boolean operator(Ticket ticket) {
        var actor = SecurityUsers.current();
        return !actor.roles().contains("DEMO")
                && (actor.roles().contains("ADMIN")
                        || actor.roles().contains("OPS")
                                && Objects.equals(ticket.getAssigneeId(), actor.userId()));
    }

    private String encode(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "恢复关联记录序列化失败");
        }
    }

    private boolean samePayload(String left, String right) {
        try {
            return json.readTree(left).equals(json.readTree(right));
        } catch (JsonProcessingException failure) {
            return false;
        }
    }

    record Binding(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{1,96}") String targetCode,
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{1,32}") String environment,
            @NotNull @Min(0) Integer version,
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 500) String reason) {}

    record View(
            long ticketId,
            int version,
            String kind,
            String targetCode,
            String environment,
            String observedEnvironment,
            String incidentId,
            boolean canEdit,
            String editHint,
            List<String> blockers,
            List<String> targets,
            Map<String, String> environments,
            String rule) {}
}
