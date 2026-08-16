package com.example.opsagent.notification.listener;

import com.example.opsagent.notification.entity.NotificationRecord;
import com.example.opsagent.notification.service.NotificationRecordService;
import com.example.opsagent.ticket.event.TicketStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 在工单状态事务提交后生成站内通知记录。
 *
 * @author heyu
 * @since 2026/8/15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private final NotificationRecordService notificationRecordService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTicketStatusChanged(TicketStatusChangedEvent event) {
        try {
            NotificationRecord record = new NotificationRecord();
            record.setTicketId(event.ticketId());
            Long receiverId = "CLOSED".equals(event.toStatus()) && event.assigneeId() != null
                ? event.assigneeId() : event.creatorId();
            record.setReceiver(String.valueOf(receiverId));
            record.setTitle("工单状态变更通知");
            record.setContent("工单【" + event.title() + "】状态已从 " + event.fromStatus()
                + " 变更为 " + event.toStatus());
            record.setStatus("PENDING");
            if (!notificationRecordService.save(record)) {
                throw new IllegalStateException("通知记录保存失败");
            }
        } catch (RuntimeException exception) {
            log.error("生成工单状态通知失败，ticketId={}", event.ticketId(), exception);
        }
    }
}
