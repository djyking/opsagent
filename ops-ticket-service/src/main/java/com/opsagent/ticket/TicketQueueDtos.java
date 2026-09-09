package com.opsagent.ticket;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件队列的筛选、独立阶段和真实 SLA 摘要。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class TicketQueueDtos {
    private TicketQueueDtos() {}

    record Query(
            @Min(1) Integer pageNum,
            @Min(1) @Max(100) Integer pageSize,
            @Size(max = 200) String keyword,
            @Pattern(
                            regexp =
                                    "|CREATED|ASSIGNED|PROCESSING|SUSPENDED|WAITING_CONFIRM|RESOLVED|CLOSED|REJECTED")
                    String status,
            @Pattern(regexp = "|LOW|MEDIUM|HIGH|URGENT") String priority,
            @Size(max = 64) String affectedCiCode,
            Long assigneeId,
            @Pattern(regexp = "|OPEN|CLOSED|ARCHIVED|ALL") String eventScope,
            @Pattern(regexp = "|HANDLING|VERIFYING|READY_TO_CLOSE|CLOSED|LEGACY_ARCHIVED")
                    String eventStage,
            @Pattern(regexp = "|mine") String scope) {
        Query(
                Integer pageNum,
                Integer pageSize,
                String keyword,
                String status,
                String priority,
                String affectedCiCode,
                Long assigneeId,
                String eventScope,
                String eventStage) {
            this(
                    pageNum,
                    pageSize,
                    keyword,
                    status,
                    priority,
                    affectedCiCode,
                    assigneeId,
                    eventScope,
                    eventStage,
                    "");
        }

        Query {
            pageNum = pageNum == null ? 1 : pageNum;
            pageSize = pageSize == null ? 10 : pageSize;
            keyword = text(keyword);
            status = text(status);
            priority = text(priority);
            affectedCiCode = text(affectedCiCode);
            eventScope = text(eventScope);
            eventStage = text(eventStage);
            scope = text(scope);
        }

        private static String text(String value) {
            return value == null ? "" : value.trim();
        }
    }

    record Row(
            @JsonUnwrapped TicketDtos.View ticket,
            String currentStage,
            String assigneeName,
            SlaBrief sla) {}

    record SlaBrief(
            boolean applicable,
            String policyName,
            LocalDateTime responseDeadline,
            LocalDateTime resolutionDeadline,
            String responseStatus,
            String resolutionStatus,
            boolean paused,
            boolean breached) {}

    record Person(long id, String name) {}

    record Counts(
            long total,
            long open,
            long handling,
            long verifying,
            long readyToClose,
            long closed,
            long archived) {}

    record Summary(
            Counts counts,
            List<Person> assignees,
            List<String> services,
            LocalDateTime checkedAt) {}
}
