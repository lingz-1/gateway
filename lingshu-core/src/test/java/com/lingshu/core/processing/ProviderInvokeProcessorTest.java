package com.lingshu.core.processing;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.provider.ModelProvider;
import com.lingshu.core.provider.ModelProviderRouter;
import com.lingshu.core.provider.ProviderHealth;
import com.lingshu.core.provider.ProviderRoutingException;
import com.lingshu.core.provider.ProviderUsageException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderInvokeProcessorTest {

    @Test
    void fallsBackAndPreservesUsageFromFailedAttempts() {
        ModelProvider primary = provider("a-primary", request -> {
            throw new ProviderUsageException("primary failed", 2, 1);
        });
        ModelProvider secondary = provider("b-secondary", request ->
                new ProviderResponse("b-secondary", "shared-model", "answer", 3, 4, "length"));
        ModelProviderRouter router = new ModelProviderRouter(List.of(primary, secondary));
        ChatProcessingContext context = context();
        new RouterProcessor(router).process(context);

        new ProviderInvokeProcessor(router).process(context);

        assertEquals("b-secondary", context.provider().id());
        assertEquals(List.of("a-primary", "b-secondary"), context.providerAttempts());
        assertEquals(5, context.providerResponse().inputTokens());
        assertEquals(5, context.providerResponse().outputTokens());
        assertEquals("length", context.providerResponse().finishReason());
    }

    @Test
    void reportsAggregateUsageWhenAllCandidatesFail() {
        ModelProvider primary = provider("a-primary", request -> {
            throw new ProviderUsageException("primary failed", 2, 1);
        });
        ModelProvider secondary = provider("b-secondary", request -> {
            throw new ProviderRoutingException("secondary failed", null, 3, 2);
        });
        ModelProviderRouter router = new ModelProviderRouter(List.of(primary, secondary));
        ChatProcessingContext context = context();
        new RouterProcessor(router).process(context);

        ProviderRoutingException exception = assertThrows(
                ProviderRoutingException.class,
                () -> new ProviderInvokeProcessor(router).process(context)
        );

        assertEquals(5, exception.inputTokens());
        assertEquals(3, exception.outputTokens());
        assertEquals(List.of("a-primary", "b-secondary"), context.providerAttempts());
    }

    private ChatProcessingContext context() {
        return new ChatProcessingContext(
                new ChatCompletionRequest(
                        "shared-model",
                        List.of(new ChatMessage("user", "hello")),
                        false,
                        null,
                        null,
                        null
                ),
                "trace-1",
                "tenant-1"
        );
    }

    private ModelProvider provider(String id, Invocation invocation) {
        return new ModelProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Set<String> supportedModels() {
                return Set.of("shared-model");
            }

            @Override
            public ProviderResponse invoke(ProviderRequest request) {
                return invocation.invoke(request);
            }

            @Override
            public ProviderHealth health() {
                return new ProviderHealth(id, ProviderHealth.Status.UP, Instant.now());
            }
        };
    }

    @FunctionalInterface
    private interface Invocation {
        ProviderResponse invoke(ProviderRequest request);
    }
}
