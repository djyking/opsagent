package com.opsagent.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 公开问答在重排与邻居扩展前按片段正文核对明确产品及问题主题。
 *
 * @author heyu
 * @since 2026/9/3
 */
final class PublicKnowledgeRelevance {
    private static final Pattern PRODUCT =
            Pattern.compile(
                    "(?<![a-z0-9_])(?:redis|rabbitmq|nacos|mysql|mariadb|docker|elasticsearch|"
                            + "qdrant|kafka|postgresql|postgres|mongodb|nginx|prometheus|promql|"
                            + "kubernetes|k8s)(?![a-z0-9_])");
    private static final Pattern SENTINEL = Pattern.compile("(?<![a-z0-9_])sentinel(?![a-z0-9_])");
    private static final Pattern CONNECTION_QUESTION =
            Pattern.compile("连接|連線|(?i:connection|connectivity|connect\\b|socket)");
    private static final Pattern CONNECTION =
            Pattern.compile(
                    "连接|連線|超时|握手|(?i:timeout|timed["
                        + " -]?out|connect|socket|maxclients|tcp|backlog|keepalive)");
    private static final Pattern HIT_RATE_QUESTION =
            Pattern.compile("命中率|(?i:hit[ _-]?(?:rate|ratio)|keyspace_hits|keyspace_misses)");
    private static final Pattern HIT_RATE_CONTENT =
            Pattern.compile(
                    "命中|过期|淘汰|回源|缓存雪崩|(?i:hit["
                        + " _-]?(?:rate|ratio)|keyspace_hits|keyspace_misses|expir|evict)");

    private PublicKnowledgeRelevance() {}

    static List<RetrievedChunk> filter(String question, List<RetrievedChunk> chunks) {
        String questionBody = AssistantIntent.body(question);
        Set<String> requested = products(questionBody, false);
        if (requested.isEmpty()) return chunks;
        // 多产品问题可能比较不同机制，不能把一个产品的主题强加给另一产品。
        boolean singleProduct = requested.size() == 1;
        boolean connection = singleProduct && CONNECTION_QUESTION.matcher(questionBody).find();
        boolean hitRate = singleProduct && HIT_RATE_QUESTION.matcher(questionBody).find();
        return chunks.stream()
                .filter(chunk -> matchesProducts(requested, chunk))
                .filter(
                        chunk ->
                                !connection && !hitRate
                                        || connection && CONNECTION.matcher(chunk.content()).find()
                                        || hitRate
                                                && HIT_RATE_CONTENT.matcher(chunk.content()).find())
                .toList();
    }

    private static boolean matchesProducts(Set<String> requested, RetrievedChunk chunk) {
        if (requested.isEmpty()) return true;
        Set<String> title = products(chunk.documentName(), false);
        Set<String> heading = products(chunk.headingPath(), title.equals(Set.of("redis")));
        boolean redisContext =
                heading.equals(Set.of("redis"))
                        || heading.isEmpty() && title.equals(Set.of("redis"));
        Set<String> body = products(chunk.content(), redisContext);
        // 正文明示的产品优先，混合文档标题不能把其他产品的片段变成相关证据。
        Set<String> actual =
                !body.isEmpty()
                        ? body
                        : !heading.isEmpty() ? heading : title.size() == 1 ? title : Set.of();
        return actual.stream().anyMatch(requested::contains);
    }

    private static Set<String> products(String value, boolean redisContext) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        var matches = PRODUCT.matcher(text);
        while (matches.find()) {
            result.add(
                    switch (matches.group()) {
                        case "postgres" -> "postgresql";
                        case "promql" -> "prometheus";
                        case "k8s" -> "kubernetes";
                        default -> matches.group();
                    });
        }
        if (SENTINEL.matcher(text).find()) {
            boolean alibaba =
                    text.contains("alibaba") || text.contains("flowrule") || text.contains("限流");
            if (!alibaba && (result.contains("redis") || redisContext)) result.add("redis");
            else result.add("sentinel");
        }
        return Set.copyOf(result);
    }
}
