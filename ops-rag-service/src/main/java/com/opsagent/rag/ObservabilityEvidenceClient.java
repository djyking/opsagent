package com.opsagent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.core.ErrorCode;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.SecurityUsers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 当前用户的短期内部签名读取固定平台证据 API，边界失败不会降级为未经授权的证据。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class ObservabilityEvidenceClient {
    private static final int RESPONSE_LIMIT = 128 * 1024;
    private static final Pattern SECRET_KEY =
            Pattern.compile(
                    "(?i).*?(?:password|passwd|secret|token|authorization|cookie|api[-_]?key|credential).*?");
    private final ObjectMapper json;
    private final InternalActorTokens tokens;
    private final String platformUrl;

    @Value("${ops.rag.observability-evidence-timeout-ms:12000}")
    private long timeoutMs = 12000;

    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    ObservabilityEvidenceClient(
            ObjectMapper json,
            @Value("${OPS_AGENT_INTERNAL_SECRET:}") String secret,
            @Value("${OPS_PLATFORM_INTERNAL_URL:http://localhost:8105}") String platformUrl) {
        this.json = json;
        this.tokens = new InternalActorTokens(secret);
        this.platformUrl = platformUrl;
    }

    record Entry(
            String id,
            String source,
            String observedAt,
            String quality,
            String summary,
            JsonNode data) {}

    record Evidence(
            ObservabilityContext context,
            String evidenceBundleId,
            String collectedAt,
            String quality,
            List<String> gaps,
            List<Entry> entries,
            boolean available) {
        static Evidence unavailable(ObservabilityContext context, String reason) {
            return new Evidence(
                    context,
                    context.evidenceBundleId(),
                    null,
                    "UNAVAILABLE",
                    List.of(reason),
                    List.of(),
                    false);
        }
    }

    Evidence load(ObservabilityContext context) {
        var actor = SecurityUsers.current();
        if (!tokens.configured() || platformUrl == null || platformUrl.isBlank()) {
            return Evidence.unavailable(
                    context, "OBSERVABILITY_INTERNAL_CONNECTION_NOT_CONFIGURED");
        }
        try {
            String authorization =
                    tokens.issue(
                            "platform",
                            new InternalActorTokens.Context(
                                    actor.userId(),
                                    actor.username(),
                                    actor.roles(),
                                    "rag-evidence:" + UUID.randomUUID(),
                                    context.service(),
                                    Instant.now().plusSeconds(60)));
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            platformUrl.replaceAll("/+$", "")
                                                    + "/internal/platform/observability/evidence"))
                            .header("Authorization", "Bearer " + authorization)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofMillis(Math.max(1000, Math.min(12000, timeoutMs))))
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            json.writeValueAsString(context)))
                            .build();
            var future = http.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {
                response =
                        future.get(
                                Math.max(1000, Math.min(12000, timeoutMs)), TimeUnit.MILLISECONDS);
            } finally {
                if (!future.isDone()) future.cancel(true);
            }
            rejectBoundary(response.statusCode() * 100);
            if (response.statusCode() != 200)
                return Evidence.unavailable(context, "OBSERVABILITY_SOURCE_UNAVAILABLE");
            JsonNode envelope = json.readTree(response.body());
            int code = envelope.path("code").asInt(-1);
            rejectBoundary(code);
            if (code != 0) return Evidence.unavailable(context, "OBSERVABILITY_SOURCE_UNAVAILABLE");
            return parse(context, envelope.path("data"));
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            return Evidence.unavailable(context, "OBSERVABILITY_SOURCE_UNAVAILABLE");
        }
    }

    private void rejectBoundary(int code) {
        switch (code) {
            case 40000 -> throw new BusinessException(ErrorCode.VALIDATION, "观测范围或证据引用无效");
            case 40100 ->
                    throw new BusinessException(ErrorCode.UNAUTHENTICATED, "观测证据身份校验失败，请重新登录");
            case 40300 -> throw new BusinessException(ErrorCode.FORBIDDEN, "当前账号无权读取所选观测证据");
            case 40400 ->
                    throw new BusinessException(ErrorCode.NOT_FOUND, "所选服务或证据包不存在，不能使用历史内容替代");
            case 40900 -> throw new BusinessException(ErrorCode.CONFLICT, "证据包范围不一致或已失效，请重新选择观测范围");
            default -> {
                // Source failures are handled separately without suppressing authorization errors.
            }
        }
    }

    private Evidence parse(ObservabilityContext context, JsonNode data) {
        String id = data.path("evidenceBundleId").asText();
        try {
            UUID.fromString(id);
            Instant collected = Instant.parse(data.path("collectedAt").asText());
            if (collected.isAfter(Instant.now().plusSeconds(60)))
                throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            return Evidence.unavailable(context, "OBSERVABILITY_RESPONSE_INVALID");
        }
        if (!context.service().equals(data.path("service").asText())
                || !context.environment().equals(data.path("environment").asText())
                || !context.timeRange().equals(data.path("timeRange").asText())
                || context.evidenceBundleId() != null
                        && !context.evidenceBundleId().equalsIgnoreCase(id)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "观测证据返回范围不一致，已拒绝用于回答");
        }
        List<String> gaps = new ArrayList<>();
        for (JsonNode gap : data.path("gaps")) {
            if (gaps.size() >= 12) break;
            gaps.add(safeText(gap.isTextual() ? gap.asText() : gap.toString(), 200));
        }
        String quality = safeText(data.path("quality").asText("UNKNOWN"), 48);
        if (Instant.parse(data.path("collectedAt").asText())
                .isBefore(Instant.now().minusSeconds(300))) {
            quality = "STALE";
            gaps.add("EVIDENCE_BUNDLE_OLDER_THAN_5_MINUTES");
        }
        List<Entry> entries = new ArrayList<>();
        int budget = 10000;
        if (!data.path("entries").isArray()) gaps.add("EVIDENCE_ENTRIES_MISSING");
        for (JsonNode row : data.path("entries")) {
            if (entries.size() >= 16 || budget < 800) {
                gaps.add("EVIDENCE_CONTEXT_TRUNCATED");
                break;
            }
            String entryId = safeText(row.path("id").asText(), 160);
            if (entryId.isBlank()
                    || entries.stream().anyMatch(entry -> entry.id().equals(entryId))) {
                gaps.add("EVIDENCE_ID_MISSING_OR_DUPLICATE");
                continue;
            }
            JsonNode safeData = sanitize(row.path("data"), 0);
            String summary = safeText(row.path("summary").asText(), 700);
            if (safeData.toString().length() > 2500) {
                safeData =
                        JsonNodeFactory.instance
                                .objectNode()
                                .put("truncated", true)
                                .put("excerpt", safeData.toString().substring(0, 2400));
                gaps.add("EVIDENCE_ENTRY_TRUNCATED:" + entryId);
            }
            budget -= safeData.toString().length() + summary.length() + 350;
            String observedAt = timestamp(row.path("observedAt").asText());
            entries.add(
                    new Entry(
                            entryId,
                            safeText(row.path("source").asText("UNKNOWN"), 96),
                            observedAt,
                            observedAt == null
                                    ? "UNKNOWN"
                                    : safeText(row.path("quality").asText("UNKNOWN"), 48),
                            summary,
                            safeData));
        }
        return new Evidence(
                context,
                id,
                data.path("collectedAt").asText(),
                quality,
                List.copyOf(gaps),
                List.copyOf(entries),
                true);
    }

    private String timestamp(String value) {
        try {
            Instant at = Instant.parse(value);
            return at.isAfter(Instant.now().plusSeconds(60)) ? null : at.toString();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private JsonNode sanitize(JsonNode node, int depth) {
        if (node == null || node.isMissingNode() || node.isNull())
            return JsonNodeFactory.instance.nullNode();
        if (depth > 6) return JsonNodeFactory.instance.textNode("[深层内容已截断]");
        if (node.isObject()) {
            var safe = JsonNodeFactory.instance.objectNode();
            var fields = node.fields();
            int count = 0;
            while (fields.hasNext() && count++ < 40) {
                var field = fields.next();
                String name = safeText(field.getKey(), 96);
                safe.set(
                        name,
                        SECRET_KEY.matcher(name).matches()
                                ? JsonNodeFactory.instance.textNode("[REDACTED]")
                                : sanitize(field.getValue(), depth + 1));
            }
            if (fields.hasNext()) safe.put("fieldsTruncated", true);
            return safe;
        }
        if (node.isArray()) {
            var safe = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                if (safe.size() >= 30) {
                    safe.add("[更多条目已截断]");
                    break;
                }
                safe.add(sanitize(item, depth + 1));
            }
            return safe;
        }
        if (!node.isTextual()) return node;
        String value = node.asText();
        if (value.trim().startsWith("{") || value.trim().startsWith("[")) {
            try {
                return sanitize(json.readTree(value), depth + 1);
            } catch (Exception ignored) {
                // Plain text still passes through credential masking below.
            }
        }
        return JsonNodeFactory.instance.textNode(safeText(value, 1600));
    }

    static String safeText(String value, int maximum) {
        if (value == null) return "";
        String safe =
                value.replaceAll(
                                "(?i)(password|passwd|secret|token|api[-_]?key|authorization)"
                                        + "[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;\\\"']+",
                                "$1=[REDACTED]")
                        .replaceAll("(?i)Bearer\\s+[A-Za-z0-9_.-]+", "Bearer [REDACTED]")
                        .replaceAll("(?i)(https?://)[^/@\\s]+@", "$1[REDACTED]@")
                        .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
        return safe.length() <= maximum ? safe : safe.substring(0, maximum) + " [已截断]";
    }

    /**
     * @author heyu
     */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate =
                HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            size += buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (size > RESPONSE_LIMIT) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("Observability response too large"));
            } else delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
        }
    }
}
