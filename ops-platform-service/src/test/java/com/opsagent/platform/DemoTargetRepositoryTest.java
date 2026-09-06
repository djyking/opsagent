package com.opsagent.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opsagent.common.core.BusinessException;
import com.opsagent.common.security.InternalActorTokens;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通过真实数据库事务验证目标租约、动作幂等冲突和可信告警归属窗口。
 *
 * @author heyu
 * @since 2026/9/3
 */
class DemoTargetRepositoryTest {
    private DemoTargetRepository repository;
    private final InternalActorTokens.Context actor =
            new InternalActorTokens.Context(
                    -101,
                    "visitor",
                    List.of("DEMO"),
                    "run-1",
                    DemoTargetDtos.TARGET,
                    Instant.now().plusSeconds(600));

    @BeforeEach
    void setup() {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:demo-target-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("demo-target-schema.sql"))
                .execute(source);
        repository =
                new DemoTargetRepository(
                        new JdbcTemplate(source),
                        new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    @Test
    void onlyOneActorCanHoldTargetAndOwnerResolverUsesEpisodeTime() {
        var row =
                repository.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        assertThat(repository.active().incidentId()).isEqualTo(row.incidentId());
        assertThatThrownBy(
                        () ->
                                repository.create(
                                        new DemoTargetDtos.CreateScenario(
                                                "SENTINEL_RULE_REGRESSION", 600),
                                        actor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TARGET_BUSY");
        assertThat(repository.owner(row.startedAt().plusSeconds(30)).ownerId())
                .isEqualTo(actor.userId());
        assertThatThrownBy(() -> repository.owner(row.startedAt().minusSeconds(10)))
                .isInstanceOf(BusinessException.class);
        assertThat(repository.page(-102, false)).isEmpty();
    }

    @Test
    void recoveryRequiresBusinessSuccessAndLabelsGuardRecoverySeparately() {
        var row =
                repository.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        repository.observed(
                row.incidentId(),
                "a".repeat(64),
                503,
                "REDIS_CONNECT_FAILED",
                "TTL_GUARD",
                Instant.now());
        assertThat(repository.active()).isNotNull();
        repository.observed(
                row.incidentId(),
                "b".repeat(64),
                200,
                "ORDER_PREVIEW_READY",
                "TTL_GUARD",
                Instant.now());
        assertThat(repository.active()).isNull();
        assertThat(repository.get(row.incidentId()).status()).isEqualTo("EXPIRED_RECOVERED");
        assertThat(repository.get(row.incidentId()).recoverySource()).isEqualTo("TTL_GUARD");
    }

    @Test
    void idempotencyCannotBeReusedForAnotherActorRunOrAction() {
        var row =
                repository.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        var action =
                new DemoTargetDtos.Action(
                        row.incidentId(), "RESTORE_CONFIGURATION", "a".repeat(64), "key-1");
        assertThat(repository.reserveAction(action, actor)).isEmpty();
        repository.actionResult("key-1", "VERIFIED", "{\"recoveryVerified\":true}");
        assertThat(repository.reserveAction(action, actor).get("status")).isEqualTo("VERIFIED");
        var anotherRun =
                new InternalActorTokens.Context(
                        actor.userId(),
                        actor.username(),
                        actor.roles(),
                        "run-2",
                        actor.targetCode(),
                        actor.validUntil());
        assertThatThrownBy(() -> repository.reserveAction(action, anotherRun))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void independentTargetsKeepLeasesOwnersCooldownsAndHistorySeparate() {
        var order =
                repository.create(
                        new DemoTargetDtos.CreateScenario("NACOS_REDIS_CONFIG_DRIFT", 600), actor);
        var notificationActor =
                new InternalActorTokens.Context(
                        actor.userId(),
                        actor.username(),
                        actor.roles(),
                        "notification-run",
                        DemoTargetDtos.NOTIFICATION_TARGET,
                        actor.validUntil());
        var notification =
                repository.create(
                        new DemoTargetDtos.CreateScenario("RABBITMQ_CONSUMER_PAUSED", 600),
                        notificationActor);
        assertThat(repository.active(DemoTargetDtos.NOTIFICATION_TARGET).incidentId())
                .isEqualTo(notification.incidentId());
        assertThat(repository.page(actor.userId(), false, DemoTargetDtos.TARGET))
                .extracting("incidentId")
                .containsExactly(order.incidentId());
        assertThat(
                        repository
                                .owner(
                                        DemoTargetDtos.NOTIFICATION_TARGET,
                                        notification.startedAt().plusSeconds(20))
                                .incidentId())
                .isEqualTo(notification.incidentId());
        repository.observed(
                notification.incidentId(),
                "c".repeat(64),
                200,
                "NOTIFICATION_DELIVERED",
                "AGENT_TOOL",
                Instant.now());
        assertThat(repository.active(DemoTargetDtos.NOTIFICATION_TARGET)).isNull();
        assertThat(repository.availableAfter(DemoTargetDtos.NOTIFICATION_TARGET)).isNotNull();
        assertThat(repository.active().incidentId()).isEqualTo(order.incidentId());
        assertThat(repository.availableAfter()).isNull();
    }
}
