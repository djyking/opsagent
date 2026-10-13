package com.opsagent.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;
import com.opsagent.common.security.OpsPrincipal;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real proposal persistence, immutable approval scope and no direct-publication bypass.
 *
 * @author heyu
 * @since 2026/9/3
 */
class ConfigurationProposalTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ConfigCenterService center = mock(ConfigCenterService.class);
    private final ManagedConfigurationService managed = mock(ManagedConfigurationService.class);
    private final ManagedConfigurationRepository publications =
            mock(ManagedConfigurationRepository.class);
    private ConfigurationProposalService service;
    private ConfigurationProposalRepository repository;
    private ConfigCenterDtos.Identity identity;
    private JsonNode before;
    private JsonNode application;

    @BeforeEach
    void setup() {
        login(1, "ADMIN");
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:h2:mem:proposal-"
                                        + UUID.randomUUID()
                                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                                "sa",
                                ""));
        jdbc.execute(
                "CREATE TABLE operations_managed_config_proposal(proposal_id VARCHAR(36) PRIMARY"
                        + " KEY,request_id VARCHAR(36) UNIQUE,request_hash VARCHAR(64),owner_id"
                        + " BIGINT,immutable_digest VARCHAR(64),proposal_json TEXT,expires_at"
                        + " TIMESTAMP,run_id VARCHAR(36))");
        repository = new ConfigurationProposalRepository(jdbc, json);
        service = new ConfigurationProposalService(center, managed, publications, repository);
        identity =
                new ConfigCenterDtos.Identity(
                        "NACOS",
                        "nacos-test",
                        "test",
                        "public",
                        "OPSAGENT_DEMO",
                        "ops-demo-order-business.json",
                        List.of(DemoTargetDtos.TARGET));
        var capabilities =
                new ConfigCenterDtos.Capabilities(true, true, true, true, true, true, Map.of());
        when(center.resolve("catalog-id"))
                .thenReturn(
                        new ConfigCenterDtos.Item(
                                "catalog-id",
                                "business",
                                DemoTargetDtos.TARGET,
                                "NACOS",
                                "public",
                                "OPSAGENT_DEMO",
                                "ops-demo-order-business.json",
                                "json",
                                true,
                                "",
                                "AVAILABLE",
                                "",
                                "",
                                identity,
                                capabilities,
                                false));
        before =
                json.createObjectNode()
                        .put("catalogTitle", "Original")
                        .put("notice", "keep-me")
                        .put("discountPercent", 0);
        application =
                json.createObjectNode()
                        .put("instanceId", UUID.randomUUID().toString())
                        .put("targetCode", DemoTargetDtos.TARGET)
                        .put("namespaceId", "public")
                        .put("sourceInstanceId", "nacos-test")
                        .put("observedAt", Instant.now().toString());
        when(managed.detail("order-business"))
                .thenReturn(
                        new ManagedConfigurationDtos.Detail(
                                "order-business",
                                "business",
                                "OPSAGENT_DEMO",
                                "ops-demo-order-business.json",
                                true,
                                "",
                                before,
                                "a".repeat(64),
                                "a".repeat(64),
                                "APPLIED",
                                "AVAILABLE",
                                true,
                                "",
                                Instant.now(),
                                json.createObjectNode().put("httpStatus", 200),
                                application));
        when(managed.applicationSnapshot()).thenReturn(application);
        when(managed.validate(eq("order-business"), any()))
                .thenAnswer(
                        call ->
                                new ManagedConfigurationDtos.Validated(
                                        true, call.getArgument(1), ""));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void immutablePatchPreservesOmittedFieldsAndCreatesNoApprovalOrWrite() {
        var request = request("replace", "/discountPercent", json.getNodeFactory().numberNode(10));
        var proposal = service.propose("catalog-id", request);
        assertThat(proposal.desired().path("notice").asText()).isEqualTo("keep-me");
        assertThat(proposal.desired().path("discountPercent").asInt()).isEqualTo(10);
        assertThat(proposal.identity()).isEqualTo(identity);
        assertThat(proposal.changes()).hasSize(1);
        assertThat(proposal.immutableDigest()).matches("[a-f0-9]{64}");
        assertThat(service.propose("catalog-id", request)).isEqualTo(proposal);
        verify(managed, never()).publish(anyString(), any());
        var changed =
                new ConfigurationProposalDtos.Create(
                        request.expectedRevision(),
                        request.requestId(),
                        "changed",
                        request.patch(),
                        null);
        assertThatThrownBy(() -> service.propose("catalog-id", changed))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dangerousDeleteNullMaskAndDuplicateFieldsAreRejectedBeforeAnyWrite() {
        for (var request :
                List.of(
                        request("remove", "/notice", null),
                        request("replace", "/password", json.getNodeFactory().textNode("secret")),
                        request("replace", "/notice", json.nullNode()),
                        request("replace", "/notice", json.getNodeFactory().textNode("******")))) {
            assertThatThrownBy(() -> service.propose("catalog-id", request))
                    .isInstanceOf(BusinessException.class);
        }
        var empty =
                service.propose(
                        "catalog-id",
                        request("replace", "/notice", json.getNodeFactory().textNode("")));
        assertThat(empty.desired().path("notice").asText()).isEmpty();
        verify(managed, never()).publish(anyString(), any());
    }

    @Test
    void executionBindsExactDigestOwnerTargetRunAndCasVersion() {
        var proposal =
                service.propose(
                        "catalog-id",
                        request(
                                "replace",
                                "/discountPercent",
                                json.getNodeFactory().numberNode(10)));
        var actor = context(UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.apply(proposal.proposalId(), "b".repeat(64), actor))
                .isInstanceOf(BusinessException.class);
        service.apply(proposal.proposalId(), proposal.immutableDigest(), actor);
        verify(managed)
                .publish(
                        "order-business",
                        new ManagedConfigurationDtos.Publish(
                                proposal.desired(),
                                "a".repeat(64),
                                proposal.proposalId(),
                                proposal.comment()));
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        proposal.proposalId(),
                                        proposal.immutableDigest(),
                                        context(UUID.randomUUID().toString())))
                .isInstanceOf(BusinessException.class);
        login(2, "ADMIN");
        assertThatThrownBy(() -> service.read(proposal.proposalId()))
                .isInstanceOf(BusinessException.class);
        login(1, "OPS");
        assertThatThrownBy(() -> service.read(proposal.proposalId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void targetInstanceAndSourceNamespaceChangesInvalidateApprovedIntent() {
        var proposal =
                service.propose(
                        "catalog-id",
                        request(
                                "replace",
                                "/discountPercent",
                                json.getNodeFactory().numberNode(10)));
        ((com.fasterxml.jackson.databind.node.ObjectNode) application)
                .put("instanceId", UUID.randomUUID().toString());
        assertThatThrownBy(
                        () ->
                                service.apply(
                                        proposal.proposalId(),
                                        proposal.immutableDigest(),
                                        context(UUID.randomUUID().toString())))
                .isInstanceOf(BusinessException.class);
        ((com.fasterxml.jackson.databind.node.ObjectNode) application).put("namespaceId", "other");
        assertThatThrownBy(
                        () ->
                                service.propose(
                                        "catalog-id",
                                        request(
                                                "replace",
                                                "/notice",
                                                json.getNodeFactory().textNode("new"))))
                .isInstanceOf(BusinessException.class);
        verify(managed, never()).publish(anyString(), any());
    }

    @Test
    void oldPublicEndpointsRejectEvenAdminAndNeverInvokeExecutor() {
        var controller = new ManagedConfigurationController(managed);
        assertThatThrownBy(
                        () ->
                                controller.publish(
                                        "order-business",
                                        new ManagedConfigurationDtos.Publish(
                                                before,
                                                "a".repeat(64),
                                                UUID.randomUUID().toString(),
                                                "bypass")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                controller.rollback(
                                        "order-business",
                                        new ManagedConfigurationDtos.Rollback(
                                                1,
                                                "a".repeat(64),
                                                UUID.randomUUID().toString(),
                                                "bypass")))
                .isInstanceOf(BusinessException.class);
        verify(managed, never()).publish(anyString(), any());
        verify(managed, never()).rollback(anyString(), any());
    }

    private ConfigurationProposalDtos.Create request(String op, String path, JsonNode value) {
        return new ConfigurationProposalDtos.Create(
                "a".repeat(64),
                UUID.randomUUID().toString(),
                "reviewed",
                List.of(new ConfigurationProposalDtos.Patch(op, path, value)),
                null);
    }

    private InternalActorTokens.Context context(String run) {
        return new InternalActorTokens.Context(
                1,
                "admin",
                List.of("ADMIN"),
                run,
                DemoTargetDtos.TARGET,
                Instant.now().plusSeconds(90));
    }

    private void login(long id, String role) {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new OpsPrincipal(id, "admin", "test", List.of(role)),
                                null,
                                List.of()));
    }
}
