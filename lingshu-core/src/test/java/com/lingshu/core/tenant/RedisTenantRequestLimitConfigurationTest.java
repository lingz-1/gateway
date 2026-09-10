package com.lingshu.core.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest(properties = "lingshu.tenant-policy.rate-limit.store=redis")
class RedisTenantRequestLimitConfigurationTest {

    @Autowired
    private TenantRequestLimitStore store;

    @Test
    void selectsRedisStore() {
        assertInstanceOf(RedisTenantRequestLimitStore.class, store);
    }
}
