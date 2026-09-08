package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.cache.exact",
        name = "store",
        havingValue = "memory",
        matchIfMissing = true
)
public class InMemoryExactCacheStore implements ExactCacheStore {

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Clock clock;

    @Autowired
    public InMemoryExactCacheStore(LingShuProperties properties) {
        this(
                properties.getCache().getExact().getTtl(),
                properties.getCache().getExact().getMaxEntries(),
                Clock.systemUTC()
        );
    }

    InMemoryExactCacheStore(Duration ttl, int maxEntries, Clock clock) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Cache TTL must be positive");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Cache maxEntries must be positive");
        }
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    @Override
    public Optional<ProviderResponse> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    @Override
    public void put(String key, ProviderResponse response) {
        evictIfFull();
        entries.put(key, new Entry(response, clock.instant().plus(ttl)));
    }

    private void evictIfFull() {
        if (entries.size() < maxEntries) {
            return;
        }
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        if (entries.size() >= maxEntries) {
            entries.keySet().stream().findAny().ifPresent(entries::remove);
        }
    }

    private record Entry(ProviderResponse response, Instant expiresAt) {
    }
}
