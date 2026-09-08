package com.lingshu.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "lingshu.billing",
        name = "enabled",
        havingValue = "true"
)
public class BillingConfiguration {

    @Bean(name = "billingDataSource", destroyMethod = "close")
    public HikariDataSource billingDataSource(LingShuProperties properties) {
        LingShuProperties.Billing billing = properties.getBilling();
        HikariConfig config = new HikariConfig();
        config.setPoolName("lingshu-billing");
        config.setJdbcUrl(billing.getJdbcUrl());
        config.setUsername(billing.getUsername());
        config.setPassword(billing.getPassword());
        config.setMaximumPoolSize(billing.getMaximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3_000);
        config.setInitializationFailTimeout(5_000);
        return new HikariDataSource(config);
    }
}
