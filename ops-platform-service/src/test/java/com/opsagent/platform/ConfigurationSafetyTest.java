package com.opsagent.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Realistic configuration formats, safe history projection and independently unavailable sources.
 *
 * @author heyu
 * @since 2026/9/3
 */
class ConfigurationSafetyTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void masksNestedSecretsUrlCredentialsSerializedObjectsAndMultilinePayloads() {
        var masker = new ConfigurationMasker(json);
        String masked =
                masker.mask(
                        """
spring:
  datasource:
    password: db-private
    url: jdbc:mysql://real-user:real-pass@mysql:3306/ops?password=url-private
  ai:
    api-key: model-private
nested: '{"jwtSecret":"jwt-private","visible":123}'
script: |
  password=embedded-private
  next=value
label: safe-value
""",
                        "yaml");
        assertThat(masked)
                .contains("******", "safe-value", "visible")
                .doesNotContain(
                        "db-private",
                        "real-user",
                        "real-pass",
                        "url-private",
                        "model-private",
                        "jwt-private",
                        "embedded-private");
        assertThat(masker.mask("ops.jwt.secret=prop-private\nname=visible", "properties"))
                .contains("******", "visible")
                .doesNotContain("prop-private");
    }

    @Test
    void failsClosedForMalformedUnsupportedOrUnsafeYaml() {
        var masker = new ConfigurationMasker(json);
        assertThat(masker.mask("password: !java/object unsafe-private", "yaml"))
                .doesNotContain("unsafe-private")
                .contains("******");
        assertThat(masker.mask("<password>xml-private</password>", "xml"))
                .doesNotContain("xml-private");
        assertThat(masker.mask("{\"password\":\"broken-private", "json"))
                .doesNotContain("broken-private");
        assertThat(masker.mask("{\"note\":\"-----BEGIN PRIVATE KEY----- pem-private\"}", "json"))
                .doesNotContain("pem-private");
    }

    @Test
    void masksJdbcSemicolonAndNameValueCredentialPairsIncludingSerializedPairs() {
        String raw =
                """
                url: jdbc:sqlserver://db;user=sa;password=sql-private
                env:
                  - name: REDIS_PASSWORD
                    value: env-private
                  - key: ops.api-key
                    value: api-private
                  - name: PORT
                    value: 1234
                nested: '{"name":"JWT_SECRET","value":"nested-private"}'
                command: --password=cli-private
                """;
        String masked = new ConfigurationMasker(json).mask(raw, "yaml");
        assertThat(masked)
                .contains("******", "PORT", "1234")
                .doesNotContain(
                        "sql-private",
                        "env-private",
                        "api-private",
                        "nested-private",
                        "cli-private");
    }

    @Test
    void nacosV3HistoryAndContentUseFixedIdentityAndValidateRequestedHistoryOwner()
            throws Exception {
        var http = mock(HttpClient.class);
        var client = new NacosConfigurationClient(json, http);
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "url", "http://nacos.test/nacos");
        ReflectionTestUtils.setField(client, "identityKey", "x-nacos-identity");
        ReflectionTestUtils.setField(client, "identityValue", "identity-private");
        List<HttpRequest> calls = new ArrayList<>();
        when(http.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(
                        invocation -> {
                            HttpRequest request = invocation.getArgument(0);
                            calls.add(request);
                            if (request.uri().getPath().endsWith("/history/list"))
                                return response(
                                        """
{"code":0,"data":{"pageItems":[{"id":12,"srcUser":"admin",
"modifyTime":1788649999000,"opType":"U","content":"private-value"}]}}
""");
                            return response(
                                    """
{"code":0,"data":{"dataId":"ops-auth-service.yaml","groupName":"DEFAULT_GROUP",
"type":"yaml","content":"password: private-value"}}
""");
                        });
        var history = client.history("ops-auth-service.yaml", "DEFAULT_GROUP");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).id()).isEqualTo(12);
        assertThat(json.writeValueAsString(history))
                .doesNotContain("private-value", "identity-private");
        var version = client.version("ops-auth-service.yaml", "DEFAULT_GROUP", 12);
        assertThat(new ConfigurationMasker(json).mask(version.value(), version.type()))
                .doesNotContain("private-value");
        assertThatThrownBy(() -> client.version("ops-rag-service.yaml", "DEFAULT_GROUP", 12))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls)
                .allSatisfy(
                        request -> {
                            assertThat(request.uri().getHost()).isEqualTo("nacos.test");
                            assertThat(request.headers().firstValue("x-nacos-identity"))
                                    .contains("identity-private");
                            assertThat(request.uri().getQuery()).contains("namespaceId=public");
                        });
    }

    @Test
    void unavailableNacosDoesNotHideDeploymentSourcesOrClaimZeroConfigurations() throws Exception {
        var actor = new OpsPrincipal(2, "ops", "test", List.of("OPS"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        var nacos = mock(NacosConfigurationClient.class);
        when(nacos.enabled()).thenReturn(true);
        when(nacos.sourceInstanceId()).thenReturn("nacos-test");
        when(nacos.environment()).thenReturn("test");
        when(nacos.catalog()).thenThrow(new IllegalStateException("secret-response"));
        var service = new ConfigCenterService(nacos, new ConfigurationMasker(json));
        var catalog = service.catalog("ops-rag-service");
        var summary = service.summary("ops-rag-service");
        assertThat(catalog.status()).isEqualTo("UPSTREAM_UNAVAILABLE");
        assertThat(catalog.items())
                .extracting(ConfigCenterDtos.Item::source)
                .contains("ENV", "EXTERNAL", "SECRET");
        assertThat(summary.configurationCount()).isNull();
        assertThat(json.writeValueAsString(catalog)).doesNotContain("secret-response");
        assertThat(catalog.items()).noneMatch(ConfigCenterDtos.Item::editable);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(String body) {
        var response = (HttpResponse<String>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    @Test
    void identicalNacosMarkersDoNotReplaceActualRuntimeParametersOrImplyConnectionHealth()
            throws Exception {
        var actor = new OpsPrincipal(1, "admin", "test", List.of("ADMIN"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        var nacos = mock(NacosConfigurationClient.class);
        when(nacos.sourceInstanceId()).thenReturn("nacos-test");
        when(nacos.environment()).thenReturn("test");
        when(nacos.namespace()).thenReturn("public");
        when(nacos.catalog())
                .thenReturn(
                        List.of(
                                new NacosConfigurationClient.Entry(
                                        "ops-rag-service.yaml", "DEFAULT_GROUP", "yaml", "")));
        when(nacos.content("ops-rag-service.yaml", "DEFAULT_GROUP"))
                .thenReturn(
                        new NacosConfigurationClient.Content(
                                "info:\n  middleware:\n    nacos-config: connected",
                                "yaml",
                                "a".repeat(64),
                                true));
        var runtime = mock(ConfigurationRuntimeClient.class);
        var field =
                new com.opsagent.common.security.RuntimeConfigurationSnapshot.Field(
                        "ops.rag.top-k", "检索条数", "模型与检索", "9", "环境变量", "RUNTIME_RESOLVED");
        when(runtime.snapshot("ops-rag-service"))
                .thenReturn(
                        new ConfigCenterDtos.Overview(
                                "AVAILABLE",
                                "ops-rag-service",
                                "process-id",
                                java.time.Instant.now(),
                                "单实例解析值不等同业务已应用",
                                List.of(field)));
        var service = new ConfigCenterService(nacos, new ConfigurationMasker(json), runtime);
        String id = service.catalog("ops-rag-service").items().get(0).id();
        var detail = service.detail(id);
        assertThat(detail.content()).contains("connected");
        assertThat(detail.overview().fields()).containsExactly(field);
        assertThat(detail.overview().message()).contains("不等同");
        when(runtime.snapshot("ops-rag-service"))
                .thenReturn(
                        ConfigurationRuntimeClient.unavailable(
                                "ops-rag-service", "UNAVAILABLE", "运行快照未核实。"));
        var missing = service.detail(id);
        assertThat(missing.overview().fields()).isEmpty();
        assertThat(missing.overview().message()).contains("静态配置文字", "不能证明连接正常");
        when(nacos.content("ops-rag-service.yaml", "DEFAULT_GROUP"))
                .thenReturn(
                        new NacosConfigurationClient.Content(
                                "ops:\n  rag:\n    top-k: 4", "yaml", "b".repeat(64), true));
        var sourceOnly = service.detail(id).overview();
        assertThat(sourceOnly.status()).isEqualTo("UNAVAILABLE");
        assertThat(sourceOnly.fields())
                .singleElement()
                .satisfies(
                        sourceField -> {
                            assertThat(sourceField.value()).isEqualTo("4");
                            assertThat(sourceField.verification()).isEqualTo("SOURCE_ONLY");
                        });
    }

    @Test
    void completeIdentitySeparatesDifferentSourcesAndNoTemplateOrPermissionOverclaim()
            throws Exception {
        var actor = new OpsPrincipal(1, "admin", "test", List.of("ADMIN"));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
        var nacos = mock(NacosConfigurationClient.class);
        when(nacos.enabled()).thenReturn(true);
        when(nacos.sourceInstanceId()).thenReturn("nacos-main");
        when(nacos.environment()).thenReturn("test-a");
        when(nacos.namespace()).thenReturn("namespace-a");
        when(nacos.catalog())
                .thenReturn(
                        List.of(
                                new NacosConfigurationClient.Entry(
                                        "ops-demo-order-business.json",
                                        "OPSAGENT_DEMO",
                                        "json",
                                        ""),
                                new NacosConfigurationClient.Entry(
                                        "ops-demo-order-runtime.json",
                                        "OPSAGENT_DEMO",
                                        "json",
                                        "")));
        when(nacos.content("ops-demo-order-business.json", "OPSAGENT_DEMO"))
                .thenReturn(
                        new NacosConfigurationClient.Content(
                                "{\"title\":\"A\",\"password\":\"hidden\"}",
                                "json",
                                "a".repeat(64),
                                true));
        when(nacos.content("ops-demo-order-runtime.json", "OPSAGENT_DEMO"))
                .thenReturn(
                        new NacosConfigurationClient.Content(
                                "{\"title\":\"B\"}", "json", "b".repeat(64), true));
        var service = new ConfigCenterService(nacos, new ConfigurationMasker(json));
        var rows = service.catalog(DemoTargetDtos.TARGET).items();
        var a = service.detail(rows.get(0).id());
        var b = service.detail(rows.get(1).id());
        assertThat(a.item().identity().namespaceId()).isEqualTo("namespace-a");
        assertThat(a.content()).contains("A").doesNotContain("hidden");
        assertThat(b.content()).contains("B");
        assertThat(b.item().capabilities().canEdit()).isFalse();
        ReflectionTestUtils.setField(
                service,
                "bindingsJson",
                "{\"namespace-a|OPSAGENT_DEMO|ops-demo-order-business.json\":["
                        + "\"ops-demo-order-service\",\"ops-demo-notification-service\"]}");
        var shared = service.catalog("ops-demo-notification-service").items().get(0);
        assertThat(shared.shared()).isTrue();
        assertThat(shared.identity().targetScope())
                .containsExactly("ops-demo-notification-service", "ops-demo-order-service");
        assertThat(shared.capabilities().canPublish()).isFalse();
        ReflectionTestUtils.setField(service, "bindingsJson", "{}");
        when(nacos.namespace()).thenReturn("namespace-b");
        assertThat(service.catalog(DemoTargetDtos.TARGET).items().get(0).id())
                .isNotEqualTo(a.item().id());
        assertThatThrownBy(() -> service.detail(a.item().id()))
                .isInstanceOf(com.opsagent.common.core.BusinessException.class);
        String currentId = service.catalog(DemoTargetDtos.TARGET).items().get(0).id();
        for (String failure :
                List.of(
                        "NOT_FOUND",
                        "FORBIDDEN",
                        "UPSTREAM_UNAVAILABLE",
                        "UNSUPPORTED",
                        "INVALID_CONTENT")) {
            doThrow(new NacosConfigurationClient.SourceFailure(failure))
                    .when(nacos)
                    .content("ops-demo-order-business.json", "OPSAGENT_DEMO");
            var failed = service.detail(currentId);
            assertThat(failed.status()).isEqualTo(failure);
            assertThat(failed.content()).isEmpty();
        }
    }
}
