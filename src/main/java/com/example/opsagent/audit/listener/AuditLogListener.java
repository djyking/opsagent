package com.example.opsagent.audit.listener;

import com.example.opsagent.audit.entity.OperationLog;
import com.example.opsagent.audit.service.OperationLogService;
import com.example.opsagent.ticket.event.TicketStatusChangedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在工单状态事务提交后记录操作审计日志。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogListener {

    private final OperationLogService operationLogService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTicketStatusChanged(TicketStatusChangedEvent event) {
        try {
            OperationLog operationLog = new OperationLog();
            operationLog.setBizType("TICKET");
            operationLog.setBizId(event.ticketId());
            operationLog.setOperationType("STATUS_CHANGE");
            operationLog.setOperator(String.valueOf(event.operatorId()));
            operationLog.setContent(
                    "工单 #"
                            + event.ticketId()
                            + " 状态从 "
                            + event.fromStatus()
                            + " 修改为 "
                            + event.toStatus()
                            + (event.remark() == null ? "" : "，备注：" + event.remark()));
            if (!operationLogService.save(operationLog)) {
                throw new IllegalStateException("操作审计日志保存失败");
            }
        } catch (RuntimeException exception) {
            log.error("记录工单状态审计日志失败，ticketId={}", event.ticketId(), exception);
        }
    }
}
