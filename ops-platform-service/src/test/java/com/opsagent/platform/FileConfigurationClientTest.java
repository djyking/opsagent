package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The platform authenticates roles itself and never forwards user-supplied identity or paths.
 *
 * @author heyu
 * @since 2026/9/3
 */
class FileConfigurationClientTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsExternalExecutorTargetsAndOrdinaryUsers() {
        assertThat(FileConfigurationClient.trusted("http://example.com:18110")).isNull();
        assertThat(FileConfigurationClient.trusted("http://user:secret@127.0.0.1:18110")).isNull();
        assertThat(FileConfigurationClient.trusted("http://127.0.0.1:18110")).isNotNull();
        actor("USER");
        var client =
                new FileConfigurationClient(
                        "http://127.0.0.1:18110", "test-secret-".repeat(4), new ObjectMapper());
        assertThatThrownBy(() -> client.get("/files")).isInstanceOf(BusinessException.class);
        actor("OPS");
        assertThatThrownBy(
                        () ->
                                client.post(
                                        "/files/auth/drafts",
                                        new ObjectMapper().createObjectNode()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void privateExecutorRequiresAnExactConfiguredLiteralHost() {
        assertThat(FileConfigurationClient.trusted("http://172.20.0.1:18110", "172.20.0.1"))
                .isNotNull();
        assertThat(FileConfigurationClient.trusted("http://172.20.0.2:18110", "172.20.0.1"))
                .isNull();
        assertThat(FileConfigurationClient.trusted("http://172.20.0.1:18110")).isNull();
        assertThat(FileConfigurationClient.trusted("http://example.com:18110", "example.com"))
                .isNull();
        assertThat(FileConfigurationClient.trusted("http://8.8.8.8:18110", "8.8.8.8")).isNull();
        assertThat(FileConfigurationClient.trusted("http://172.20.0.1:18110/private", "172.20.0.1"))
                .isNull();
        assertThat(
                        FileConfigurationClient.trusted(
                                "http://172.20.0.1:18110?redirect=x", "172.20.0.1"))
                .isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void signedProxyIdentityComesFromAuthenticatedPrincipal() throws Exception {
        actor("ADMIN");
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        new ByteArrayInputStream(
                                "{\"code\":0,\"data\":{\"items\":[]}}"
                                        .getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        var client =
                new FileConfigurationClient(
                        "http://127.0.0.1:18110",
                        "test-secret-".repeat(4),
                        new ObjectMapper(),
                        http);
        assertThat(client.get("/files").path("items").isArray()).isTrue();
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captured.getValue().headers().firstValue("X-Ops-Actor-Id")).contains("7");
        assertThat(captured.getValue().headers().firstValue("X-Ops-Actor-Role")).contains("ADMIN");
        assertThatThrownBy(() -> client.get("/files/../../arbitrary"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void visitorUsesSanitizedExecutorViewAndCannotReadDraftsOrWrite() throws Exception {
        var visitor = new OpsPrincipal(-99, "visitor", "visitor", List.of("DEMO"));
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(visitor, null, List.of()));
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        new ByteArrayInputStream(
                                ("{\"code\":0,\"data\":{\"accessMode\":\"VISITOR_READ_ONLY\","
                                     + "\"fields\":[{\"key\":\"server.port\",\"value\":8101}]}}")
                                        .getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        var client =
                new FileConfigurationClient(
                        "http://127.0.0.1:18110",
                        "test-secret-".repeat(4),
                        new ObjectMapper(),
                        http);
        assertThat(client.get("/files/auth").path("fields").get(0).path("value").asInt())
                .isEqualTo(8101);
        ArgumentCaptor<HttpRequest> captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(captured.getValue().headers().firstValue("X-Ops-Actor-Id")).contains("-99");
        assertThat(captured.getValue().headers().firstValue("X-Ops-Actor-Role")).contains("DEMO");
        for (String path :
                List.of("/drafts/a", "/tasks/a", "/files/auth/download", "/files/drafts")) {
            assertThatThrownBy(() -> client.get(path)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(
                        () ->
                                client.post(
                                        "/files/auth/drafts",
                                        new ObjectMapper().createObjectNode()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void visitorFailsClosedWhenExecutorHasNotBeenUpgraded() throws Exception {
        actor("DEMO");
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body())
                .thenReturn(
                        new ByteArrayInputStream(
                                "{\"code\":0,\"data\":{\"redactedContent\":\"private\"}}"
                                        .getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
        var client =
                new FileConfigurationClient(
                        "http://127.0.0.1:18110",
                        "test-secret-".repeat(4),
                        new ObjectMapper(),
                        http);
        assertThatThrownBy(() -> client.get("/files/auth"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("脱敏视图");
    }

    private static void actor(String role) {
        var actor = new OpsPrincipal(7, "test", "test", List.of(role));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }
}
