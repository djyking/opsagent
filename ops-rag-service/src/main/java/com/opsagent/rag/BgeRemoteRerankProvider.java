package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * 调用可选的 BGE Reranker 推理边车并校验候选序号。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class BgeRemoteRerankProvider implements RerankProvider {
    private final RagProperties properties;
    private final HttpClient client;
    private final ObjectMapper json = new ObjectMapper();

    BgeRemoteRerankProvider(RagProperties properties) {
        this.properties = properties;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                        .build();
    }

    @Override
    public boolean available() {
        return properties.isRerankEnabled();
    }

    @Override
    public List<RerankResult> rerank(String query, List<RerankDocument> candidates, int topN) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        JsonNode response;
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(
                                            properties.getRerankBaseUrl().replaceAll("/+$", "")
                                                    + "/rerank"))
                            .timeout(Duration.ofSeconds(timeoutSeconds()))
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofByteArray(
                                            json.writeValueAsBytes(
                                                    Map.of(
                                                            "query", query,
                                                            "documents",
                                                                    candidates.stream()
                                                                            .map(
                                                                                    RerankDocument
                                                                                            ::passage)
                                                                            .toList(),
                                                            "top_n", topN))))
                            .build();
            pending = client.sendAsync(request, info -> new BoundedBody());
            // One deadline covers connection, headers and the entire body, including trickled
            // bytes.
            HttpResponse<byte[]> result = pending.get(timeoutSeconds(), TimeUnit.SECONDS);
            if (result.statusCode() < 200 || result.statusCode() >= 300)
                throw new IllegalStateException("Rerank HTTP failure");
            response = json.readTree(result.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rerank interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Rerank unavailable", exception);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
        if (response == null
                || !response.path("results").isArray()
                || response.path("results").isEmpty())
            throw new IllegalStateException("Rerank empty result");
        List<RerankResult> results = new ArrayList<>();
        var seen = new HashSet<Integer>();
        int rank = 1;
        for (JsonNode item : response.path("results")) {
            int index = item.path("index").asInt(-1);
            if (index < 0
                    || index >= candidates.size()
                    || !seen.add(index)
                    || !item.path("score").isNumber()
                    || !Double.isFinite(item.path("score").asDouble())) {
                throw new IllegalStateException("Rerank 返回了无效 candidateIndex");
            }
            results.add(new RerankResult(index, item.path("score").asDouble(), rank++));
        }
        return List.copyOf(results);
    }

    private int timeoutSeconds() {
        return Math.max(1, Math.min(5, properties.getRerankTimeoutSeconds()));
    }

    /**
     * Limits an optional sidecar response independently of Content-Length.
     *
     * @author heyu
     * @since 2026/9/3
     */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 262144) {
                    subscription.cancel();
                    result.completeExceptionally(
                            new IllegalStateException("Rerank response too large"));
                    return;
                }
                byte[] next = new byte[buffer.remaining()];
                buffer.get(next);
                bytes.writeBytes(next);
            }
            subscription.request(1);
        }

        public void onError(Throwable cause) {
            result.completeExceptionally(cause);
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
