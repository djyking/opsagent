package com.opsagent.rag;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对重排结果执行去重、邻居扩展、文档配额和 Token Budget 控制。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class ContextAssembler {
    private final RagProperties properties;
    private final MeterRegistry metrics;

    ContextAssembler(RagProperties properties, MeterRegistry metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    AssembledContext assemble(
            List<RetrievedChunk> ranked,
            List<RetrievedChunk> candidates,
            boolean singleDocumentScope) {
        List<ContextCandidate> expanded = expandNeighbors(ranked, candidates);
        List<ContextSource> sources = new ArrayList<>();
        Map<Long, Integer> documentCounts = new HashMap<>();
        Set<Long> seenChunks = new HashSet<>();
        List<String> seenContent = new ArrayList<>();
        int maximumPerDocument = singleDocumentScope
                ? Integer.MAX_VALUE : Math.max(1, properties.getMaxChunksPerDocument());
        int tokenBudget = Math.max(100, properties.getMaxContextTokens());
        int tokens = 0;
        int duplicates = 0;
        for (ContextCandidate candidate : expanded) {
            RetrievedChunk chunk = candidate.chunk();
            if (!seenChunks.add(chunk.chunkId()) || duplicate(chunk.content(), seenContent)) {
                duplicates++;
                continue;
            }
            int count = documentCounts.getOrDefault(chunk.documentId(), 0);
            if (count >= maximumPerDocument) {
                continue;
            }
            int chunkTokens = approximateTokens(chunk.content()) + 40;
            if (tokens + chunkTokens > tokenBudget) {
                break;
            }
            String sourceId = "S" + (sources.size() + 1);
            sources.add(new ContextSource(sourceId, chunk, candidate.neighbor(), candidate.parentChunkId()));
            seenContent.add(normalize(chunk.content()));
            documentCounts.put(chunk.documentId(), count + 1);
            tokens += chunkTokens;
        }
        metrics.summary("rag.context.tokens").record(tokens);
        metrics.summary("rag.context.chunk.count").record(sources.size());
        metrics.counter("rag.context.duplicate.removed").increment(duplicates);
        return new AssembledContext(render(sources), List.copyOf(sources), tokens, duplicates);
    }

    private List<ContextCandidate> expandNeighbors(
            List<RetrievedChunk> ranked, List<RetrievedChunk> candidates) {
        Map<String, RetrievedChunk> byIdentity = new LinkedHashMap<>();
        candidates.forEach(chunk -> byIdentity.put(identity(chunk), chunk));
        List<ContextCandidate> result = new ArrayList<>();
        int window = Math.max(0, properties.getNeighborWindow());
        for (RetrievedChunk chunk : ranked) {
            result.add(new ContextCandidate(chunk, false, null));
            for (int distance = 1; distance <= window; distance++) {
                addNeighbor(result, byIdentity, chunk, chunk.chunkIndex() - distance);
                addNeighbor(result, byIdentity, chunk, chunk.chunkIndex() + distance);
            }
        }
        return result;
    }

    private void addNeighbor(
            List<ContextCandidate> result,
            Map<String, RetrievedChunk> candidates,
            RetrievedChunk parent,
            int chunkIndex) {
        RetrievedChunk neighbor = candidates.get(parent.documentId() + ":" + chunkIndex);
        if (neighbor != null && sameHeading(parent, neighbor)) {
            result.add(new ContextCandidate(neighbor, true, parent.chunkId()));
        }
    }

    private boolean sameHeading(RetrievedChunk left, RetrievedChunk right) {
        return left.headingPath().isBlank()
                || right.headingPath().isBlank()
                || left.headingPath().equals(right.headingPath())
                || left.headingPath().startsWith(right.headingPath())
                || right.headingPath().startsWith(left.headingPath());
    }

    private boolean duplicate(String content, List<String> seen) {
        String normalized = normalize(content);
        if (normalized.isBlank()) {
            return true;
        }
        for (String previous : seen) {
            if (normalized.equals(previous)
                    || normalized.contains(previous)
                    || previous.contains(normalized)
                    || overlapRatio(normalized, previous) >= 0.8D) {
                return true;
            }
        }
        return false;
    }

    private double overlapRatio(String left, String right) {
        int maximum = Math.min(left.length(), right.length());
        for (int size = maximum; size >= Math.min(20, maximum); size--) {
            if (left.endsWith(right.substring(0, size))
                    || right.endsWith(left.substring(0, size))) {
                return (double) size / maximum;
            }
        }
        return 0.0D;
    }

    private int approximateTokens(String text) {
        int chinese = 0;
        int other = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                chinese++;
            } else if (!Character.isWhitespace(codePoint)) {
                other++;
            }
        }
        return Math.max(1, chinese + (other + 3) / 4);
    }

    private String render(List<ContextSource> sources) {
        if (sources.isEmpty()) {
            return "（没有检索到知识片段）";
        }
        StringBuilder result = new StringBuilder();
        for (ContextSource source : sources) {
            RetrievedChunk chunk = source.chunk();
            result.append('[').append(source.sourceId()).append("]\n")
                    .append("文档：").append(safe(chunk.documentName())).append('\n')
                    .append("章节：").append(safe(chunk.headingPath())).append('\n')
                    .append("页码：").append(pages(chunk)).append('\n')
                    .append("正文：").append(chunk.content()).append("\n\n");
        }
        return result.toString();
    }

    private String pages(RetrievedChunk chunk) {
        if (chunk.pageStart() == null) {
            return "未知";
        }
        if (chunk.pageEnd() == null || chunk.pageStart().equals(chunk.pageEnd())) {
            return chunk.pageStart().toString();
        }
        return chunk.pageStart() + "-" + chunk.pageEnd();
    }

    private String safe(String text) {
        return text == null || text.isBlank() ? "未知" : text.replace('\n', ' ');
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").trim();
    }

    private String identity(RetrievedChunk chunk) {
        return chunk.documentId() + ":" + chunk.chunkIndex();
    }

    /**
     * 保存最终 Prompt Context、可信来源映射和预算统计。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record AssembledContext(
            String text,
            List<ContextSource> sources,
            int tokenCount,
            int duplicateRemoved) {
    }

    /**
     * 关联 Source ID、检索切片及其邻居扩展来源。
     *
     * @author heyu
     * @since 2026/9/3
     */
    record ContextSource(
            String sourceId,
            RetrievedChunk chunk,
            boolean neighbor,
            Long parentChunkId) {
    }

    private record ContextCandidate(
            RetrievedChunk chunk,
            boolean neighbor,
            Long parentChunkId) {
    }
}
