package com.opsagent.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * 以公开验收实际资料和产品边界验证入选资格，不调用模型。
 *
 * @author heyu
 * @since 2026/9/3
 */
class PublicKnowledgeRelevanceTest {
    private final RetrievedChunk mysql =
            chunk(
                    1001,
                    "MySQL连接池排障手册.md",
                    "",
                    "数据库连接超时时，先检查 Hikari active、idle、pending 指标，再核对 MySQL max_connections 与慢查询。");
    private final RetrievedChunk redis =
            chunk(
                    1003,
                    "Redis与Nacos排障手册.md",
                    "",
                    "Redis 命中率下降时，核对 key 过期是否集中、淘汰策略、内存碎片率与回源 QPS，避免缓存雪崩。");
    private final RetrievedChunk nacos =
            chunk(
                    1004,
                    "Redis与Nacos排障手册.md",
                    "",
                    "Nacos 配置不一致时，确认 dataId、group、namespace 和客户端长轮询日志。");

    @Test
    void originalRedisTimeoutQuestionCannotUseMysqlOrCacheHitMaterial() {
        assertThat(
                        PublicKnowledgeRelevance.filter(
                                "Redis连接超时排查有哪些建议？请优先根据知识库用两句话回答，并标出引用。",
                                List.of(mysql, redis, nacos)))
                .isEmpty();
    }

    @Test
    void originalHitRateQuestionKeepsOnlyItsActualRedisSource() {
        assertThat(
                        PublicKnowledgeRelevance.filter(
                                "Redis 命中率下降时应该核对哪些项目？请根据知识库用一句话回答，并标出引用。",
                                List.of(mysql, redis, nacos)))
                .containsExactly(redis);
    }

    @Test
    void singleProductTitleMaySupplyProductWithoutRepeatedBodyName() {
        var step = chunk(1, "Redis连接排障.md", "", "检查客户端连接池、maxclients 与 tcp-keepalive。");
        assertThat(PublicKnowledgeRelevance.filter("Redis连接超时怎么办", List.of(step, mysql)))
                .containsExactly(step);
    }

    @Test
    void mixedTitleDoesNotAuthorizeAmbiguousOrOtherProductBody() {
        var ambiguous = chunk(1, "Redis与Nacos排障.md", "", "检查 timeout 参数后再重试连接。");
        assertThat(PublicKnowledgeRelevance.filter("Redis连接超时怎么办", List.of(ambiguous, nacos)))
                .isEmpty();
        var section = chunk(2, "Redis与Nacos排障.md", "Redis / 连接", "检查 timeout 参数和客户端连接池。");
        assertThat(PublicKnowledgeRelevance.filter("Redis连接超时怎么办", List.of(section)))
                .containsExactly(section);
    }

    @Test
    void comparisonKeepsBothProductsWithoutImposingOneTopicOnTheOther() {
        assertThat(
                        PublicKnowledgeRelevance.filter(
                                "比较 Redis 命中率下降与 MySQL 连接超时的处理方式", List.of(mysql, redis, nacos)))
                .containsExactly(mysql, redis);
    }

    @Test
    void timeoutAloneDoesNotTurnConfigurationQuestionIntoConnectionQuestion() {
        assertThat(PublicKnowledgeRelevance.filter("Nacos配置发布超时如何排查", List.of(nacos, redis)))
                .containsExactly(nacos);
    }

    @Test
    void productAliasesAreMatchedAtIdentifierBoundaries() {
        var kubernetes = chunk(1, "Kubernetes故障手册.md", "", "核对 Pod 的事件与健康检查。");
        var unrelated = chunk(2, "MySQL运维手册.md", "", "rediscovery 操作后检查连接状态。");
        assertThat(PublicKnowledgeRelevance.filter("k8s如何检查健康", List.of(kubernetes, mysql)))
                .containsExactly(kubernetes);
        assertThat(PublicKnowledgeRelevance.filter("Redis连接超时怎么办", List.of(unrelated))).isEmpty();
    }

    @Test
    void redisSentinelAndAlibabaSentinelAreNotInterchanged() {
        var failover = chunk(1, "Redis Sentinel手册.md", "", "Sentinel 监测主节点并执行故障切换。");
        var flow = chunk(2, "Alibaba Sentinel手册.md", "", "FlowRule 配置用于限流。");
        assertThat(PublicKnowledgeRelevance.filter("Redis Sentinel如何切换", List.of(failover, flow)))
                .containsExactly(failover);
        assertThat(PublicKnowledgeRelevance.filter("Sentinel如何限流", List.of(failover, flow)))
                .containsExactly(flow);
    }

    @Test
    void broadQuestionWithoutAnExplicitProductKeepsItsAuthorizedCandidates() {
        assertThat(PublicKnowledgeRelevance.filter("有哪些常见排障方法", List.of(mysql, redis, nacos)))
                .containsExactly(mysql, redis, nacos);
    }

    private static RetrievedChunk chunk(long id, String title, String heading, String body) {
        return RetrievedChunk.from(
                Map.of(
                        "chunkId",
                        id,
                        "documentId",
                        id,
                        "documentName",
                        title,
                        "headingPath",
                        heading,
                        "content",
                        body,
                        "retrievalMode",
                        "BM25"));
    }
}
