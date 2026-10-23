package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.ApiResponse;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.OpsPrincipal;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author heyu @since 2026/9/3
 */
class HarnessAnswerServiceTest {
    MockWebServer auth;
    MockWebServer platform;
    JdbcTemplate jdbc;
    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    RagService rag = mock(RagService.class);
    HarnessCitationAccessController citations = mock(HarnessCitationAccessController.class);
    final String runId = UUID.randomUUID().toString();
    final String releaseId = UUID.randomUUID().toString();

    @BeforeEach
    void setup() throws Exception {
        auth = new MockWebServer();
        auth.start();
        platform = new MockWebServer();
        platform.start();
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("harness-answer-schema.sql"))
                .execute(ds);
        jdbc = new JdbcTemplate(ds);
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(7, "user", "login", List.of("USER")),
                                null,
                                List.of()));
        auth.setDispatcher(
                new okhttp3.mockwebserver.Dispatcher() {
                    @Override
                    public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                        return response(
                                "{\"code\":0,\"data\":{\"applicationId\":\"ops-app\","
                                    + "\"projectId\":\"ops-project\",\"subject\":\"7\",\"permissions\":"
                                    + "[\"runs:create\",\"runs:read\",\"runs:output:read\",\"opsagent:rag:search\"]}}");
                    }
                });
    }

    @AfterEach
    void close() throws Exception {
        SecurityContextHolder.clearContext();
        auth.shutdown();
        platform.shutdown();
    }

    HarnessAnswerService service(String owner) {
        return new HarnessAnswerService(
                jdbc,
                json,
                rag,
                citations,
                auth.url("/").toString(),
                platform.url("/").toString(),
                "synthetic-application-credential-only-tests",
                "ops-app",
                "ops-project",
                owner,
                releaseId,
                "sha256:" + "a".repeat(64),
                "deepseek",
                "synthetic-model");
    }

    RagService.StreamPlan plan(List<RagService.Source> sources) {
        return new RagService.StreamPlan(
                "question",
                List.of(),
                List.of(),
                sources,
                new LlmRequest("trusted rules", "authorized question and context", 200),
                new RagService.AnswerMetadata("TEST", false, 0, 0, 0, false, null),
                null,
                0,
                null,
                null,
                false);
    }

    RagController.ChatRequest request() {
        return new RagController.ChatRequest("question", 5, null, null, null);
    }

    @Test
    void rejectedCommandBodyIsValidationFailureRatherThanUnknownTransportOutcome() {
        var client =
                new HarnessPlatformClient(
                        json,
                        auth.url("/").toString(),
                        platform.url("/").toString(),
                        "synthetic-application-credential-only-tests");
        platform.enqueue(new MockResponse().setResponseCode(422).setBody("{}"));
        assertThatThrownBy(
                        () ->
                                client.call(
                                        "/v1/projects/ops-project/runs",
                                        "POST",
                                        json.createObjectNode(),
                                        "synthetic-user",
                                        "invalid-command",
                                        null))
                .hasMessageContaining("HARNESS_COMMAND_INPUT_INVALID");
    }

    @Test
    void utf8DispatchLimitRejectsBeforeFreezingOrCallingPlatform() {
        var oversized =
                new RagService.StreamPlan(
                        "question",
                        List.of(),
                        List.of(),
                        List.of(),
                        new LlmRequest("trusted rules", "中".repeat(23000), 200),
                        new RagService.AnswerMetadata("TEST", false, 0, 0, 0, false, null),
                        null,
                        0,
                        null,
                        null,
                        false);
        when(rag.prepareHarness(any())).thenReturn(oversized);
        assertThatThrownBy(
                        () ->
                                service("HARNESS")
                                        .submit(
                                                "Bearer synthetic-user",
                                                "request-utf8-limit",
                                                request(),
                                                () -> {
                                                    throw new AssertionError("legacy invoked");
                                                }))
                .hasMessageContaining("HARNESS_DISPATCH_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT state FROM ops_harness_answer", String.class))
                .isEqualTo("REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT dispatch_json FROM ops_harness_answer", String.class))
                .isNull();
        assertThat(platform.getRequestCount()).isZero();
    }

    @Test
    void asyncAcceptanceFreezesPayloadAndRetryDoesNotGenerateAgainAfterRollback() throws Exception {
        when(rag.prepareHarness(any())).thenReturn(plan(List.of()));
        platform.enqueue(response("{\"run\":{\"id\":\"" + runId + "\"}}"));
        platform.enqueue(response("{\"status\":\"RUNNING\"}"));
        var result =
                service("HARNESS")
                        .submit(
                                "Bearer synthetic-user",
                                "request-1001",
                                request(),
                                () -> {
                                    throw new AssertionError("legacy invoked");
                                });
        assertThat(result.status()).isEqualTo(202);
        String route = jdbc.queryForObject("SELECT id FROM ops_harness_answer", String.class);
        var submission = platform.takeRequest();
        assertThat(submission.getHeader("Idempotency-Key")).isEqualTo(route);
        assertThat(submission.getHeader("X-Harness-User-Token")).isEqualTo("synthetic-user");
        var payload = json.readTree(submission.getBody().readUtf8());
        assertThat(payload.path("inputs").path("contextId").asText()).isEqualTo(route);
        platform.takeRequest();
        platform.enqueue(response("{\"status\":\"RUNNING\"}"));
        service("LEGACY")
                .submit(
                        "Bearer synthetic-user",
                        "request-1001",
                        request(),
                        () -> {
                            throw new AssertionError("rollback replayed old request");
                        });
        assertThat(platform.takeRequest().getMethod()).isEqualTo("GET");
        verify(rag, times(1)).prepareHarness(any());
    }

    @Test
    void unknownLegacyOutcomeNeverFallsForwardToHarness() {
        AtomicInteger effects = new AtomicInteger();
        assertThatThrownBy(
                        () ->
                                service("LEGACY")
                                        .submit(
                                                "Bearer synthetic-user",
                                                "request-1002",
                                                request(),
                                                () -> {
                                                    effects.incrementAndGet();
                                                    throw new IllegalStateException("lost reply");
                                                }))
                .isInstanceOf(IllegalStateException.class);
        var retry =
                service("HARNESS")
                        .submit(
                                "Bearer synthetic-user",
                                "request-1002",
                                request(),
                                () -> {
                                    effects.incrementAndGet();
                                    return null;
                                });
        assertThat(retry.status()).isEqualTo(202);
        assertThat(effects.get()).isEqualTo(1);
        assertThat(platform.getRequestCount()).isZero();
        verify(rag, never()).prepareHarness(any());
    }

    @Test
    void protectedProjectionBindsExactContextUserRunPromptAndRevalidatesEverySource()
            throws Exception {
        var source =
                new RagService.Source(
                        11,
                        3,
                        0,
                        "document",
                        null,
                        1,
                        null,
                        1,
                        "S1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of(),
                        false,
                        null,
                        "KNOWLEDGE_DOCUMENT",
                        null,
                        null,
                        null);
        when(rag.prepareHarness(any())).thenReturn(plan(List.of(source)));
        platform.enqueue(response("{\"run\":{\"id\":\"" + runId + "\"}}"));
        platform.enqueue(response("{\"status\":\"RUNNING\"}"));
        var service = service("HARNESS");
        service.submit("Bearer synthetic-user", "request-1003", request(), () -> null);
        String route = jdbc.queryForObject("SELECT id FROM ops_harness_answer", String.class);
        String hash =
                jdbc.queryForObject("SELECT prompt_sha256 FROM ops_harness_answer", String.class);
        var actor =
                new InternalActorTokens.Context(
                        7, "user", List.of("USER"), runId, "", Instant.now().plusSeconds(60));
        when(citations.validate(eq("signed-delegation"), any()))
                .thenReturn(ApiResponse.success(new HarnessCitationAccessController.Verdict(true)));
        assertThat(service.answerAccess(actor, "signed-delegation", route, hash)).isTrue();
        assertThat(service.answerAccess(actor, "signed-delegation", route, "b".repeat(64)))
                .isFalse();
        assertThat(
                        service.answerAccess(
                                new InternalActorTokens.Context(
                                        8,
                                        "other",
                                        List.of("USER"),
                                        runId,
                                        "",
                                        Instant.now().plusSeconds(60)),
                                "signed-delegation",
                                route,
                                hash))
                .isFalse();
        assertThat(
                        service.answerAccess(
                                new InternalActorTokens.Context(
                                        7,
                                        "user",
                                        List.of("USER"),
                                        UUID.randomUUID().toString(),
                                        "",
                                        Instant.now().plusSeconds(60)),
                                "signed-delegation",
                                route,
                                hash))
                .isFalse();
        when(citations.validate(eq("signed-delegation"), any()))
                .thenReturn(
                        ApiResponse.success(new HarnessCitationAccessController.Verdict(false)));
        assertThat(service.answerAccess(actor, "signed-delegation", route, hash)).isFalse();
        verify(citations, times(2))
                .validate(
                        eq("signed-delegation"),
                        argThat(
                                request ->
                                        request.citations()
                                                .equals(
                                                        List.of(
                                                                new HarnessCitationAccessController
                                                                        .Citation(3, 11, 1)))));
        when(citations.validateOutput(eq("signed-output"), any()))
                .thenReturn(ApiResponse.success(new HarnessCitationAccessController.Verdict(true)));
        assertThat(service.outputAnswerAccess(actor, "signed-output", route, hash)).isTrue();
        assertThat(service.outputAnswerAccess(actor, "signed-output", route, "b".repeat(64)))
                .isFalse();
        for (var wrong :
                List.of(
                        new InternalActorTokens.Context(
                                8,
                                "other",
                                List.of("USER"),
                                runId,
                                "",
                                Instant.now().plusSeconds(60)),
                        new InternalActorTokens.Context(
                                7,
                                "user",
                                List.of("USER"),
                                "other-run",
                                "",
                                Instant.now().plusSeconds(60)))) {
            assertThat(service.outputAnswerAccess(wrong, "signed-output", route, hash)).isFalse();
        }
        jdbc.update("UPDATE ops_harness_answer SET project_id='other-project'");
        assertThat(service.outputAnswerAccess(actor, "signed-output", route, hash)).isFalse();
        jdbc.update(
                "UPDATE ops_harness_answer SET"
                    + " project_id='ops-project',application_id='other-app'");
        assertThat(service.outputAnswerAccess(actor, "signed-output", route, hash)).isFalse();
        jdbc.update("UPDATE ops_harness_answer SET application_id='ops-app'");
        when(citations.validateOutput(eq("signed-output"), any()))
                .thenReturn(
                        ApiResponse.success(new HarnessCitationAccessController.Verdict(false)));
        assertThat(service.outputAnswerAccess(actor, "signed-output", route, hash)).isFalse();
        verify(citations, times(2))
                .validateOutput(
                        eq("signed-output"),
                        argThat(
                                request ->
                                        request.citations()
                                                .equals(
                                                        List.of(
                                                                new HarnessCitationAccessController
                                                                        .Citation(3, 11, 1)))));
        verify(citations, times(2)).validate(eq("signed-delegation"), any());
        assertThat(platform.getRequestCount()).isEqualTo(2);
    }

    @Test
    void missingGrantOrUnavailableIdentityPreventsPreparationAndDispatch() {
        auth.setDispatcher(
                new okhttp3.mockwebserver.Dispatcher() {
                    @Override
                    public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                        return new MockResponse().setResponseCode(403).setBody("{}");
                    }
                });
        assertThatThrownBy(
                        () ->
                                service("HARNESS")
                                        .submit(
                                                "Bearer synthetic-user",
                                                "request-1004",
                                                request(),
                                                () -> null))
                .hasMessageContaining("ACCESS_REVOKED");
        verifyNoInteractions(rag);
        assertThat(platform.getRequestCount()).isZero();
    }

    @Test
    void explicitLegacyDomainIsChosenBeforeWorkRatherThanOnPlatformFailure() {
        when(rag.legacyDomainRequired(any())).thenReturn(true);
        var expected =
                new RagService.Answer(
                        "domain answer",
                        List.of(),
                        "operations",
                        "readonly",
                        0,
                        0,
                        10,
                        new RagService.AnswerMetadata("OPERATIONS", false, 0, 0, 0, false, null));
        var result =
                service("HARNESS")
                        .submit(
                                "Bearer synthetic-user",
                                "request-domain",
                                request(),
                                () -> expected);
        assertThat(result.body()).isEqualTo(expected);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT execution_owner FROM ops_harness_answer", String.class))
                .isEqualTo("LEGACY");
        assertThat(platform.getRequestCount()).isZero();
        verify(rag, never()).prepareHarness(any());
    }

    @Test
    void publicSourceMustComeFromTheFixedOfficialCatalog() {
        var source =
                new RagService.Source(
                        0,
                        0,
                        0,
                        "web",
                        null,
                        null,
                        null,
                        0,
                        "S1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of("OFFICIAL_WEB"),
                        false,
                        null,
                        "OFFICIAL_WEB",
                        "https://attacker.invalid/private",
                        null,
                        null);
        when(rag.prepareHarness(any())).thenReturn(plan(List.of(source)));
        assertThatThrownBy(
                        () ->
                                service("HARNESS")
                                        .submit(
                                                "Bearer synthetic-user",
                                                "request-web",
                                                request(),
                                                () -> null))
                .hasMessageContaining("SOURCE_PROJECTION_UNAVAILABLE");
        assertThat(platform.getRequestCount()).isZero();
    }

    @Test
    void observationProjectionRechecksOriginalBundleUnderDelegatedActorAndRejectsMissingEvidence() {
        String bundle = UUID.randomUUID().toString();
        var range = new ObservabilityContext("service-a", "DEMO", "15m", null);
        var source =
                new RagService.Source(
                        0,
                        0,
                        0,
                        "metric",
                        null,
                        null,
                        null,
                        0,
                        "S1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Set.of("OBSERVABILITY"),
                        false,
                        null,
                        "OBSERVABILITY_EVIDENCE",
                        null,
                        null,
                        null,
                        bundle,
                        "evidence-1");
        var request = new RagController.ChatRequest("question", 5, null, null, null, range, null);
        when(rag.prepareHarness(any())).thenReturn(plan(List.of(source)));
        platform.enqueue(response("{\"run\":{\"id\":\"" + runId + "\"}}"));
        platform.enqueue(response("{\"status\":\"RUNNING\"}"));
        var service = service("HARNESS");
        var observations = mock(ObservabilityEvidenceClient.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "observations", observations);
        service.submit("Bearer synthetic-user", "request-observation", request, () -> null);
        String route = jdbc.queryForObject("SELECT id FROM ops_harness_answer", String.class);
        String hash =
                jdbc.queryForObject("SELECT prompt_sha256 FROM ops_harness_answer", String.class);
        var restored = new ObservabilityContext("service-a", "DEMO", "15m", bundle);
        when(observations.load(restored))
                .thenReturn(
                        new ObservabilityEvidenceClient.Evidence(
                                restored,
                                bundle,
                                Instant.now().toString(),
                                "GOOD",
                                List.of(),
                                List.of(
                                        new ObservabilityEvidenceClient.Entry(
                                                "evidence-1",
                                                "metrics",
                                                Instant.now().toString(),
                                                "GOOD",
                                                "safe",
                                                json.createObjectNode())),
                                true));
        var actor =
                new InternalActorTokens.Context(
                        7, "user", List.of("USER"), runId, "", Instant.now().plusSeconds(60));
        assertThat(service.answerAccess(actor, "signed-delegation", route, hash)).isTrue();
        when(observations.load(restored))
                .thenReturn(
                        new ObservabilityEvidenceClient.Evidence(
                                restored,
                                bundle,
                                Instant.now().toString(),
                                "GOOD",
                                List.of(),
                                List.of(),
                                true));
        assertThat(service.answerAccess(actor, "signed-delegation", route, hash)).isFalse();
        verify(observations, times(2)).load(restored);
    }

    static MockResponse response(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
