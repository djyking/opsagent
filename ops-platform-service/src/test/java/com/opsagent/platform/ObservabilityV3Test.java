package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Flow;

/**
 * 真实JSON/SQL验证Trace脱敏与身份隔离、历史不可变、留存及证据权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ObservabilityV3Test {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void traceProjectionPreservesParentageAndEnvironmentWithoutSecretsOrInventedPods()
            throws Exception {
        var root =
                json.readTree(
                        """
{"batches":[{"resource":{"attributes":[
{"key":"service.namespace","value":{"stringValue":"opsagent"}},
{"key":"opsagent.environment","value":{"stringValue":"DEMO"}},
{"key":"opsagent.ci.code","value":{"stringValue":"orders"}},
{"key":"service.instance.id","value":{"stringValue":"real-instance-one"}},
{"key":"process.command_line","value":{"stringValue":"SECRET_PASSWORD"}}]},
"scopeSpans":[{"spans":[{"spanId":"0123456789abcdef","parentSpanId":"1111111111111111",
"name":"SQL SELECT secret","kind":2,"startTimeUnixNano":"1788696000000000000",
"endTimeUnixNano":"1788696000010000000","attributes":[
{"key":"http.route","value":{"stringValue":"/orders/{id}"}},
{"key":"Authorization","value":{"stringValue":"SECRET_BEARER"}}]}]}]}]}
""");
        var visible = TraceEvidenceAdapter.project(root, "DEMO");
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0))
                .containsEntry("parentSpanId", "1111111111111111")
                .containsEntry("durationMs", 10.0)
                .containsEntry("operation", "/orders/{id}");
        assertThat(visible.toString()).doesNotContain("SECRET", "SQL SELECT", "Pod");
        assertThat(TraceEvidenceAdapter.project(root, "PROD")).isEmpty();
    }

    @Test
    void identityAndRelationCompatibilityDoNotMergeSameNamesOrBroadDependencies() {
        assertThat(ObservabilityV3Service.identity("opsagent:DEMO:orders"))
                .containsExactly("DEMO", "orders");
        assertThat(ObservabilityV3Service.identity("opsagent:PROD:orders"))
                .containsExactly("PROD", "orders");
        assertThat(ObservabilityV3Service.identity("orders")).containsExactly("", "");
        var observed =
                Map.<String, Object>of(
                        "sourceCiCode", "a", "targetCiCode", "b", "relationType", "CALLS");
        assertThat(
                        ObservabilityV3Service.match(
                                Map.of(
                                        "sourceCiCode",
                                        "a",
                                        "targetCiCode",
                                        "b",
                                        "relationType",
                                        "DEPENDS_ON"),
                                observed))
                .isFalse();
        assertThat(ObservabilityV3Service.match(observed, observed)).isTrue();
    }

    @Test
    void historyIsImmutableAndEmptyRangesDoNotReturnCurrentGraphAndCleanupKeepsEvidence() {
        var repo = repository();
        Instant now = Instant.now();
        var first =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "environment",
                                "DEMO",
                                "windowStart",
                                now.minusSeconds(300).toString(),
                                "windowEnd",
                                now.toString(),
                                "graphVersion",
                                "a".repeat(64),
                                "nodes",
                                List.of(Map.of("ciCode", "a", "health", "CRITICAL"))));
        repo.snapshot(first);
        var second = new LinkedHashMap<>(first);
        second.put("nodes", List.of(Map.of("ciCode", "a", "health", "HEALTHY")));
        repo.snapshot(second);
        var history =
                TraceEvidenceAdapter.rows(
                        repo.history("DEMO", now.minusSeconds(10), now.plusSeconds(10))
                                .get("items"));
        assertThat(history).hasSize(1);
        assertThat(repo.snapshot(String.valueOf(history.get(0).get("id"))).toString())
                .contains("CRITICAL")
                .doesNotContain("HEALTHY");
        assertThat(
                        TraceEvidenceAdapter.rows(
                                repo.history(
                                                "DEMO",
                                                now.minusSeconds(86400),
                                                now.minusSeconds(3600))
                                        .get("items")))
                .isEmpty();
        String evidence =
                repo.evidence(
                        1, "a", "DEMO", 7L, new LinkedHashMap<>(Map.of("fixed", "event-evidence")));
        repo.cleanup(now.plusSeconds(10));
        assertThat(repo.evidence(evidence, 1, false)).containsEntry("fixed", "event-evidence");
        assertThatThrownBy(() -> repo.evidence(evidence, 2, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void instanceRestartCreatesSeparateLifetimesAndMissingTrafficIsNotProcessFailure() {
        var repo = repository();
        Instant now = Instant.now();
        repo.recordSpans(List.of(span("one", now.minusSeconds(3600)), span("two", now)));
        var items = repo.instances("service", "DEMO", now.minusSeconds(7200));
        assertThat(items).hasSize(2);
        assertThat(items.get(0))
                .containsEntry("instanceId", "two")
                .containsEntry("observationStatus", "RECENTLY_OBSERVED");
        assertThat(items.get(1))
                .containsEntry("instanceId", "one")
                .containsEntry("observationStatus", "NOT_RECENTLY_OBSERVED");
        assertThat(repo.instances("service", "PROD", now.minusSeconds(7200))).isEmpty();
    }

    @Test
    void freeTextOperationAndRuntimeFieldsCannotExposeRequestData() throws Exception {
        var root =
                json.readTree(
                        """
{"resourceSpans":[{"resource":{"attributes":[
{"key":"service.namespace","value":{"stringValue":"opsagent"}},
{"key":"opsagent.environment","value":{"stringValue":"PROD"}},
{"key":"opsagent.ci.code","value":{"stringValue":"orders"}},
{"key":"service.instance.id","value":{"stringValue":"token=SECRET"}},
{"key":"opsagent.runtime.kind","value":{"stringValue":"SECRET"}},
{"key":"process.pid","value":{"stringValue":"SECRET"}},
{"key":"process.runtime.name","value":{"stringValue":"token=SECRET"}}]},
"scopeSpans":[{"spans":[{"spanId":"0123456789abcdef","kind":3,
"startTimeUnixNano":"1788696000000000000","endTimeUnixNano":"1788696000010000000",
"status":{"code":"SECRET"},"attributes":[
{"key":"http.route","value":{"stringValue":"/orders?token=SECRET"}},
{"key":"db.operation.name","value":{"stringValue":"SELECT SECRET FROM users"}},
{"key":"messaging.operation.type","value":{"stringValue":"publish SECRET"}}]}]}]}]}
""");
        var spans = TraceEvidenceAdapter.project(root, "PROD");
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0)).containsEntry("operation", "操作").containsEntry("status", "UNSET");
        assertThat(spans.toString()).doesNotContain("SECRET");
    }

    @Test
    void directEvidenceRequiresCallDirectionAndKeepsBrokerSeparateFromConsumer() {
        var producer =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "ciCode",
                                "producer",
                                "environment",
                                "DEMO",
                                "spanId",
                                "1111111111111111",
                                "kind",
                                "PRODUCER",
                                "peerService",
                                "opsagent:DEMO:broker"));
        var consumer =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "ciCode",
                                "consumer",
                                "environment",
                                "DEMO",
                                "spanId",
                                "2222222222222222",
                                "parentSpanId",
                                "other",
                                "kind",
                                "CONSUMER"));
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "producer", "consumer"))
                .isFalse();
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "producer", "broker"))
                .isTrue();
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "broker", "producer"))
                .isFalse();
        consumer.put("parentSpanId", producer.get("spanId"));
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "producer", "consumer"))
                .isTrue();
        consumer.put("environment", "PROD");
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "producer", "consumer"))
                .isFalse();
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer),
                                "ops-demo-order-service",
                                "ops-demo-notification-service"))
                .isFalse();
    }

    @Test
    void sameRuntimeMessageEvidenceRequiresActualProducerConsumerParentage() {
        var producer =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "ciCode",
                                "orders",
                                "environment",
                                "DEMO",
                                "spanId",
                                "1111111111111111",
                                "kind",
                                "PRODUCER"));
        var consumer =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "ciCode",
                                "orders",
                                "environment",
                                "DEMO",
                                "spanId",
                                "2222222222222222",
                                "parentSpanId",
                                "1111111111111111",
                                "kind",
                                "CONSUMER"));
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "orders", "orders"))
                .isTrue();
        consumer.put("parentSpanId", "unrelated");
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "orders", "orders"))
                .isFalse();
        consumer.put("parentSpanId", producer.get("spanId"));
        consumer.put("environment", "PROD");
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "orders", "orders"))
                .isFalse();
        consumer.put("environment", "DEMO");
        producer.put("kind", "CLIENT");
        consumer.put("kind", "SERVER");
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer), "orders", "orders"))
                .isFalse();
        producer.put("kind", "PRODUCER");
        consumer.put("kind", "CONSUMER");
        producer.put("ciCode", "ops-demo-order-service");
        consumer.put("ciCode", "ops-demo-order-service");
        assertThat(
                        TraceEvidenceAdapter.hasDirectedRelation(
                                List.of(producer, consumer),
                                "ops-demo-order-service",
                                "ops-demo-notification-service"))
                .isFalse();
    }

    @Test
    void tempoOmittedLeadingZerosAreNormalizedButNonHexOrZeroIdsAreRejected() {
        String shortId = "71399510c3b13d27034d2e851e80add";
        assertThat(TraceEvidenceAdapter.traceIdentifier(shortId)).isEqualTo("0" + shortId);
        assertThat(TraceEvidenceAdapter.traceIdentifier("AF")).isEqualTo("0".repeat(30) + "af");
        assertThat(TraceEvidenceAdapter.traceIdentifier("a".repeat(32))).isEqualTo("a".repeat(32));
        for (String value :
                List.of("", "0", "0".repeat(32), "a".repeat(33), "../traces", "g123", " 1"))
            assertThat(TraceEvidenceAdapter.traceIdentifier(value)).isEmpty();
        assertThat(TraceEvidenceAdapter.traceIdentifier(null)).isEmpty();
    }

    @Test
    void virtualRabbitClientEvidenceIsMessagingWithoutInventingABrokerIdentity() throws Exception {
        assertThat(
                        TraceEvidenceAdapter.observedRelation(
                                json.readTree(
                                        """
{"connection_type":"virtual_node","client_messaging_system":"rabbitmq","server":"rabbitmq","virtual_node":"server"}
""")))
                .isEqualTo("MESSAGES");
        assertThat(
                        TraceEvidenceAdapter.observedRelation(
                                json.readTree("{\"connection_type\":\"messaging_system\"}")))
                .isEqualTo("MESSAGES");
        assertThat(
                        TraceEvidenceAdapter.observedRelation(
                                json.readTree("{\"connection_type\":\"database\"}")))
                .isEqualTo("ACCESSES_DATA");
        assertThat(
                        TraceEvidenceAdapter.observedRelation(
                                json.readTree("{\"connection_type\":\"virtual_node\"}")))
                .isEqualTo("CALLS");
    }

    @Test
    void identicalUnresolvedPeerNamesStaySeparatedByCounterpartEnvironment() {
        var aggregation = mock(TopologyAggregationService.class);
        var cmdb = mock(ItsmPlatformService.class);
        var traces = mock(TraceEvidenceAdapter.class);
        var repo = mock(ObservabilityV3Repository.class);
        var prod = Map.<String, Object>of("ciCode", "orders-prod", "environment", "PROD");
        var demo = Map.<String, Object>of("ciCode", "orders-demo", "environment", "DEMO");
        when(cmdb.cis(null, null)).thenReturn(List.of(prod, demo));
        when(aggregation.topology("ALL", "15m", "CONFIGURED"))
                .thenReturn(
                        Map.of(
                                "nodes",
                                List.of(prod, demo),
                                "edges",
                                List.of(),
                                "dataSources",
                                List.of()));
        when(traces.graph("15m"))
                .thenReturn(
                        new TraceEvidenceAdapter.Graph(
                                "READY",
                                "real sampled peers",
                                Instant.now(),
                                List.of(
                                        Map.of(
                                                "id",
                                                "prod-edge",
                                                "sourceService",
                                                "opsagent:PROD:orders-prod",
                                                "targetService",
                                                "rabbitmq",
                                                "relationType",
                                                "MESSAGES",
                                                "relationSource",
                                                "OBSERVED"),
                                        Map.of(
                                                "id",
                                                "demo-edge",
                                                "sourceService",
                                                "opsagent:DEMO:orders-demo",
                                                "targetService",
                                                "rabbitmq",
                                                "relationType",
                                                "MESSAGES",
                                                "relationSource",
                                                "OBSERVED"))));
        when(repo.handling(org.mockito.ArgumentMatchers.<String>anyList())).thenReturn(Map.of());
        var service = new ObservabilityV3Service(aggregation, cmdb, traces, repo, null);
        var graph = service.topology("ALL", "15m", "OBSERVED");
        var peers =
                TraceEvidenceAdapter.rows(graph.get("nodes")).stream()
                        .filter(node -> Boolean.TRUE.equals(node.get("virtual")))
                        .toList();
        assertThat(peers).hasSize(2);
        assertThat(peers.stream().map(node -> node.get("ciCode")).distinct().count()).isEqualTo(2);
        assertThat(peers.stream().map(node -> node.get("environment")))
                .containsExactlyInAnyOrder("PROD", "DEMO");
        assertThat(peers)
                .allSatisfy(
                        peer ->
                                assertThat(peer)
                                        .containsEntry("health", "UNKNOWN")
                                        .containsEntry("healthScope", "CLIENT_PEER_ONLY"));
        var differences = TraceEvidenceAdapter.rows(service.differences("ALL", "15m").get("items"));
        assertThat(differences)
                .hasSize(2)
                .allSatisfy(
                        row -> assertThat(row).containsEntry("category", "UNRESOLVED_IDENTITY"));
        assertThat(differences.stream().map(row -> row.get("id")).distinct().count()).isEqualTo(2);
        assertThat(ObservabilityV3Service.peerEnvironment("", "ALL")).isEqualTo("UNKNOWN");
        assertThat(ObservabilityV3Service.peerEnvironment("", "")).isEqualTo("UNKNOWN");
        assertThat(ObservabilityV3Service.peerEnvironment("DEMO", "PROD")).isEqualTo("DEMO");
    }

    @Test
    void repeatedSpansUseTwoInitialWritesAndOldSamplesCannotReplaceNewMetadata() {
        var jdbc = spy(new JdbcTemplate(dataSource()));
        var repo = new ObservabilityV3Repository(jdbc, json);
        repo.initialize();
        clearInvocations(jdbc);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var latest = new LinkedHashMap<>(span("one", now));
        latest.put("metadata", Map.of("runtimeVersion", "new"));
        repo.recordSpans(Collections.nCopies(500, latest));
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
        var older = new LinkedHashMap<>(span("one", now.minusSeconds(300)));
        older.put("metadata", Map.of("runtimeVersion", "old"));
        repo.recordSpans(List.of(older));
        var rows = repo.instances("service", "DEMO", now.minusSeconds(600));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("firstSeenAt", now.minusSeconds(300).toString())
                .containsEntry("lastSeenAt", now.toString());
        assertThat(((Map<?, ?>) rows.get(0).get("metadata")).get("runtimeVersion"))
                .isEqualTo("new");
        var prod = new LinkedHashMap<>(latest);
        prod.put("environment", "PROD");
        repo.recordSpans(List.of(prod));
        assertThat(repo.instances("service", "PROD", now.minusSeconds(600))).hasSize(1);
    }

    @Test
    void allScopeServiceQueriesUseCmdbEnvironmentAndRejectConflictingIdentity() {
        var cmdb = mock(ItsmPlatformService.class);
        var traces = mock(TraceEvidenceAdapter.class);
        var repo = mock(ObservabilityV3Repository.class);
        var service = new ObservabilityV3Service(null, cmdb, traces, repo, null);
        when(cmdb.ci("orders")).thenReturn(Map.of("environment", "PROD"));
        when(traces.search("orders", "PROD", "15m", null)).thenReturn(Map.of("items", List.of()));
        when(traces.trace("a".repeat(32), "PROD", "orders")).thenReturn(Map.of("spans", List.of()));
        service.search("orders", "ALL", "15m", null);
        service.trace("a".repeat(32), "ALL", "orders");
        verify(traces).search("orders", "PROD", "15m", null);
        verify(traces).trace("a".repeat(32), "PROD", "orders");
        assertThatThrownBy(() -> service.search("orders", "DEMO", "15m", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.trace("a".repeat(32), "DEMO", "orders"))
                .isInstanceOf(BusinessException.class);
        Instant end = Instant.now();
        assertThatThrownBy(() -> service.history("PROD", end.minusSeconds(72 * 3600 + 1), end))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void oversizedEvidenceBodyCancelsUpstreamBeforeBufferingMoreBytes() {
        var subscriber = new TraceEvidenceAdapter.LimitedBody();
        var subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(2_000_000)));
        subscriber.onNext(List.of(ByteBuffer.allocate(2_000_001)));
        verify(subscription).cancel();
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    @Test
    void expiredDifferenceIgnoresReopenAndBatchLookupPreservesRecordedReason() {
        var repo = repository();
        String id = UUID.randomUUID().toString();
        repo.decision(id, "DEMO", "IGNORE", "待复核", Instant.now().minusSeconds(60), 1);
        assertThat(repo.handling(List.of(id)).get(id))
                .containsEntry("decision", "OPEN")
                .containsEntry("expired", true)
                .containsEntry("note", "待复核");
        assertThat(repo.handling(UUID.randomUUID().toString())).containsEntry("decision", "OPEN");
    }

    private Map<String, Object> span(String instance, Instant at) {
        return Map.of(
                "ciCode",
                "service",
                "environment",
                "DEMO",
                "instanceId",
                instance,
                "startTime",
                at.toString(),
                "metadata",
                Map.of("runtimeKind", "DOCKER_COMPOSE"));
    }

    private ObservabilityV3Repository repository() {
        var repo = new ObservabilityV3Repository(new JdbcTemplate(dataSource()), json);
        repo.initialize();
        return repo;
    }

    private DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:v3-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }
}
