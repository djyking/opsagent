package com.opsagent.ticket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理 SLA 生命周期，并通过 Redis 锁和数据库唯一键保证扫描幂等。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class SlaService {
    private static final String SCANNER_LOCK = "ops:lock:sla:scanner";
    private final SlaMapper mapper;
    private final OutboxMapper outbox;
    private final RedisDistributedLock lock;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final TransactionTemplate transactions;
    private final boolean scannerEnabled;

    SlaService(
            SlaMapper mapper,
            OutboxMapper outbox,
            RedisDistributedLock lock,
            ObjectMapper json,
            MeterRegistry metrics,
            TransactionTemplate transactions,
            @Value("${ops.sla.scanner-enabled:true}") boolean scannerEnabled) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.lock = lock;
        this.json = json;
        this.metrics = metrics;
        this.transactions = transactions;
        this.scannerEnabled = scannerEnabled;
    }

    void start(long ticketId, String priority) {
        mapper.start(ticketId, priority);
    }

    void responseCompleted(long ticketId) {
        mapper.responseCompleted(ticketId);
    }

    void resolutionCompleted(long ticketId) {
        mapper.resolutionCompleted(ticketId);
    }

    Map<String, Object> detail(long ticketId) {
        return mapper.detail(ticketId);
    }

    List<Map<String, Object>> overview() {
        return mapper.overview(200);
    }

    @Scheduled(fixedDelayString = "${ops.sla.scan-delay-millis:10000}")
    public void scan() {
        if (!scannerEnabled) {
            return;
        }
        String token = lock.tryLock(SCANNER_LOCK, Duration.ofSeconds(8));
        if (token == null) {
            return;
        }
        try {
            mapper.due(100).forEach(
                    candidate -> transactions.executeWithoutResult(
                            status -> evaluate(candidate)));
        } finally {
            lock.unlock(SCANNER_LOCK, token);
        }
    }

    void evaluate(SlaMapper.SlaCandidate candidate) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime responseWarning = candidate.createTime().plusMinutes(
                Math.max(1L, candidate.responseMinutes() * candidate.warningPercent() / 100L));
        LocalDateTime resolutionWarning = candidate.createTime().plusMinutes(
                Math.max(1L, candidate.resolutionMinutes() * candidate.warningPercent() / 100L));
        if ("RUNNING".equals(candidate.responseStatus()) && !now.isBefore(responseWarning)) {
            emit(candidate, "RESPONSE_WARNING", 0, "响应 SLA 即将到期");
        }
        if ("RUNNING".equals(candidate.responseStatus())
                && !now.isBefore(candidate.responseDeadline())) {
            emit(candidate, "RESPONSE_BREACH", 0, "响应 SLA 已超时");
        }
        if (!now.isBefore(resolutionWarning)) {
            emit(candidate, "RESOLUTION_WARNING", 0, "解决 SLA 即将到期");
        }
        if (!now.isBefore(candidate.resolutionDeadline())) {
            emit(candidate, "RESOLUTION_BREACH", 0, "解决 SLA 已超时");
        }
        if (!now.isBefore(candidate.resolutionDeadline().plusMinutes(5))) {
            emit(candidate, "ESCALATION", 1, "一级升级：通知 PRIMARY 值班人员");
        }
        if (!now.isBefore(candidate.resolutionDeadline().plusMinutes(15))) {
            emit(candidate, "ESCALATION", 2, "二级升级：通知 SECONDARY 值班人员和管理员");
        }
    }

    private void emit(
            SlaMapper.SlaCandidate candidate,
            String eventType,
            int level,
            String detail) {
        if (mapper.addEvent(candidate.id(), candidate.ticketId(), eventType, level, detail) != 1) {
            return;
        }
        mapper.markEvent(candidate.id(), eventType, level);
        String metric = "ESCALATION".equals(eventType)
                ? "opsagent.sla.escalation"
                : eventType.endsWith("BREACH")
                        ? "opsagent.sla.breach"
                        : "opsagent.sla.warning";
        metrics.counter(metric).increment();
        String payload = payload(candidate.ticketId(), eventType, level, detail);
        outbox.add(UUID.randomUUID().toString(), candidate.ticketId(), "sla." + eventType.toLowerCase(), payload);
    }

    private String payload(long ticketId, String eventType, int level, String detail) {
        try {
            return json.writeValueAsString(Map.of(
                    "ticketId", ticketId,
                    "eventType", eventType,
                    "escalationLevel", level,
                    "detail", detail));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SLA 事件序列化失败", exception);
        }
    }
}
