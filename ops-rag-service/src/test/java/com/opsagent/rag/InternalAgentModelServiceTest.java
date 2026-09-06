package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 真实数据库验证能力门禁、持久回执、结果未知时不重计费、模型快照及共享准入。
 *
 * @author heyu
 * @since 2026/9/3
 */
class InternalAgentModelServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private AiProperties properties;
    private InternalAgentStore store;
    private JdbcTemplate jdbc;
    private AiBudgetGuard budget;
    private AiUsageRepository usage;
    private InternalAgentModelService service;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        properties = NativeToolModelClientTest.configured(server);
        var dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:agent-model-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        store = new InternalAgentStore(jdbc, mapper);
        store.initialize();
        budget = mock(AiBudgetGuard.class);
        usage = mock(AiUsageRepository.class);
        when(budget.acquire()).thenReturn(() -> {});
        service = service(store);
    }

    @AfterEach
    void close() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldRequireVerifiedCapabilityAndReplayPersistedResponseWithoutSecondModelCall() {
        var request = request("stable-id");
        assertThat(service.models().models())
                .noneMatch(InternalAgentDtos.ModelCapability::toolCalling);
        assertThatThrownBy(() -> service.turn(request, actor(7)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MODEL_TOOL_CALLING_NOT_VERIFIED");
        assertThat(server.getRequestCount()).isZero();
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        server.enqueue(NativeToolModelClientTest.toolResponse("tool_calls", "{\"ticketId\":7}"));
        var actual = service.turn(request, actor(7));
        assertThat(actual.outcome()).isEqualTo("TOOL_CALLS");

        var restarted = service(new InternalAgentStore(jdbc, mapper));
        assertThat(restarted.turn(request, actor(7))).isEqualTo(actual);
        verify(budget, times(1)).acquire();
        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThatThrownBy(() -> restarted.turn(request, actor(8)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MODEL_CALL_ID_CONFLICT");
        properties.settings("deepseek").setModel("changed-model");
        assertThatThrownBy(() -> service.turn(request("new-id"), actor(7)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MODEL_SNAPSHOT_CHANGED");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void shouldStopUnknownAttemptsAcrossRestartsInsteadOfRetryingOrContinuing() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> service.turn(request("lost-response"), actor(7)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MODEL_HTTP_503").hasMessageContaining("新的隔离演练");
        assertThatThrownBy(
                        () ->
                                service(new InternalAgentStore(jdbc, mapper))
                                        .turn(request("lost-response"), actor(7)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MODEL_HTTP_503");
        assertThat(server.getRequestCount()).isEqualTo(2);
        verify(budget, times(2)).acquire();

        store.claim(request("process-crash"), actor(7));
        assertThatThrownBy(() -> service.turn(request("process-crash"), actor(7)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MODEL_OUTCOME_UNKNOWN");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void retriesOnlyTheSameReadOnlyDecisionAndReservesUnknownUsage() throws Exception {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(NativeToolModelClientTest.toolResponse("tool_calls", "{\"ticketId\":7}"));
        var request = request("transient-retry");
        var result = service.turn(request, actor(7));
        assertThat(result.outcome()).isEqualTo("TOOL_CALLS");
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.usageKnown()).isFalse();
        int reservation = new NativeToolModelClient(properties, new AiHttpExecutor(), mapper).reservation(request);
        assertThat(result.budgetTokens()).isEqualTo(reservation + 14);
        assertThat(server.takeRequest().getBody().readUtf8()).isEqualTo(server.takeRequest().getBody().readUtf8());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_agent_model_turn", Integer.class)).isEqualTo(1);
        assertThat(service.turn(request, actor(7))).isEqualTo(result);
        assertThat(server.getRequestCount()).isEqualTo(2);
        verify(budget, times(2)).acquire();
        verify(usage, times(2)).save(any());
    }

    @Test
    void doesNotRetryAuthenticationRequestOrMalformedProtocolFailures() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        for (int status : List.of(400, 401, 403, 422)) {
            server.enqueue(new MockResponse().setResponseCode(status));
            assertThatThrownBy(() -> service.turn(request("rejected-" + status), actor(7)))
                    .hasMessageContaining("MODEL_HTTP_" + status).hasMessageContaining("检查");
        }
        server.enqueue(NativeToolModelClientTest.json("not-json"));
        assertThatThrownBy(() -> service.turn(request("invalid-json"), actor(7)))
                .hasMessageContaining("MODEL_RESPONSE_INVALID");
        server.enqueue(NativeToolModelClientTest.json("{\"error\":{\"message\":\"private vendor message\"}}"));
        assertThatThrownBy(() -> service.turn(request("protocol-not-http-502"), actor(7)))
                .hasMessageContaining("MODEL_RESPONSE_INVALID").hasMessageNotContaining("private vendor message");
        assertThat(server.getRequestCount()).isEqualTo(6);
    }

    @Test
    void neverRetriesTruncatedOrRefusedNativeResponses() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        server.enqueue(NativeToolModelClientTest.toolResponse("length", "{\"ticketId\":"));
        assertThat(service.turn(request("truncated"), actor(7)).outcome()).isEqualTo("INCOMPLETE");
        server.enqueue(NativeToolModelClientTest.json("{\"choices\":[{\"finish_reason\":\"content_filter\","
                + "\"message\":{\"refusal\":\"refused\"}}]}"));
        assertThat(service.turn(request("refused"), actor(7)).outcome()).isEqualTo("REFUSED");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void keepsSuccessfulReceiptWhenUsageAuditFailsAndNeverRepeatsProvider() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        doThrow(new IllegalStateException("audit unavailable")).when(usage).save(any());
        server.enqueue(NativeToolModelClientTest.toolResponse("tool_calls", "{\"ticketId\":7}"));
        var result = service.turn(request("audit-down"), actor(7));
        assertThat(service.turn(request("audit-down"), actor(7))).isEqualTo(result);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void neverRetriesProviderAfterReceiptPersistenceFailure() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        var broken = spy(store);
        doThrow(new IllegalStateException("write failed")).when(broken).complete(anyString(), any());
        server.enqueue(NativeToolModelClientTest.toolResponse("tool_calls", "{\"ticketId\":7}"));
        assertThatThrownBy(() -> service(broken).turn(request("receipt-down"), actor(7)))
                .hasMessageContaining("MODEL_RECEIPT_FAILED");
        assertThatThrownBy(() -> service.turn(request("receipt-down"), actor(7)))
                .hasMessageContaining("MODEL_RECEIPT_FAILED");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void refusesSecondProviderRequestWhenDailyBudgetOrRemainingTokensDoNotPermitIt() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        when(budget.acquire()).thenReturn(() -> {}).thenThrow(
                new BusinessException(com.opsagent.common.core.ErrorCode.VALIDATION, "今日额度已用完"));
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> service.turn(request("daily-budget"), actor(7)))
                .hasMessageContaining("MODEL_BUDGET_REJECTED");
        assertThat(server.getRequestCount()).isEqualTo(1);
        doReturn((AiBudgetGuard.Permit) () -> {}).when(budget).acquire();
        var normal = request("token-budget");
        int cost = new NativeToolModelClient(properties, new AiHttpExecutor(), mapper).reservation(normal);
        var limited = new InternalAgentDtos.TurnRequest(normal.callId(), normal.provider(), normal.model(),
                normal.messages(), normal.tools(), normal.maxOutputTokens(), cost + 1);
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> service.turn(limited, actor(7))).hasMessageContaining("MODEL_HTTP_503");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void interruptedAdmissionNeverSendsAnOutboundRequest() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        when(budget.acquire()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return (AiBudgetGuard.Permit) () -> {};
        });
        try {
            assertThatThrownBy(() -> service.turn(request("admission-interrupted"), actor(7)))
                    .hasMessageContaining("MODEL_CANCELLED");
            assertThat(server.getRequestCount()).isZero();
        } finally { Thread.interrupted(); }
    }

    @Test
    void oversizedProviderUsageCannotOverflowTheRetryBudgetCharge() {
        store.capability("deepseek", properties.settings("deepseek"), "VERIFIED");
        server.enqueue(new MockResponse().setResponseCode(503));
        server.enqueue(NativeToolModelClientTest.json("{\"choices\":[{\"finish_reason\":\"stop\","
                + "\"message\":{\"content\":\"结果\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,"
                + "\"total_tokens\":2147483647}}"));
        var request = request("usage-overflow");
        var result = service.turn(request, actor(7));
        assertThat(result.budgetTokens()).isEqualTo(request.remainingTokens());
        assertThat(result.usageKnown()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    void shouldRejectVisitorProbeWithoutContactingProviderAndKeepTextOnlyFailureUnverified() {
        assertThatThrownBy(() -> service.probe("deepseek", actor(7)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("ADMIN_REQUIRED");
        server.enqueue(
                NativeToolModelClientTest.json(
                        """
                        {"model":"model-test","choices":[{"message":{"content":"I can use tools"},
                         "finish_reason":"stop"}]}
                        """));
        var admin =
                new InternalActorTokens.Context(
                        1,
                        "admin",
                        List.of("ADMIN"),
                        "admin-probe",
                        "",
                        Instant.now().plusSeconds(60));
        var status = service.probe("deepseek", admin);
        assertThat(status.verificationStatus()).isEqualTo("UNSUPPORTED");
        assertThat(status.toolCalling()).isFalse();
        assertThat(server.getRequestCount()).isEqualTo(1);
        verify(budget, times(1)).acquire();
    }

    @Test
    void shouldVerifyOnlyAnActualNativeForcedProbeWithMatchingNonce() throws Exception {
        var admin =
                new InternalActorTokens.Context(
                        1, "admin", List.of("ADMIN"), "probe", "", Instant.now().plusSeconds(60));
        server.setDispatcher(
                new okhttp3.mockwebserver.Dispatcher() {
                    @Override
                    public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                        try {
                            var body = mapper.readTree(request.getBody().readUtf8());
                            String name =
                                    body.path("tool_choice").path("function").path("name").asText();
                            String nonce =
                                    body.path("tools")
                                            .path(0)
                                            .path("function")
                                            .path("parameters")
                                            .path("properties")
                                            .path("nonce")
                                            .path("enum")
                                            .path(0)
                                            .asText();
                            String arguments = mapper.writeValueAsString(Map.of("nonce", nonce));
                            return NativeToolModelClientTest.json(
                                    mapper.writeValueAsString(
                                            Map.of(
                                                    "choices",
                                                    List.of(
                                                            Map.of(
                                                                    "finish_reason",
                                                                    "tool_calls",
                                                                    "message",
                                                                    Map.of(
                                                                            "tool_calls",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "id",
                                                                                            "probe_1",
                                                                                            "type",
                                                                                            "function",
                                                                                            "function",
                                                                                            Map.of(
                                                                                                    "name",
                                                                                                    name,
                                                                                                    "arguments",
                                                                                                    arguments)))))))));
                        } catch (IOException exception) {
                            return new MockResponse().setResponseCode(500);
                        }
                    }
                });
        var result = service.probe("deepseek", admin);
        assertThat(result.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(result.toolCalling()).isTrue();
        assertThat(result.verifiedAt()).isNotBlank();
        assertThat(server.getRequestCount()).isEqualTo(1);
        String directory = mapper.writeValueAsString(service.models());
        assertThat(directory).doesNotContain("test-key-not-production", "baseUrl", "apiKey");
    }

    private InternalAgentModelService service(InternalAgentStore repository) {
        return new InternalAgentModelService(
                properties,
                new NativeToolModelClient(properties, new AiHttpExecutor(), mapper),
                repository,
                budget,
                usage,
                new SimpleMeterRegistry(),
                mapper);
    }

    private InternalActorTokens.Context actor(long userId) {
        return new InternalActorTokens.Context(
                userId, "user", List.of("USER"), "run-1", "", Instant.now().plusSeconds(60));
    }

    private InternalAgentDtos.TurnRequest request(String id) {
        return NativeToolModelClientTest.request(
                id,
                "deepseek",
                NativeToolModelClientTest.messages(),
                NativeToolModelClientTest.tools());
    }
}
