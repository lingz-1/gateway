package com.lingshu.core.streaming;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(prefix = "lingshu.streaming", name = "routing-store", havingValue = "redis")
public class RedisStreamOwnerDirectory implements StreamOwnerDirectory {

    private static final Duration MINIMUM_OWNER_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ownerTtl;
    private final String instanceId;
    private final String baseUrl;

    public RedisStreamOwnerDirectory(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            LingShuProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        LingShuProperties.Streaming streaming = properties.getStreaming();
        this.keyPrefix = requireText(streaming.getRedisKeyPrefix(), "streaming redis key prefix");
        this.ownerTtl = Objects.requireNonNull(streaming.getOwnerTtl(), "streaming owner TTL must not be null");
        this.instanceId = requireText(streaming.getInstanceId(), "streaming instance id");
        this.baseUrl = validateBaseUrl(streaming.getInstanceBaseUrl());
        if ("local".equalsIgnoreCase(instanceId)) {
            throw new IllegalArgumentException(
                    "Distributed stream routing requires a unique LINGSHU_STREAM_INSTANCE_ID"
            );
        }
        if (ownerTtl.compareTo(MINIMUM_OWNER_TTL) <= 0) {
            throw new IllegalArgumentException("LINGSHU_STREAM_OWNER_TTL must exceed the two-minute SSE timeout");
        }
        if (!properties.getSecurity().isInternalAdminKeyEnabled()
                || properties.getSecurity().getInternalAdminKey() == null
                || properties.getSecurity().getInternalAdminKey().isBlank()) {
            throw new IllegalArgumentException(
                    "Distributed stream routing requires LINGSHU_INTERNAL_ADMIN_KEY_ENABLED=true and a non-empty key"
            );
        }
    }

    @Override
    public boolean register(String traceId, String tenantId) {
        try {
            String value = objectMapper.writeValueAsString(localOwner(tenantId));
            Boolean registered = redisTemplate.opsForValue().setIfAbsent(key(traceId), value, ownerTtl);
            return Boolean.TRUE.equals(registered);
        } catch (Exception exception) {
            throw new StreamRoutingUnavailableException("Unable to register streaming owner", exception);
        }
    }

    @Override
    public Optional<StreamOwner> find(String traceId) {
        try {
            String value = redisTemplate.opsForValue().get(key(traceId));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, StreamOwner.class));
        } catch (Exception exception) {
            throw new StreamRoutingUnavailableException("Unable to resolve streaming owner", exception);
        }
    }

    @Override
    public StreamOwner localOwner(String tenantId) {
        return new StreamOwner(instanceId, baseUrl, tenantId);
    }

    private String key(String traceId) {
        return keyPrefix + traceId;
    }

    private static String validateBaseUrl(String value) {
        String baseUrl = requireText(value, "streaming instance base URL");
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("LINGSHU_STREAM_INSTANCE_BASE_URL is invalid", exception);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("LINGSHU_STREAM_INSTANCE_BASE_URL must be an HTTP(S) origin");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
