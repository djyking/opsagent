package com.opsagent.rag;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排远程重排、结果对齐和超时异常时的 RRF 顺序降级。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
public class RerankService {
    private final RagProperties properties;
    private final BgeRemoteRerankProvider remote;
    private final NoOpRerankProvider noOp;
    private final MeterRegistry metrics;

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
        int topN = Math.min(Math.max(1, requestedTopN), properties.getRerankTopN());
        List<RerankDocument> documents = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            RetrievedChunk chunk = chunks.get(index);
            documents.add(new RerankDocument(
                    index,
                    chunk.chunkId(),
                    chunk.documentName(),
                    chunk.headingPath(),
                    chunk.content(),
                    null));
        }
        metrics.summary("rag.rerank.candidate.count").record(documents.size());
        Timer.Sample sample = Timer.start(metrics);
        try {
            boolean applied = remote.available();
            List<RerankResult> ranked = (applied ? remote : noOp)
                    .rerank(query, documents, topN);
            List<RetrievedChunk> results = ranked.stream()
                    .map(result -> chunks.get(result.candidateIndex())
                            .withRerankScore(applied ? result.score() : null))
                    .toList();
            metrics.counter("rag.rerank", "applied", Boolean.toString(applied)).increment();
            return new Outcome(results, applied, null);
        } catch (RuntimeException exception) {
            metrics.counter("rag.rerank.failure", "reason", "REMOTE_ERROR").increment();
            List<RerankResult> fallback = noOp.rerank(query, documents, topN);
            return new Outcome(
                    fallback.stream().map(item -> chunks.get(item.candidateIndex())).toList(),
                    false,
                    "REMOTE_ERROR");
        } finally {
            sample.stop(metrics.timer("rag.rerank.duration"));
        }
    }

    /**
     * 返回重排后的切片和是否发生真实重排或降级。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record Outcome(List<RetrievedChunk> chunks, boolean applied, String degradedReason) {
    }
}
