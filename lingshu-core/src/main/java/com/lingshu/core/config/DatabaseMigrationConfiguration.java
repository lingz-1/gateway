package com.lingshu.core.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "lingshu.database", name = "migration-enabled", havingValue = "true")
public class DatabaseMigrationConfiguration {

    @Bean(initMethod = "migrate")
    public Flyway lingshuFlyway(LingShuProperties properties) {
        LingShuProperties.Billing billing = properties.getBilling();
        return Flyway.configure()
                .dataSource(billing.getJdbcUrl(), billing.getUsername(), billing.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }
}
