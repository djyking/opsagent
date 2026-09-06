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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

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
    private final AlertEpisodeMapper episodes;
    private final AlertProvenanceResolver provenance;

    AlertmanagerService(
            AlertMapper alerts,
            TicketService tickets,
            TicketAuditMapper audit,
            ObjectMapper json,
            MeterRegistry metrics,
            AlertEpisodeMapper episodes,
            AlertProvenanceResolver provenance,
            @Value("${ops.alertmanager.enabled:false}") boolean enabled,
            @Value("${ops.alertmanager.webhook-token:}") String webhookToken) {
        this.alerts = alerts;
        this.tickets = tickets;
        this.audit = audit;
        this.json = json;
        this.metrics = metrics;
        this.episodes = episodes;
        this.provenance = provenance;
        this.enabled = enabled;
        this.webhookToken = webhookToken;
    }

    void authenticate(String authorization) {
        metrics.counter("opsagent.alert.webhook").increment();
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "告警接入未启用");
        }
        String actual =
                authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring(7)
                        : "";
        boolean valid =
                !webhookToken.isBlank()
                        && MessageDigest.isEqual(
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
        if (!items.isArray() || items.size() > 100 || stringify(payload).length() > 262144) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alerts 必须是最多 100 项的有界数组");
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
        if (!labels.isObject()
                || alertName.length() > 128
                || serviceCode.length() > 64
                || severity.length() > 32
                || fingerprint.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警标签无效或超长");
        }
        if (labels.size() > 64
                || !annotations.isObject()
                || annotations.toString().length() > 16384) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警元数据无效或超长");
        }
        labels.fields()
                .forEachRemaining(
                        entry -> {
                            if (!entry.getValue().isTextual()
                                    || entry.getKey().length() > 128
                                    || entry.getValue().asText().length() > 2048) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "告警标签必须是有界文本");
                            }
                        });
        Instant startsAt = parseTime(item, "startsAt");
        Instant eventAt = "resolved".equals(status) ? parseTime(item, "endsAt") : startsAt;
        if (eventAt.isBefore(startsAt))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "恢复时间早于故障开始时间");
        LocalDateTime startTime =
                LocalDateTime.ofInstant(startsAt, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        LocalDateTime seenTime =
                LocalDateTime.ofInstant(eventAt, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String episodeId = digest(fingerprint + "|" + startsAt);
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
        episodes.insert(episodeId, record.id(), fingerprint, startTime);
        AlertEpisodeMapper.Episode episode = episodes.lock(episodeId);
        String deliveryKey = digest(episodeId + "|" + status + "|" + eventAt);
        if (episodes.delivery(deliveryKey, episodeId, status, seenTime) == 0) {
            metrics.counter("opsagent.alert.deduplicated").increment();
            return result(fingerprint, episodeId, "DEDUPLICATED", episode.ticketId());
        }
        boolean latest = !startTime.isBefore(episodes.latestStart(record.id()));
        if (seenTime.isBefore(episode.lastEventTime())
                || ("resolved".equals(episode.currentStatus()) && "firing".equals(status))) {
            return result(fingerprint, episodeId, "STALE_IGNORED", episode.ticketId());
        }
        String outcome;
        if ("resolved".equals(status)) {
            episodes.observed(episodeId, status, seenTime);
            if (latest) alerts.resolved(record.id(), seenTime, labelsJson, annotationsJson);
            alerts.event(record.id(), status, stringify(item));
            if (episode.ticketId() != null && !"resolved".equals(episode.currentStatus())) {
                audit.history(
                        episode.ticketId(),
                        0L,
                        "ALERT_RESOLVED",
                        "UNCHANGED",
                        "UNCHANGED",
                        "监控告警已恢复；工单保持原状态，需人工验证后关闭");
            }
            metrics.counter("opsagent.alert.resolved").increment();
            outcome = "RESOLVED_RECORDED";
        } else if (episode.ticketId() == null && latest) {
            TicketService.AlertProvenance origin =
                    provenance.resolve(serviceCode, startsAt, episodeId);
            Ticket ticket =
                    tickets.createFromAlert(
                            alertName + " - " + text(annotations, "summary", "监控告警"),
                            description(alertName, serviceCode, labels, annotations),
                            priority(severity),
                            serviceCode,
                            episodeId,
                            origin);
            episodes.link(
                    episodeId,
                    ticket.getId(),
                    origin.incidentId(),
                    origin.ownerActorId(),
                    origin.isolated() ? "ISOLATED" : "CORE");
            episodes.observed(episodeId, status, seenTime);
            alerts.linkTicket(record.id(), ticket.getId(), seenTime, labelsJson, annotationsJson);
            alerts.event(record.id(), status, stringify(item));
            metrics.counter("opsagent.alert.created").increment();
            if (serviceCode.isBlank()) {
                metrics.counter("opsagent.alert.mapping.miss").increment();
            }
            outcome = "TICKET_CREATED";
            episode = episodes.lock(episodeId);
        } else if (!latest) {
            outcome = "STALE_IGNORED";
        } else {
            episodes.observed(episodeId, status, seenTime);
            alerts.duplicateFiring(
                    record.id(), seenTime, severity, serviceCode, labelsJson, annotationsJson);
            alerts.event(record.id(), status, stringify(item));
            metrics.counter("opsagent.alert.deduplicated").increment();
            outcome = "DEDUPLICATED";
        }
        return result(fingerprint, episodeId, outcome, episode.ticketId());
    }

    private Map<String, Object> result(
            String fingerprint, String episodeId, String outcome, Long ticketId) {
        return Map.of(
                "fingerprint",
                fingerprint,
                "episodeId",
                episodeId,
                "outcome",
                outcome,
                "ticketId",
                ticketId == null ? 0L : ticketId);
    }

    List<Map<String, Object>> list(String status) {
        String filter = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        var actor = com.opsagent.common.security.SecurityUsers.current();
        return actor.roles().contains("ADMIN") || actor.roles().contains("OPS")
                ? alerts.list(filter)
                : alerts.listVisible(filter, actor.userId());
    }

    private String description(
            String alertName, String serviceCode, JsonNode labels, JsonNode annotations) {
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

    private Instant parseTime(JsonNode item, String key) {
        try {
            Instant value = Instant.parse(item.path(key).asText());
            if (value.isAfter(Instant.now().plusSeconds(120))
                    || value.isBefore(Instant.parse("2000-01-01T00:00:00Z"))) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 必须是有效的告警事件时间");
        }
    }

    private String fingerprint(String alertName, String serviceCode, JsonNode labels) {
        TreeMap<String, String> canonical = new TreeMap<>();
        labels.fields()
                .forEachRemaining(
                        entry -> canonical.put(entry.getKey(), entry.getValue().asText()));
        String source =
                alertName + "|" + serviceCode + "|" + stringify(json.valueToTree(canonical));
        return digest(source);
    }

    private String digest(String source) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
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
