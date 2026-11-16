package com.lingshu.core.streaming;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisStreamOwnerDirectoryTest {

    @Test
    void atomicallyRegistersAndResolvesOwner() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), eq(Duration.ofMinutes(5))))
                .thenReturn(true);
        LingShuProperties properties = distributedProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        StreamOwnerDirectory.StreamOwner owner = new StreamOwnerDirectory.StreamOwner(
                "core-a",
                "http://127.0.0.1:8081",
                "tenant-a"
        );
        when(values.get("lingshu:stream-owner:trace-1"))
                .thenReturn(objectMapper.writeValueAsString(owner));
        RedisStreamOwnerDirectory directory = new RedisStreamOwnerDirectory(
                redisTemplate,
                objectMapper,
                properties
        );

        assertTrue(directory.register("trace-1", "tenant-a"));
        assertEquals(owner, directory.find("trace-1").orElseThrow());
        verify(values).setIfAbsent(
                eq("lingshu:stream-owner:trace-1"),
                anyString(),
                eq(Duration.ofMinutes(5))
        );
    }

    @Test
    void refusesDistributedRoutingWithoutInternalAuthentication() {
        LingShuProperties properties = distributedProperties();
        properties.getSecurity().setInternalAdminKeyEnabled(false);

        assertThrows(IllegalArgumentException.class, () -> new RedisStreamOwnerDirectory(
                mock(StringRedisTemplate.class),
                new ObjectMapper(),
                properties
        ));
    }

    @Test
    void reportsRedisFailureInsteadOfPretendingCancellationCanBeRouted() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get("lingshu:stream-owner:trace-failure"))
                .thenThrow(new IllegalStateException("redis unavailable"));
        RedisStreamOwnerDirectory directory = new RedisStreamOwnerDirectory(
                redisTemplate,
                new ObjectMapper(),
                distributedProperties()
        );

        assertThrows(StreamRoutingUnavailableException.class,
                () -> directory.find("trace-failure"));
    }

    private LingShuProperties distributedProperties() {
        LingShuProperties properties = new LingShuProperties();
        properties.getStreaming().setRoutingStore("redis");
        properties.getStreaming().setInstanceId("core-a");
        properties.getStreaming().setInstanceBaseUrl("http://127.0.0.1:8081");
        properties.getSecurity().setInternalAdminKeyEnabled(true);
        properties.getSecurity().setInternalAdminKey("cluster-secret");
        return properties;
    }
}
