package com.opsagent.knowledge;

import com.fasterxml.jackson.databind.JsonNode;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 调用 OpenAI Embeddings API，并保持文档和查询使用同一模型与维度。
 *
 * @author heyu
 * @since 2026/8/30
 */
@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final int MAXIMUM_ATTEMPTS = 3;
    private final VectorProperties properties;
    private final MeterRegistry metrics;

    OpenAiEmbeddingClient(VectorProperties properties, MeterRegistry metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public boolean configured() {
        return properties.configured();
    }

    @Override
    public String model() {
        return properties.getEmbeddingModel();
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    @Override
    public EmbeddingBatchResult embedBatch(List<String> texts) {
        long started = System.nanoTime();
        try {
            if (!configured()) {
                throw new IllegalStateException("Embedding 服务未配置");
            }
            Map<String, Object> body = Map.of(
                    "model", model(),
                    "input", texts,
                    "dimensions", properties.getDimensions(),
                    "encoding_format", "float");
            JsonNode response = request(body);
            List<JsonNode> rows = new ArrayList<>();
            response.path("data").forEach(rows::add);
            rows.sort(Comparator.comparingInt(row -> row.path("index").asInt()));
            List<List<Double>> vectors = rows.stream()
                    .map(row -> {
                        List<Double> vector = new ArrayList<>();
                        row.path("embedding").forEach(value -> vector.add(value.asDouble()));
                        return List.copyOf(vector);
                    })
                    .toList();
            if (vectors.size() != texts.size()
                    || vectors.stream()
                            .anyMatch(vector -> vector.size() != properties.getDimensions())) {
                throw new IllegalStateException("Embedding 返回数量或维度不正确");
            }
            EmbeddingBatchResult result = new EmbeddingBatchResult(
                    vectors,
                    model(),
                    properties.getDimensions(),
                    response.path("usage").path("total_tokens").asInt(0));
            metrics.counter("rag.embedding.request", "status", "success").increment();
            metrics.counter("rag.embedding.tokens").increment(result.tokenUsage());
            return result;
        } catch (RuntimeException exception) {
            metrics.counter("rag.embedding.request", "status", "failure").increment();
            LOG.warn("Embedding request failed: exceptionType={}, rootCauseType={}",
                    exception.getClass().getSimpleName(),
                    NestedExceptionUtils.getMostSpecificCause(exception).getClass().getSimpleName());
            throw exception;
        } finally {
            metrics.timer("rag.embedding.duration")
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private JsonNode request(Map<String, Object> body) {
        RestClient client = client();
        String base = properties.getEmbeddingBaseUrl();
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/embeddings";
        for (int attempt = 1; attempt <= MAXIMUM_ATTEMPTS; attempt++) {
            try {
                return client.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> headers.setBearerAuth(properties.getEmbeddingApiKey()))
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException exception) {
                int status = exception.getStatusCode().value();
                if ((status != 429 && status < 500) || attempt == MAXIMUM_ATTEMPTS) {
                    throw new IllegalStateException("Embedding API 调用失败，HTTP " + status, exception);
                }
                pause(attempt);
            } catch (ResourceAccessException exception) {
                if (attempt == MAXIMUM_ATTEMPTS) {
                    throw new IllegalStateException("Embedding API 响应超时", exception);
                }
                pause(attempt);
            }
        }
        throw new IllegalStateException("Embedding API 暂时不可用");
    }

    private RestClient client() {
        int seconds = Math.max(3, Math.min(properties.getTimeoutSeconds(), 120));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(seconds, 15)));
        factory.setReadTimeout(Duration.ofSeconds(seconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    private void pause(int attempt) {
        try {
            Thread.sleep(250L * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 请求已取消", exception);
        }
    }
}
