package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证Nacos3固定管理接口、服务端身份鉴权、真实分页元信息及响应脱敏。
 *
 * @author heyu
 * @since 2026/9/3
 */
class OperationsNacosMetadataTest {
    private static final String SERVICES = """
            {"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pageItems":[
              {"name":"ops-auth-service","groupName":"DEFAULT_GROUP","ipCount":2,"healthyInstanceCount":1}
            ]}}
            """;
    private static final String CONFIGURATIONS = """
            {"code":0,"message":"success","data":{"totalCount":1,"pageNumber":1,"pageItems":[
              {"dataId":"ops-auth-service.yaml","groupName":"DEFAULT_GROUP","modifyTime":1788649999000,
               "content":"must-never-reach-client","password":"must-never-reach-client"}
            ]}}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = mock(HttpClient.class);
    private final List<HttpRequest> requests = new ArrayList<>();
    private OperationsIntegrationClient client;

    @BeforeEach
    void prepare() {
        client = new OperationsIntegrationClient(json, http);
        ReflectionTestUtils.setField(client, "nacosEnabled", true);
        ReflectionTestUtils.setField(client, "nacosUrl", "http://nacos.test/nacos");
        ReflectionTestUtils.setField(client, "nacosUsername", "");
        ReflectionTestUtils.setField(client, "nacosPassword", "");
        ReflectionTestUtils.setField(client, "namespace", "");
        ReflectionTestUtils.setField(client, "nacosIdentityKey", "x-test-server-identity");
        ReflectionTestUtils.setField(client, "nacosIdentityValue", "server-identity-test-secret");
    }

    @Test
    void shouldUseAuthenticatedV3MetadataEndpointsAndProjectSafeFields() throws Exception {
        respond(request -> response(200, request.uri().getPath().contains("/ns/") ? SERVICES : CONFIGURATIONS));
        var result = client.nacos();
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.serviceCount()).isEqualTo(1);
        assertThat(result.healthyInstanceCount()).isEqualTo(1);
        assertThat(result.configurationCount()).isEqualTo(1);
        assertThat(result.configurations().get(0).group()).isEqualTo("DEFAULT_GROUP");
        assertThat(result.configurations().get(0).modifiedAt()).isEqualTo("1788649999000");
        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri().getPath()).startsWith("/nacos/v3/admin/").endsWith("/list");
            assertThat(request.uri().getQuery()).contains("namespaceId=public");
            assertThat(request.headers().firstValue("x-test-server-identity"))
                    .contains("server-identity-test-secret");
        });
        assertThat(json.writeValueAsString(result))
                .doesNotContain("server-identity-test-secret", "must-never-reach-client", "x-test-server-identity");
    }

    @Test
    void shouldKeepFailedOrMalformedMetadataUnknownWithoutLeakingResponse() throws Exception {
        respond(request -> request.uri().getPath().contains("/ns/")
                ? response(403, "{\"message\":\"server-identity-test-secret\"}")
                : response(200, "{\"code\":0,\"data\":{\"content\":\"must-never-reach-client\"}}"));
        var result = client.nacos();
        assertThat(result.status()).isEqualTo("UNAVAILABLE");
        assertThat(result.serviceCount()).isNull();
        assertThat(result.configurationCount()).isNull();
        assertThat(json.writeValueAsString(result))
                .doesNotContain("server-identity-test-secret", "must-never-reach-client");
    }

    @Test
    void shouldKeepAvailableRegistrationWhenConfigurationAccessFails() throws Exception {
        respond(request -> request.uri().getPath().contains("/ns/") ? response(200, SERVICES)
                : response(403, "{\"message\":\"forbidden\"}"));
        var result = client.nacos();
        assertThat(result.status()).isEqualTo("PARTIAL");
        assertThat(result.services()).hasSize(1);
        assertThat(result.configurationCount()).isNull();
    }

    @Test
    void shouldUseV3LoginForConfiguredReadOnlyUserWhenServerIdentityIsAbsent() throws Exception {
        ReflectionTestUtils.setField(client, "nacosIdentityKey", "");
        ReflectionTestUtils.setField(client, "nacosIdentityValue", "");
        ReflectionTestUtils.setField(client, "nacosUsername", "readonly");
        ReflectionTestUtils.setField(client, "nacosPassword", "login-test-password");
        respond(request -> {
            if (request.method().equals("POST")) return response(200, "{\"accessToken\":\"login-test-token\"}");
            return response(200, request.uri().getPath().contains("/ns/") ? SERVICES : CONFIGURATIONS);
        });
        var result = client.nacos();
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(requests.get(0).uri().getPath()).isEqualTo("/nacos/v3/auth/user/login");
        assertThat(requests.get(1).uri().getQuery()).contains("accessToken=login-test-token");
        assertThat(json.writeValueAsString(result)).doesNotContain("login-test-token", "login-test-password");
    }

    @Test
    void shouldNeverSendNacosServerIdentityToSentinelRuntime() throws Exception {
        ReflectionTestUtils.setField(client, "ragUrl", "http://rag.test");
        respond(request -> response(200, "{\"code\":0,\"data\":{\"rules\":[],\"passedTotal\":0,\"blockedTotal\":0}}"));
        client.sentinel();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).headers().firstValue("x-test-server-identity")).isEmpty();
    }

    private void respond(Function<HttpRequest, HttpResponse<String>> responder) throws Exception {
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    requests.add(request);
                    return responder.apply(request);
                });
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
