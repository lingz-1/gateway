package com.lingshu.core.cache;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticCacheServiceTest {

    @Test
    void scopesLookupAndWritesEntry() {
        LingShuProperties properties = enabledProperties();
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        SemanticCacheStore store = mock(SemanticCacheStore.class);
        ExactCacheKeyFactory keyFactory = new ExactCacheKeyFactory(properties);
        SemanticCacheService service = new SemanticCacheService(
                properties,
                embeddingService,
                store,
                keyFactory
        );
        ChatCompletionRequest request = request("reset my password now");
        ProviderResponse response = new ProviderResponse("stub", "stub-echo-v1", "answer", 4, 1);
        double[] embedding = {1.0, 0.0};
        when(embeddingService.embed(anyString())).thenReturn(embedding);
        when(store.find(anyString(), anyString(), any(double[].class), anyDouble()))
                .thenReturn(Optional.of(new SemanticCacheMatch(response, 0.95)));

        assertEquals(embedding, service.embed(request));
        assertEquals(response, service.find("tenant-a", request, embedding).orElseThrow().response());
        service.put("tenant-a", request, embedding, response);

        ArgumentCaptor<SemanticCacheEntry> entryCaptor = ArgumentCaptor.forClass(SemanticCacheEntry.class);
        verify(store).put(entryCaptor.capture());
        SemanticCacheEntry entry = entryCaptor.getValue();
        assertEquals("tenant-a", entry.tenantId());
        assertEquals("stub-echo-v1", entry.model());
        assertEquals("v1", entry.promptVersion());
        assertFalse(entry.requestText().contains("\u0000"));
        assertTrue(entry.scopeKey().matches("[0-9a-f]{64}"));
        assertTrue(entry.requestHash().matches("[0-9a-f]{64}"));
    }

    @Test
    void failsOpenWhenStoreIsUnavailable() {
        LingShuProperties properties = enabledProperties();
        SemanticCacheStore store = mock(SemanticCacheStore.class);
        when(store.find(anyString(), anyString(), any(double[].class), anyDouble()))
                .thenThrow(new IllegalStateException("unavailable"));
        SemanticCacheService service = new SemanticCacheService(
                properties,
                text -> new double[]{1.0, 0.0},
                store,
                new ExactCacheKeyFactory(properties)
        );

        assertTrue(service.find("tenant-a", request("hello"), new double[]{1.0, 0.0}).isEmpty());
    }

    @Test
    void failsOpenWhenEmbeddingProviderIsUnavailable() {
        LingShuProperties properties = enabledProperties();
        SemanticCacheService service = new SemanticCacheService(
                properties,
                text -> {
                    throw new IllegalStateException("unavailable");
                },
                mock(SemanticCacheStore.class),
                new ExactCacheKeyFactory(properties)
        );

        assertTrue(service.tryEmbed(request("hello")).isEmpty());
    }

    @Test
    void isolatesCacheWhenEmbeddingDimensionChanges() {
        LingShuProperties firstProperties = enabledProperties();
        LingShuProperties secondProperties = enabledProperties();
        secondProperties.getCache().getSemantic().setEmbeddingDimension(128);
        SemanticCacheStore firstStore = mock(SemanticCacheStore.class);
        SemanticCacheStore secondStore = mock(SemanticCacheStore.class);
        SemanticCacheService firstService = new SemanticCacheService(
                firstProperties,
                text -> new double[64],
                firstStore,
                new ExactCacheKeyFactory(firstProperties)
        );
        SemanticCacheService secondService = new SemanticCacheService(
                secondProperties,
                text -> new double[128],
                secondStore,
                new ExactCacheKeyFactory(secondProperties)
        );

        firstService.find("tenant-a", request("hello"), new double[64]);
        secondService.find("tenant-a", request("hello"), new double[128]);

        ArgumentCaptor<String> firstScope = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> secondScope = ArgumentCaptor.forClass(String.class);
        verify(firstStore).find(anyString(), firstScope.capture(), any(double[].class), anyDouble());
        verify(secondStore).find(anyString(), secondScope.capture(), any(double[].class), anyDouble());
        assertNotEquals(firstScope.getValue(), secondScope.getValue());
    }

    private LingShuProperties enabledProperties() {
        LingShuProperties properties = new LingShuProperties();
        properties.getCache().getSemantic().setEnabled(true);
        return properties;
    }

    private ChatCompletionRequest request(String content) {
        return new ChatCompletionRequest(
                "stub-echo-v1",
                List.of(new ChatMessage("user", content)),
                false,
                0.2
        );
    }
}
