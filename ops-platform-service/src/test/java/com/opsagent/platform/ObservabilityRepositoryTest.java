package com.opsagent.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.OpsPrincipal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 用真实 SQL 验证 CMDB 逻辑删除、关系维护、历史与身份隔离，并经过方法安全代理检查写权限。
 *
 * @author heyu
 * @since 2026/9/3
 */
class ObservabilityRepositoryTest {
    private JdbcTemplate jdbc;
    private ItsmPlatformService cmdb;
    private ObservabilityRepository observations;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:obs-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("observability-schema.sql"),
                new ClassPathResource("demo-target-schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE cmdb_ci(id BIGINT AUTO_INCREMENT PRIMARY KEY,ci_code VARCHAR(64) UNIQUE,"
                + "ci_name VARCHAR(128),ci_type VARCHAR(32),environment VARCHAR(32),owner_name VARCHAR(64),"
                + "endpoint VARCHAR(255),status VARCHAR(16),description VARCHAR(500),create_time TIMESTAMP,"
                + "update_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE cmdb_relation(id BIGINT AUTO_INCREMENT PRIMARY KEY,source_ci_code VARCHAR(64),"
                + "target_ci_code VARCHAR(64),relation_type VARCHAR(32),description VARCHAR(500),create_time TIMESTAMP,"
                + "UNIQUE(source_ci_code,target_ci_code,relation_type))");
        cmdb = new ItsmPlatformService(new ItsmPlatformRepository(jdbc, new SimpleMeterRegistry()),
                mock(PlatformAuditRepository.class), json);
        observations = new ObservabilityRepository(jdbc, json);
        actor("ADMIN", 1);
        jdbc.update("INSERT INTO cmdb_ci(ci_code,ci_name,ci_type,environment,status)"
                + " VALUES('a','Service A','SERVICE','PROD','ACTIVE'),('b','Service B','SERVICE','PROD','ACTIVE')");
        jdbc.update("INSERT INTO cmdb_relation(source_ci_code,target_ci_code,relation_type)"
                + " VALUES('a','b','CALLS')");
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void relationsAreEditableAndLogicallyDeletedAndCiDeletionKeepsStoredRecords() {
        assertThat(cmdb.relations()).hasSize(1);
        cmdb.updateRelation(1, new ItsmPlatformController.RelationRequest("a", "b", "READS_FROM", "lookup"));
        assertThat(cmdb.relations().get(0).get("relationType")).isEqualTo("READS_FROM");
        cmdb.deleteRelation(1);
        assertThat(cmdb.relations()).isEmpty();
        cmdb.addRelation(new ItsmPlatformController.RelationRequest("a", "b", "READS_FROM", "restored"));
        assertThat(cmdb.relations()).hasSize(1);
        cmdb.deleteCi(1);
        assertThat(cmdb.cis(null, null)).hasSize(1);
        assertThat(cmdb.relations()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cmdb_ci", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> cmdb.ci("a")).isInstanceOf(BusinessException.class);
    }

    @Test
    void metadataRoundTripsAndStableCodesAndCredentialUrlsAreProtected() {
        var request = new ItsmPlatformController.CiRequest("a", "Service A", "SERVICE", "PROD", "SRE",
                "https://example.test", "ACTIVE", "", "OpsAgent", List.of("core"),
                new ObservabilityDtos.Bindings("service-a", "a", "a.yaml", "a"));
        cmdb.updateCi(1, request);
        assertThat(json.valueToTree(cmdb.ci("a")).path("bindings").path("prometheusJob").asText())
                .isEqualTo("service-a");
        assertThat(cmdb.cis("Service", null)).hasSize(2);
        var renamed = new ItsmPlatformController.CiRequest("changed", "A", "SERVICE", "PROD", "SRE",
                "", "ACTIVE", "");
        assertThatThrownBy(() -> cmdb.updateCi(1, renamed)).isInstanceOf(BusinessException.class);
        var secrets = new ItsmPlatformController.CiRequest("c", "C", "SERVICE", "PROD", "SRE",
                "https://host?token=secret", "ACTIVE", "");
        assertThatThrownBy(() -> cmdb.addCi(secrets)).isInstanceOf(BusinessException.class);
    }

    @Test
    void layoutsAndInspectionHistoryArePersistedWithoutChangingCurrentHealth() {
        var layout = new ObservabilityDtos.Layout("PROD", List.of(new ObservabilityDtos.Position("a", 15, 20)));
        observations.layout(layout, 1);
        assertThat(observations.layout("PROD")).containsKey("a");
        Map<String, Object> node = new LinkedHashMap<>(Map.of("ciCode", "a", "health", "CRITICAL",
                "statusReason", "Probe failed", "metrics", Map.of()));
        observations.inspection(node, 50, 1, "MANUAL", null);
        observations.inspection(node, 50, 1, "WORKFLOW", 9L);
        assertThat(observations.consecutiveFailures("a")).isEqualTo(2);
        node.put("health", "HEALTHY");
        observations.inspection(node, 35, 1, "MANUAL", null);
        assertThat(observations.consecutiveFailures("a")).isZero();
        assertThat(observations.history("a")).hasSize(3);
        assertThat(observations.history("a").get(0).get("status")).isEqualTo("SUCCESS");
        assertThat(observations.today().get("total")).isEqualTo(3L);
    }

    @Test
    void foreignPrivateDrillIsNotIncludedInDrawer() {
        jdbc.update("INSERT INTO operations_demo_incident(incident_id,target_code,scenario_code,owner_id,owner_name,"
                + "owner_kind,status,started_at,expires_at) VALUES('i','a','test',2,'owner','USER','ACTIVE',"
                + "CURRENT_TIMESTAMP,DATEADD('SECOND',600,CURRENT_TIMESTAMP))");
        assertThat(observations.recentRuns("a", actor("OPS", 3))).isEmpty();
        assertThat(observations.recentRuns("a", actor("OPS", 2))).hasSize(1);
        assertThat(observations.recentRuns("a", actor("ADMIN", 1))).hasSize(1);
    }

    @Test
    void opsCannotEditTopologyEvenWhenCallingApiDirectly() {
        var service = mock(TopologyAggregationService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(SecurityConfig.class);
            context.registerBean(ObservabilityController.class,
                    () -> new ObservabilityController(service, mock(ObservabilityInspectionService.class)));
            context.refresh();
            actor("OPS", 2);
            var request = new ObservabilityDtos.Layout("ALL", List.of());
            assertThatThrownBy(() -> context.getBean(ObservabilityController.class).layout(request))
                    .isInstanceOf(AccessDeniedException.class);
            verify(service, never()).saveLayout(any());
            actor("ADMIN", 1);
            context.getBean(ObservabilityController.class).layout(request);
            verify(service).saveLayout(request);
        }
    }

    private OpsPrincipal actor(String role, long id) {
        var actor = new OpsPrincipal(id, role.toLowerCase(), "test", List.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        return actor;
    }

    /** @author heyu */
    @Configuration
    @EnableMethodSecurity
    static class SecurityConfig {}
}
