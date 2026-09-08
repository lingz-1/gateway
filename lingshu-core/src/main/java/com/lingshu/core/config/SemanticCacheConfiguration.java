package com.lingshu.core.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "lingshu.cache.semantic",
        name = "enabled",
        havingValue = "true"
)
public class SemanticCacheConfiguration {

    @Bean(name = "semanticCacheDataSource", destroyMethod = "close")
    public HikariDataSource semanticCacheDataSource(LingShuProperties properties) {
        LingShuProperties.Semantic semantic = properties.getCache().getSemantic();
        HikariConfig config = new HikariConfig();
        config.setPoolName("lingshu-semantic-cache");
        config.setJdbcUrl(semantic.getJdbcUrl());
        config.setUsername(semantic.getUsername());
        config.setPassword(semantic.getPassword());
        config.setMaximumPoolSize(semantic.getMaximumPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3_000);
        config.setInitializationFailTimeout(5_000);
        return new HikariDataSource(config);
    }
}
