package com.example.opsagent.task.listener;

import com.example.opsagent.task.entity.AiTask;
import com.example.opsagent.task.service.AiTaskService;
import com.example.opsagent.ticket.event.TicketStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在工单解决后创建待处理的 AI 总结任务。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSummaryTaskListener {

    private final AiTaskService aiTaskService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTicketStatusChanged(TicketStatusChangedEvent event) {
        if (!"RESOLVED".equals(event.getToStatus())) {
            return;
        }
        try {
            AiTask task = new AiTask();
            task.setBizType("TICKET");
            task.setBizId(event.getTicketId());
            task.setTaskType("TICKET_SUMMARY");
            task.setStatus("PENDING");
            task.setRequestPayload("工单标题：" + event.getTitle() + "；处理原因："
                + (event.getReason() == null ? "未填写" : event.getReason()));
            if (!aiTaskService.save(task)) {
                throw new IllegalStateException("AI 总结任务保存失败");
            }
        } catch (RuntimeException exception) {
            log.error("创建工单 AI 总结任务失败，ticketId={}", event.getTicketId(), exception);
        }
    }
}
