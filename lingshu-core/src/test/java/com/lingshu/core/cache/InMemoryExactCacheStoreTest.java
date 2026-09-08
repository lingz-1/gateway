package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryExactCacheStoreTest {

    @Test
    void expiresEntriesAfterTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryExactCacheStore store = new InMemoryExactCacheStore(Duration.ofSeconds(5), 10, clock);
        ProviderResponse response = new ProviderResponse("stub", "stub-echo-v1", "answer", 1, 1);

        store.put("key", response);
        assertEquals(response, store.get("key").orElseThrow());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(store.get("key").isEmpty());
    }

    @Test
    void remainsBounded() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-28T00:00:00Z"));
        InMemoryExactCacheStore store = new InMemoryExactCacheStore(Duration.ofMinutes(1), 1, clock);
        ProviderResponse response = new ProviderResponse("stub", "stub-echo-v1", "answer", 1, 1);

        store.put("first", response);
        store.put("second", response);

        assertTrue(store.get("first").isEmpty() || store.get("second").isEmpty());
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
