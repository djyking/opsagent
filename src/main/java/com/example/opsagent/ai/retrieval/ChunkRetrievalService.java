package com.example.opsagent.ai.retrieval;

import com.example.opsagent.ai.config.AiProperties;
import com.example.opsagent.document.dao.DocumentChunkDao;
import com.example.opsagent.document.entity.DocumentChunk;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在有限工单候选 Chunk 中执行中英文关键词评分并返回 Top K。
 *
 * @author heyu
 * @since 2026/8/16
 */
@Service
@RequiredArgsConstructor
public class ChunkRetrievalService {

    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9_.-]{2,}");

    private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]+");

    private final DocumentChunkDao chunkDao;

    private final AiProperties properties;

    public List<ScoredChunk> retrieve(
            Long ticketId, Long documentId, String question, Integer requestedTopK) {
        int topK = requestedTopK == null ? properties.getTopK() : requestedTopK;
        if (topK < 1 || topK > 10) {
            throw new IllegalArgumentException("topK 必须在 1 到 10 之间");
        }
        if (properties.getCandidateLimit() < topK || properties.getCandidateLimit() > 1000) {
            throw new IllegalStateException("AI candidate-limit 配置不合法");
        }
        Set<String> terms = terms(question);
        List<DocumentChunk> candidates =
                chunkDao.selectCandidates(ticketId, documentId, properties.getCandidateLimit());
        return candidates.stream()
                .map(chunk -> new ScoredChunk(chunk, score(chunk.getContent(), terms)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(
                        Comparator.comparingDouble(ScoredChunk::score)
                                .reversed()
                                .thenComparing(candidate -> candidate.chunk().getDocumentId())
                                .thenComparing(candidate -> candidate.chunk().getChunkIndex()))
                .limit(topK)
                .toList();
    }

    private Set<String> terms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        Matcher latinMatcher = LATIN_TERM.matcher(normalized);
        while (latinMatcher.find()) {
            result.add(latinMatcher.group());
        }
        Matcher hanMatcher = HAN_SEQUENCE.matcher(normalized);
        while (hanMatcher.find()) {
            String sequence = hanMatcher.group();
            if (sequence.length() == 1) {
                result.add(sequence);
            } else {
                for (int index = 0; index < sequence.length() - 1; index++) {
                    result.add(sequence.substring(index, index + 2));
                }
            }
        }
        if (result.isEmpty()) {
            result.add(normalized.trim());
        }
        return result;
    }

    private double score(String content, Set<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        double score = 0;
        for (String term : new ArrayList<>(terms)) {
            int from = 0;
            int occurrences = 0;
            while ((from = normalized.indexOf(term, from)) >= 0) {
                occurrences++;
                from += Math.max(1, term.length());
            }
            score += occurrences * Math.max(1, term.length() - 1);
        }
        return score;
    }
}
