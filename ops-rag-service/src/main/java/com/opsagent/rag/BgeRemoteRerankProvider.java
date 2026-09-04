package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调用可选的 BGE Reranker 推理边车并校验候选序号。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class BgeRemoteRerankProvider implements RerankProvider {
    private final RagProperties properties;
    private final RestClient client;

    BgeRemoteRerankProvider(RagProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getRerankTimeoutSeconds() * 1000);
        requestFactory.setReadTimeout(properties.getRerankTimeoutSeconds() * 1000);
        this.client = RestClient.builder()
                .baseUrl(properties.getRerankBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public boolean available() {
        return properties.isRerankEnabled();
    }

    @Override
    public List<RerankResult> rerank(
            String query, List<RerankDocument> candidates, int topN) {
        JsonNode response = client.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "query", query,
                        "documents", candidates.stream().map(RerankDocument::passage).toList(),
                        "top_n", topN))
                .retrieve()
                .body(JsonNode.class);
        List<RerankResult> results = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : response.path("results")) {
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= candidates.size()) {
                throw new IllegalStateException("Rerank 返回了无效 candidateIndex");
            }
            results.add(new RerankResult(index, item.path("score").asDouble(), rank++));
        }
        return List.copyOf(results);
    }
}
