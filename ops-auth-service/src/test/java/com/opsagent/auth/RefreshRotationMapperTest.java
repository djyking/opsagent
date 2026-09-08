package com.opsagent.auth;

import static org.assertj.core.api.Assertions.*;

import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 使用独立 H2 内存库核对刷新行锁、单次撤销与会话链字段映射。
 *
 * @author heyu
 * @since 2026/9/3
 */
class RefreshRotationMapperTest {
    @Test
    void concurrentRotationWaitsAndSeesTheRevokedPredecessor() throws Exception {
        PooledDataSource data =
                new PooledDataSource(
                        "org.h2.Driver",
                        "jdbc:h2:mem:refresh_"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        try (var connection = data.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE auth_refresh_token(token_id VARCHAR(36),user_id BIGINT,token_hash"
                        + " VARCHAR(64) PRIMARY KEY,expire_time TIMESTAMP,revoked"
                        + " TINYINT,revoke_time TIMESTAMP,create_time TIMESTAMP,session_id"
                        + " VARCHAR(36),session_started_at TIMESTAMP,last_activity_at"
                        + " TIMESTAMP,absolute_expires_at TIMESTAMP)");
        }
        Configuration configuration =
                new Configuration(new Environment("test", new JdbcTransactionFactory(), data));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(RefreshTokenMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        try (var seed = factory.openSession()) {
            seed.getMapper(RefreshTokenMapper.class)
                    .insertSession(
                            "old",
                            42L,
                            "old-hash",
                            now.plusHours(2),
                            now,
                            "session",
                            now,
                            now,
                            now.plusHours(24));
            seed.commit();
        }
        var executor = Executors.newSingleThreadExecutor();
        try (var first = factory.openSession()) {
            var mapper = first.getMapper(RefreshTokenMapper.class);
            var before = mapper.lockLease("old-hash");
            assertThat(before.revoked()).isFalse();
            assertThat(before.sessionStartedAt()).isEqualTo(now);
            CountDownLatch attempting = new CountDownLatch(1);
            var competing =
                    executor.submit(
                            () -> {
                                try (var second = factory.openSession()) {
                                    attempting.countDown();
                                    var secondMapper = second.getMapper(RefreshTokenMapper.class);
                                    var lease = secondMapper.lockLease("old-hash");
                                    int revoked = secondMapper.revoke("old-hash");
                                    second.commit();
                                    return lease.revoked() && revoked == 0;
                                }
                            });
            assertThat(attempting.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> competing.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(mapper.revoke("old-hash")).isEqualTo(1);
            mapper.insertSession(
                    "new",
                    42L,
                    "new-hash",
                    now.plusHours(2),
                    now,
                    "session",
                    now,
                    now,
                    now.plusHours(24));
            first.commit();
            assertThat(competing.get(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
        try (var check = factory.openSession()) {
            var mapper = check.getMapper(RefreshTokenMapper.class);
            assertThat(mapper.validUser("old-hash")).isNull();
            assertThat(mapper.validUser("new-hash")).isEqualTo(42L);
            assertThat(mapper.revokeSession("session")).isEqualTo(1);
            assertThat(mapper.validUser("new-hash")).isNull();
            check.commit();
        } finally {
            data.forceCloseAll();
        }
    }
}
