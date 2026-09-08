package com.lingshu.core.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "lingshu.tenant-policy.nacos",
        name = "enabled",
        havingValue = "true"
)
public class NacosTenantPolicyConfiguration {
}
