package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Historical output uses a distinct audience and still revalidates current source visibility.
 *
 * @author heyu
 * @since 2026/9/3
 */
class HarnessOutputAccessTest {
    private static final String SECRET = "synthetic-output-access-secret-more-than-thirty-two";
    private final InternalActorTokens tokens = new InternalActorTokens(SECRET);
    private final ObjectMapper json = new ObjectMapper();
    private final String runId = UUID.randomUUID().toString();
    private MockWebServer auth;
    private MockWebServer knowledge;

    @BeforeEach
    void setup() throws Exception {
        auth = new MockWebServer();
        knowledge = new MockWebServer();
        auth.start();
        knowledge.start();
        auth.setDispatcher(
                new okhttp3.mockwebserver.Dispatcher() {
                    @Override
                    public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                        return response(
                                """
                                {"code":0,"data":{"userId":7,"username":"reader","active":true,
                                "roles":["USER"],"expiresAt":null}}
                                """);
                    }
                });
    }

    @AfterEach
    void close() throws Exception {
        auth.shutdown();
        knowledge.shutdown();
    }

    @Test
    void answerEndpointsRequireTheirOwnAudienceWithoutFallingBackToExecution() {
        var answers = mock(HarnessAnswerService.class);
        var controller = new HarnessAnswerController(answers, SECRET, auth.url("/").toString());
        var request =
                new HarnessAnswerController.Access(UUID.randomUUID().toString(), "a".repeat(64));
        String output = authorization("rag-output");
        String execution = authorization("rag");
        when(answers.outputAnswerAccess(
                        any(), eq(output), eq(request.contextId()), eq(request.promptSha256())))
                .thenReturn(true);
        when(answers.answerAccess(
                        any(), eq(execution), eq(request.contextId()), eq(request.promptSha256())))
                .thenReturn(true);
        assertThat(allowed(controller.outputAccess(output, request).getBody())).isTrue();
        assertThat(allowed(controller.access(output, request).getBody())).isFalse();
        assertThat(allowed(controller.outputAccess(execution, request).getBody())).isFalse();
        assertThat(allowed(controller.access(execution, request).getBody())).isTrue();
        verify(answers)
                .outputAnswerAccess(
                        any(), eq(output), eq(request.contextId()), eq(request.promptSha256()));
        verify(answers)
                .answerAccess(
                        any(), eq(execution), eq(request.contextId()), eq(request.promptSha256()));
        verifyNoMoreInteractions(answers);
        assertThat(auth.getRequestCount()).isEqualTo(2);
    }

    @Test
    void outputCitationChecksPreserveCurrentKnowledgeVerdictAndTheRestrictedIdentity()
            throws Exception {
        var citations =
                new HarnessCitationAccessController(
                        SECRET, auth.url("/").toString(), knowledge.url("/").toString());
        var request =
                new HarnessCitationAccessController.Request(
                        List.of(new HarnessCitationAccessController.Citation(6003, 10, 1)), true);
        String output = authorization("rag-output");
        knowledge.enqueue(response("{\"code\":0,\"data\":{\"allowed\":true}}"));
        knowledge.enqueue(response("{\"code\":0,\"data\":{\"allowed\":false}}"));
        assertThat(citations.validateOutput(output, request).data().allowed()).isTrue();
        assertThat(citations.validateOutput(output, request).data().allowed()).isFalse();
        for (int index = 0; index < 2; index++) {
            var actual = knowledge.takeRequest(2, TimeUnit.SECONDS);
            assertThat(actual).isNotNull();
            assertThat(actual.getPath()).isEqualTo("/internal/agent/validate-citations");
            var actor = tokens.verify(actual.getHeader("Authorization"), "knowledge");
            assertThat(actor.userId()).isEqualTo(7);
            assertThat(actor.runId()).isEqualTo(runId);
            assertThat(actor.roles()).containsExactly("USER");
            JsonNode body = json.readTree(actual.getBody().readUtf8());
            assertThat(body.path("allowUnpublished").asBoolean()).isTrue();
            assertThat(body.path("citations").get(0).path("version").asInt()).isEqualTo(1);
            assertThat(body.path("citations").get(0).path("documentId").asLong()).isEqualTo(6003);
        }
        assertThatThrownBy(() -> citations.validate(output, request))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> citations.validateOutput(authorization("rag"), request))
                .isInstanceOf(BusinessException.class);
        assertThat(knowledge.getRequestCount()).isEqualTo(2);
        assertThat(auth.getRequestCount()).isEqualTo(2);
    }

    @Test
    void outputTokenCannotExecuteModelsSearchOrProbeExistingInternalEndpoints() {
        var models = mock(InternalAgentModelService.class);
        var search = mock(InternalAgentSearchService.class);
        var usage = mock(InternalAgentUsageService.class);
        var controller =
                new InternalAgentController(
                        models, search, usage, SECRET, auth.url("/").toString());
        String output = authorization("rag-output");
        assertThatThrownBy(() -> controller.turn(output, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.search(output, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.probe(output, "deepseek"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(models, search, usage);
        assertThat(auth.getRequestCount()).isZero();
    }

    @Test
    void currentActorRevocationPreventsOutputSourceCalls() {
        auth.setDispatcher(
                new okhttp3.mockwebserver.Dispatcher() {
                    @Override
                    public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                        return response("{\"code\":0,\"data\":{\"userId\":7,\"active\":false}}");
                    }
                });
        var answers = mock(HarnessAnswerService.class);
        var controller = new HarnessAnswerController(answers, SECRET, auth.url("/").toString());
        var request =
                new HarnessAnswerController.Access(UUID.randomUUID().toString(), "a".repeat(64));
        assertThat(allowed(controller.outputAccess(authorization("rag-output"), request).getBody()))
                .isFalse();
        verifyNoInteractions(answers);
        assertThat(knowledge.getRequestCount()).isZero();
    }

    private String authorization(String audience) {
        return "Bearer "
                + tokens.issue(
                        audience,
                        new InternalActorTokens.Context(
                                7,
                                "reader",
                                List.of("USER"),
                                runId,
                                "",
                                Instant.now().plusSeconds(120)));
    }

    private boolean allowed(Object body) {
        return json.valueToTree(body).path("data").path("allowed").asBoolean();
    }

    private static MockResponse response(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
