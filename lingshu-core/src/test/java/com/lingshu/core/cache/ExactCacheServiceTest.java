package com.lingshu.core.cache;

import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

class ExactCacheServiceTest {

    @Test
    void failsOpenWhenStoreIsUnavailable() {
        ExactCacheStore store = mock(ExactCacheStore.class);
        when(store.get("key")).thenThrow(new IllegalStateException("unavailable"));
        doThrow(new IllegalStateException("unavailable"))
                .when(store)
                .put("key", new ProviderResponse("stub", "stub-echo-v1", "answer", 1, 1));
        ExactCacheService service = new ExactCacheService(
                new LingShuProperties(),
                mock(ExactCacheKeyFactory.class),
                store
        );

        assertTrue(service.get("key").isEmpty());
        assertDoesNotThrow(() -> service.put(
                "key",
                new ProviderResponse("stub", "stub-echo-v1", "answer", 1, 1)
        ));
    }
}
