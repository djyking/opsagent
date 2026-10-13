package com.opsagent.platform;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 有界后台保存低频观测摘要，不占用既有巡检/执行器调度线程。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class ObservabilityHistoryRecorder {
    private final boolean enabled;
    private final ObservabilityV3Service service;
    private final ObservabilityV3Repository repository;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "observability-history");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final AtomicBoolean running = new AtomicBoolean();
    private int instanceCursor;

    ObservabilityHistoryRecorder(
            @Value("${OPS_OBSERVABILITY_HISTORY_ENABLED:false}") boolean enabled,
            ObservabilityV3Service service,
            ObservabilityV3Repository repository) {
        this.enabled = enabled;
        this.service = service;
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    void tick() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        worker.submit(
                () -> {
                    try {
                        for (String env : List.of("ALL", "PROD", "DEMO")) {
                            try {
                                repository.snapshot(service.topology(env, "5m", "HYBRID"));
                            } catch (RuntimeException unavailable) {
                                // No synthetic last-good snapshot: the missing minute remains a
                                // visible gap.
                            }
                        }
                        repository.cleanup(Instant.now().minusSeconds(72 * 3600));
                        // One real service per minute bounds Tempo work independently of UI reads.
                        var services =
                                List.of(
                                        "ops-demo-order-service",
                                        "ops-gateway",
                                        "ops-platform-service",
                                        "ops-agent-service",
                                        "ops-rag-service");
                        try {
                            service.instances(
                                    services.get(instanceCursor++ % services.size()), "30m");
                        } catch (RuntimeException unavailable) {
                            // Retain actual first/last seen; absence is not process termination.
                        }
                    } finally {
                        running.set(false);
                    }
                });
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
    }
}
