package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.cache.exact",
        name = "store",
        havingValue = "redis"
)
public class RedisExactCacheStore implements ExactCacheStore {

    static final String KEY_PREFIX = "lingshu:cache:exact:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisExactCacheStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            LingShuProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = properties.getCache().getExact().getTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Cache TTL must be positive");
        }
    }

    @Override
    public Optional<ProviderResponse> get(String key) {
        String value = redisTemplate.opsForValue().get(redisKey(key));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, ProviderResponse.class));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot deserialize exact cache entry", exception);
        }
    }

    @Override
    public void put(String key, ProviderResponse response) {
        try {
            String value = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(redisKey(key), value, ttl);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize exact cache entry", exception);
        }
    }

    private String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
