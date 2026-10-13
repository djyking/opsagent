package com.opsagent.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Idempotent, isolated traffic publication history schema.
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class TrafficSchemaInitializer implements ApplicationRunner {
    private final DataSource source;

    @Value("${ops.operations.initialize-schema:true}")
    private boolean enabled;

    TrafficSchemaInitializer(DataSource source) {
        this.source = source;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        var populator =
                new ResourceDatabasePopulator(
                        new ClassPathResource("traffic-governance-schema.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(source);
    }
}
