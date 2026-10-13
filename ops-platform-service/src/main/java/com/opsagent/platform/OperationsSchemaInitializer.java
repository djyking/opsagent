package com.opsagent.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 演示部署启动时幂等创建独立工作流表，可在外部执行同一迁移后关闭自动初始化。
 *
 * @author heyu
 * @since 2026/9/3
 */
@Component
public class OperationsSchemaInitializer implements ApplicationRunner {
    private final DataSource dataSource;

    @Value("${ops.operations.initialize-schema:true}")
    private boolean initializeSchema;

    OperationsSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if (initializeSchema) {
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                    new ClassPathResource("operations-schema.sql"), new ClassPathResource("demo-target-schema.sql"));
            populator.setSqlScriptEncoding("UTF-8");
            populator.execute(dataSource);
        }
    }
}
