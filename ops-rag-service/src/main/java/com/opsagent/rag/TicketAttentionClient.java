package com.opsagent.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 沿用请求用户身份读取已授权告警，只接收问答需要的白名单字段。
 *
 * @author heyu
 * @since 2026/9/3
 */
@FeignClient(
        name = "ops-ticket-service",
        contextId = "ragTicketAttention",
        url = "${ops.rag.ticket-url:}")
interface TicketAttentionClient {
    @GetMapping("/api/tickets/alerts")
    KnowledgeClient.Envelope<List<Alert>> activeAlerts(@RequestParam String status);

    /**
     * @author heyu
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Alert(
            String alertName,
            String serviceCode,
            String severity,
            String currentStatus,
            Long ticketId,
            String ticketStatus,
            String lastSeenTime) {}
}
