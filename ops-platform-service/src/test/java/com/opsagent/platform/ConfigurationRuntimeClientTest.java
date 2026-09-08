package com.opsagent.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.OpsPrincipal;
import com.opsagent.common.security.RuntimeConfigurationSnapshot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A configuration source or wrong target must not become a credentialed proxy or runtime claim.
 *
 * @author heyu
 * @since 2026/9/3
 */
class ConfigurationRuntimeClientTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = mock(HttpClient.class);
    private final MockEnvironment environment =
            new MockEnvironment().withProperty("spring.application.name", "ops-platform-service");

    @BeforeEach
    void authorize() {
        actor("ADMIN");
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-user-identity");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void onlyExplicitInternalTargetsReceiveCurrentIdentityAndResponseUsesLocalWhitelist()
            throws Exception {
        environment.setProperty(
                "ops.configuration.runtime-targets.ops-rag-service", "http://ops-rag-app:8104");
        respond(
                "ops-rag-service",
                Instant.now(),
                List.of(
                        Map.of("key", "ops.rag.top-k", "value", "8", "source", "应用配置文件 → 环境变量"),
                        Map.of(
                                "key",
                                "spring.datasource.url",
                                "value",
                                "jdbc:mysql://name:secret@mysql/db?password=hidden",
                                "source",
                                "private-file-with-secret"),
                        Map.of(
                                "key",
                                "ops.security.secret",
                                "value",
                                "must-not-escape",
                                "source",
                                "环境变量")));
        var result = client().snapshot("ops-rag-service");
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.fields()).hasSize(2);
        assertThat(result.fields().get(0).value()).isEqualTo("8");
        assertThat(result.fields().get(0).verification()).isEqualTo("RUNTIME_RESOLVED");
        assertThat(json.writeValueAsString(result))
                .doesNotContain(
                        "must-not-escape", "private-file-with-secret", "hidden", "name:secret");
        var captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http)
                .send(
                        captor.capture(),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(captor.getValue().uri().toString())
                .isEqualTo("http://ops-rag-app:8104/api/runtime/configuration");
        assertThat(captor.getValue().headers().firstValue("Authorization"))
                .contains("Bearer test-user-identity");
        assertThat(result.message()).contains("单个", "不等同");
    }

    @Test
    void unconfiguredExternalUnknownAndUnauthorizedTargetsDoNotReceiveRequests() {
        assertThat(client().snapshot("ops-rag-service").status()).isEqualTo("UNSUPPORTED");
        assertThat(client().snapshot("arbitrary-service").status()).isEqualTo("UNSUPPORTED");
        for (String url :
                List.of(
                        "https://attacker.example",
                        "http://169.254.169.254",
                        "http://user:pass@localhost:8104",
                        "http://localhost:8104/other",
                        "http://localhost:8104?token=secret")) {
            environment.setProperty("ops.configuration.runtime-targets.ops-rag-service", url);
            assertThat(client().snapshot("ops-rag-service").status()).isEqualTo("UNSUPPORTED");
        }
        actor("USER");
        assertThat(client().snapshot("ops-platform-service").status()).isEqualTo("FORBIDDEN");
        verifyNoInteractions(http);
        assertThat(ConfigurationRuntimeClient.trusted(URI.create("http://ops-knowledge-app:8103")))
                .isNotNull();
    }

    @Test
    void wrongIdentityStaleOrDeniedSnapshotNeverKeepsPreviousFields() throws Exception {
        environment.setProperty("OPS_RAG_INTERNAL_URL", "http://ops-rag-app:8104");
        var reader = client();
        respond("ops-auth-service", Instant.now(), List.of());
        assertThat(reader.snapshot("ops-rag-service").status()).isEqualTo("UNAVAILABLE");
        respond("ops-rag-service", Instant.now().minusSeconds(120), List.of());
        assertThat(reader.snapshot("ops-rag-service").status()).isEqualTo("STALE");
        var denied = response(403, "upstream-private-error");
        when(http.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(denied);
        var result = reader.snapshot("ops-rag-service");
        assertThat(result.status()).isEqualTo("FORBIDDEN");
        assertThat(result.fields()).isEmpty();
        assertThat(result.message()).doesNotContain("upstream-private-error");
    }

    @Test
    void localSnapshotUsesCurrentEnvironmentWithoutNetworkOrInventedDefaults() {
        environment.setProperty("spring.rabbitmq.host", "actual-rabbitmq");
        var result = client().snapshot("ops-platform-service");
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.fields())
                .singleElement()
                .satisfies(field -> assertThat(field.value()).isEqualTo("actual-rabbitmq"));
        verifyNoInteractions(http);
    }

    private ConfigurationRuntimeClient client() {
        return new ConfigurationRuntimeClient(
                json, environment, new RuntimeConfigurationSnapshot(environment), http);
    }

    private void respond(String service, Instant observed, List<Map<String, String>> fields)
            throws Exception {
        String body =
                json.writeValueAsString(
                        Map.of(
                                "code",
                                0,
                                "data",
                                Map.of(
                                        "serviceId",
                                        service,
                                        "instanceId",
                                        "00000000-0000-4000-8000-000000000001",
                                        "observedAt",
                                        observed.toString(),
                                        "fields",
                                        fields)));
        var successful = response(200, body);
        when(http.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(successful);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<InputStream> response(int code, String body) {
        var response = (HttpResponse<InputStream>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static void actor(String role) {
        var actor = new OpsPrincipal(1, "test", "token", List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }
}
