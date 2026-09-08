package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisExactCacheStoreTest {

    @Test
    void writesAndReadsProviderResponseWithTtl() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);

        LingShuProperties properties = new LingShuProperties();
        properties.getCache().getExact().setTtl(Duration.ofMinutes(3));
        RedisExactCacheStore store = new RedisExactCacheStore(
                template,
                JsonMapper.builder().build(),
                properties
        );
        ProviderResponse response = new ProviderResponse("stub", "stub-echo-v1", "answer", 2, 1);

        store.put("hash", response);
        verify(values).set(
                RedisExactCacheStore.KEY_PREFIX + "hash",
                "{\"provider\":\"stub\",\"model\":\"stub-echo-v1\",\"content\":\"answer\",\"inputTokens\":2,\"outputTokens\":1}",
                Duration.ofMinutes(3)
        );

        when(values.get(RedisExactCacheStore.KEY_PREFIX + "hash"))
                .thenReturn("{\"provider\":\"stub\",\"model\":\"stub-echo-v1\",\"content\":\"answer\",\"inputTokens\":2,\"outputTokens\":1}");
        assertEquals(response, store.get("hash").orElseThrow());
    }

    @Test
    void returnsEmptyWhenKeyDoesNotExist() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);

        RedisExactCacheStore store = new RedisExactCacheStore(
                template,
                JsonMapper.builder().build(),
                new LingShuProperties()
        );

        assertTrue(store.get("missing").isEmpty());
    }
}
