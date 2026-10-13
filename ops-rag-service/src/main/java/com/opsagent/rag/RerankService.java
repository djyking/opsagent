package com.opsagent.rag;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 编排远程重排、结果对齐和超时异常时的 RRF 顺序降级。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class RerankService {
    private static final Logger LOG = LoggerFactory.getLogger(RerankService.class);
    private final RagProperties properties;
    private final BgeRemoteRerankProvider remote;
    private final NoOpRerankProvider noOp;
    private final MeterRegistry metrics;
    private final Semaphore remoteSlot = new Semaphore(1);
    private volatile long retryAfterNanos;

    RerankService(
            RagProperties properties,
            BgeRemoteRerankProvider remote,
            NoOpRerankProvider noOp,
            MeterRegistry metrics) {
        this.properties = properties;
        this.remote = remote;
        this.noOp = noOp;
        this.metrics = metrics;
    }

    Outcome rerank(String query, List<RetrievedChunk> chunks, int requestedTopN) {
        if (chunks.isEmpty()) return new Outcome(List.of(), false, null);
        int topN = Math.min(Math.max(1, requestedTopN), properties.getRerankTopN());
        List<RerankDocument> documents = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            RetrievedChunk chunk = chunks.get(index);
            documents.add(
                    new RerankDocument(
                            index,
                            chunk.chunkId(),
                            chunk.documentName(),
                            chunk.headingPath(),
                            chunk.content(),
                            null));
        }
        metrics.summary("rag.rerank.candidate.count").record(documents.size());
        Timer.Sample sample = Timer.start(metrics);
        long started = System.nanoTime();
        boolean acquired = false;
        try {
            boolean applied = remote.available();
            if (applied) {
                if (retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0)
                    return fallback(query, chunks, documents, topN, "REMOTE_COOLDOWN");
                acquired = remoteSlot.tryAcquire();
                if (!acquired) return fallback(query, chunks, documents, topN, "REMOTE_BUSY");
                if (retryAfterNanos != 0 && System.nanoTime() - retryAfterNanos < 0)
                    return fallback(query, chunks, documents, topN, "REMOTE_COOLDOWN");
            }
            List<RerankResult> ranked = (applied ? remote : noOp).rerank(query, documents, topN);
            List<RetrievedChunk> results =
                    ranked.stream()
                            .map(
                                    result ->
                                            chunks.get(result.candidateIndex())
                                                    .withRerankScore(
                                                            applied ? result.score() : null))
                            .toList();
            metrics.counter("rag.rerank", "applied", Boolean.toString(applied)).increment();
            return new Outcome(results, applied, null);
        } catch (RuntimeException exception) {
            // Cancelling a client request does not prove that sidecar inference stopped.
            // A cooldown prevents rapid questions from repeatedly submitting expensive work.
            if (acquired)
                retryAfterNanos =
                        System.nanoTime()
                                + TimeUnit.SECONDS.toNanos(
                                        properties.getRerankFailureCooldownSeconds());
            // Do not log exception messages or stack traces: HTTP failures can embed request or
            // response bodies.
            LOG.warn(
                    "Rerank fallback: exceptionType={}, rootCauseType={}, durationMs={},"
                            + " candidateCount={}",
                    exception.getClass().getSimpleName(),
                    NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000L,
                    documents.size());
            return fallback(query, chunks, documents, topN, "REMOTE_ERROR");
        } finally {
            if (acquired) remoteSlot.release();
            sample.stop(metrics.timer("rag.rerank.duration"));
        }
    }

    private Outcome fallback(
            String query,
            List<RetrievedChunk> chunks,
            List<RerankDocument> documents,
            int topN,
            String reason) {
        metrics.counter("rag.rerank.failure", "reason", reason).increment();
        return new Outcome(
                noOp.rerank(query, documents, topN).stream()
                        .map(item -> chunks.get(item.candidateIndex()).withRerankScore(null))
                        .toList(),
                false,
                reason);
    }

    /**
     * 返回重排后的切片和是否发生真实重排或降级。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Outcome(List<RetrievedChunk> chunks, boolean applied, String degradedReason) {}
}
