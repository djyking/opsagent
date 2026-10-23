package com.opsagent.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

/**
 * @author heyu
 * @since 2026/9/3
 */
class InternalAgentSchemaTest {
    @Test
    void migratedSchemaStartsWithoutAnyDdlAndMissingSchemaFailsClosed() {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = spy(new JdbcTemplate(ds));
        var store = new InternalAgentStore(jdbc, new ObjectMapper());
        ReflectionTestUtils.setField(store, "autoInitialize", false);
        assertThatThrownBy(store::initialize)
                .hasMessageContaining("RAG_INTERNAL_AGENT_SCHEMA_MIGRATION_REQUIRED");
        verify(jdbc, never()).execute(anyString());
        new ResourceDatabasePopulator(new ClassPathResource("internal-agent-schema.sql"))
                .execute(ds);
        store.initialize();
        verify(jdbc, never()).execute(anyString());
    }
}
