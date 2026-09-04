package com.opsagent.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 校验 Alertmanager 独立令牌并完成告警去重、自动建单和恢复记录。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class AlertmanagerService {
    private final AlertMapper alerts;
    private final TicketService tickets;
    private final TicketAuditMapper audit;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private final String webhookToken;

    AlertmanagerService(
            AlertMapper alerts,
            TicketService tickets,
            TicketAuditMapper audit,
            ObjectMapper json,
            MeterRegistry metrics,
            @Value("${ops.alertmanager.enabled:false}") boolean enabled,
            @Value("${ops.alertmanager.webhook-token:}") String webhookToken) {
        this.alerts = alerts;
        this.tickets = tickets;
        this.audit = audit;
        this.json = json;
        this.metrics = metrics;
        this.enabled = enabled;
        this.webhookToken = webhookToken;
    }

    void authenticate(String authorization) {
        metrics.counter("opsagent.alert.webhook").increment();
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "告警接入未启用");
        }
        String actual = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : "";
        boolean valid = !webhookToken.isBlank() && MessageDigest.isEqual(
                webhookToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook Token 无效");
        }
    }

    @Transactional
    List<Map<String, Object>> receive(JsonNode payload) {
        List<Map<String, Object>> results = new ArrayList<>();
        JsonNode items = payload.path("alerts");
        if (!items.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alerts 必须是数组");
        }
        for (JsonNode item : items) {
            results.add(process(item));
        }
        return results;
    }

    Map<String, Object> process(JsonNode item) {
        JsonNode labels = item.path("labels");
        JsonNode annotations = item.path("annotations");
        String status = text(item, "status", "firing").toLowerCase(Locale.ROOT);
        if (!"firing".equals(status) && !"resolved".equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警状态仅支持 firing/resolved");
        }
        String alertName = text(labels, "alertname", "UnnamedAlert");
        String serviceCode = text(labels, "service", text(labels, "job", ""));
        String severity = text(labels, "severity", "warning");
        String fingerprint = text(item, "fingerprint", fingerprint(alertName, serviceCode, labels));
        LocalDateTime seenTime = eventTime(item, status);
        String labelsJson = stringify(labels);
        String annotationsJson = stringify(annotations);
        alerts.insert(
                fingerprint,
                alertName,
                severity,
                serviceCode,
                status,
                seenTime,
                labelsJson,
                annotationsJson);
        AlertMapper.AlertRecord record = alerts.lockByFingerprint(fingerprint);
        String outcome;
        if ("resolved".equals(status)) {
            alerts.resolved(record.id(), seenTime, labelsJson, annotationsJson);
            alerts.event(record.id(), status, stringify(item));
            if (record.ticketId() != null) {
                audit.history(
                        record.ticketId(),
                        1L,
                        "ALERT_RESOLVED",
                        "UNCHANGED",
                        "UNCHANGED",
                        "监控告警已恢复；工单保持原状态，需人工验证后关闭");
            }
            metrics.counter("opsagent.alert.resolved").increment();
            outcome = "RESOLVED_RECORDED";
        } else if (record.ticketId() == null) {
            Ticket ticket = tickets.createFromAlert(
                    alertName + " - " + text(annotations, "summary", "监控告警"),
                    description(alertName, serviceCode, labels, annotations),
                    priority(severity),
                    serviceCode);
            alerts.linkTicket(record.id(), ticket.getId(), seenTime, labelsJson, annotationsJson);
            alerts.event(record.id(), status, stringify(item));
            metrics.counter("opsagent.alert.created").increment();
            if (serviceCode.isBlank()) {
                metrics.counter("opsagent.alert.mapping.miss").increment();
            }
            outcome = "TICKET_CREATED";
            record = alerts.lockByFingerprint(fingerprint);
        } else {
            alerts.duplicateFiring(
                    record.id(), seenTime, severity, serviceCode, labelsJson, annotationsJson);
            alerts.event(record.id(), status, stringify(item));
            metrics.counter("opsagent.alert.deduplicated").increment();
            outcome = "DEDUPLICATED";
        }
        return Map.of(
                "fingerprint", fingerprint,
                "outcome", outcome,
                "ticketId", record.ticketId() == null ? 0L : record.ticketId());
    }

    List<Map<String, Object>> list(String status) {
        return alerts.list(status == null ? "" : status.trim().toLowerCase(Locale.ROOT));
    }

    private String description(
            String alertName,
            String serviceCode,
            JsonNode labels,
            JsonNode annotations) {
        return "告警名称："
                + alertName
                + "\n受影响服务："
                + (serviceCode.isBlank() ? "未映射" : serviceCode)
                + "\n摘要："
                + text(annotations, "summary", "无")
                + "\n详情："
                + text(annotations, "description", "无")
                + "\n标签："
                + stringify(labels);
    }

    private String priority(String severity) {
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical", "emergency" -> "URGENT";
            case "error", "high" -> "HIGH";
            case "info", "low" -> "LOW";
            default -> "MEDIUM";
        };
    }

    private LocalDateTime eventTime(JsonNode item, String status) {
        String key = "resolved".equals(status) ? "endsAt" : "startsAt";
        try {
            return LocalDateTime.ofInstant(Instant.parse(item.path(key).asText()), ZoneId.systemDefault());
        } catch (RuntimeException exception) {
            return LocalDateTime.now();
        }
    }

    private String fingerprint(String alertName, String serviceCode, JsonNode labels) {
        String source = alertName + "|" + serviceCode + "|" + stringify(labels);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算告警指纹", exception);
        }
    }

    private String text(JsonNode node, String key, String fallback) {
        String value = node.path(key).asText();
        return value == null || value.isBlank() ? fallback : value;
    }

    private String stringify(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("告警数据序列化失败", exception);
        }
    }
}
