package com.lingshu.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "lingshu.billing.virtual", name = "persistence-enabled", havingValue = "true")
public class VirtualBillingConfiguration {

    @Bean(name = "virtualBillingDataSource", destroyMethod = "close")
    public HikariDataSource virtualBillingDataSource(LingShuProperties properties) {
        LingShuProperties.Billing billing = properties.getBilling();
        HikariConfig config = new HikariConfig();
        config.setPoolName("lingshu-virtual-billing");
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
