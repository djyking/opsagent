package com.opsagent.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识不足后按公开组件主题读取固定官方页面，绝不把内部问题、身份或上下文发往公网。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Service
class OfficialKnowledgeFallback {
    private final OfficialDocumentationClient official;

    OfficialKnowledgeFallback(OfficialDocumentationClient official) {
        this.official = official;
    }

    String topic(String question) {
        String text = question.toLowerCase(Locale.ROOT);
        if (text.contains("redis") && text.contains("sentinel")) return "REDIS_SENTINEL";
        if (text.contains("rabbitmq")) return "RABBITMQ_ALARMS";
        if (text.contains("nacos")) return "NACOS_CONFIGURATION";
        if (text.contains("sentinel")) return "SENTINEL_FLOW_CONTROL";
        if (text.contains("redis")) return "REDIS_CONNECTION";
        if (text.contains("mysql")) return "MYSQL_CONNECTIONS";
        if (text.contains("prometheus") || text.contains("promql")) return "PROMETHEUS_QUERY";
        if (text.contains("docker")) return "DOCKER_HEALTH";
        if (text.contains("kubernetes") || text.contains("k8s"))
            return "KUBERNETES_TROUBLESHOOTING";
        if (text.contains("elasticsearch")) return "ELASTICSEARCH_RETRIEVAL";
        return null;
    }

    boolean relevantKnowledge(String question, List<RetrievedChunk> chunks) {
        return !PublicKnowledgeRelevance.filter(question, chunks).isEmpty();
    }

    Result load(String question) {
        String topic = topic(question);
        if (topic == null) return new Result(List.of(), null, null, null, "UNSUPPORTED_TOPIC");
        var result = official.search(topic);
        List<RetrievedChunk> chunks = new ArrayList<>();
        if (result.path("status").asText().equals("AVAILABLE")) {
            int index = 0;
            for (var citation : result.path("citations")) {
                index++;
                chunks.add(
                        RetrievedChunk.from(
                                Map.of(
                                        "chunkId",
                                        index,
                                        "documentId",
                                        0,
                                        "chunkIndex",
                                        index,
                                        "documentName",
                                        citation.path("title").asText(),
                                        "content",
                                        citation.path("excerpt").asText(),
                                        "channels",
                                        List.of("OFFICIAL_WEB"),
                                        "retrievalMode",
                                        "OFFICIAL_WEB")));
            }
        }
        List<RetrievedChunk> relevant = PublicKnowledgeRelevance.filter(question, chunks);
        return new Result(
                relevant,
                result.path("sourceUrl").asText(),
                result.path("sourceTitle").asText(),
                result.path("fetchedAt").asText(null),
                relevant.isEmpty() ? "OFFICIAL_SOURCE_UNAVAILABLE" : null);
    }

    /**
     * @author heyu
     */
    record Result(
            List<RetrievedChunk> chunks,
            String url,
            String title,
            String fetchedAt,
            String reason) {
        RagService.Source source(ContextAssembler.ContextSource context) {
            return new RagService.Source(
                    0,
                    0,
                    context.chunk().chunkIndex(),
                    title,
                    null,
                    null,
                    null,
                    0,
                    context.sourceId(),
                    "官方公开参考；不是本机运行事实",
                    null,
                    null,
                    null,
                    null,
                    Set.of("OFFICIAL_WEB"),
                    false,
                    null,
                    "OFFICIAL_WEB",
                    url,
                    null,
                    fetchedAt);
        }
    }
}
