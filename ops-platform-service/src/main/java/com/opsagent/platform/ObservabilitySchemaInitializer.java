package com.opsagent.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 幂等添加观测辅助表，原 CMDB、事件与历史数据保持独立。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
class ObservabilitySchemaInitializer implements ApplicationRunner {
    private final DataSource dataSource;

    @Value("${ops.operations.initialize-schema:true}")
    private boolean enabled;

    ObservabilitySchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (!enabled) return;
        var populator = new ResourceDatabasePopulator(new ClassPathResource("observability-schema.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.execute(dataSource);
    }
}
