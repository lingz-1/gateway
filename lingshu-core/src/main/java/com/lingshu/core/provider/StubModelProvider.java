package com.lingshu.core.provider;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.Locale;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.provider.stub",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class StubModelProvider implements ModelProvider {

    private static final String PROVIDER_ID = "stub";

    private final String model;
    private final Duration invokeDelay;
    private final Duration streamDelay;
    private final int streamChunks;
    private final StreamFailureMode streamFailureMode;
    private final int streamFailureAfterChunks;

    public StubModelProvider(LingShuProperties properties) {
        this.model = properties.getProvider().getStub().getModel();
        this.invokeDelay = properties.getProvider().getStub().getInvokeDelay();
        this.streamDelay = properties.getProvider().getStub().getStreamDelay();
        this.streamChunks = properties.getProvider().getStub().getStreamChunks();
        this.streamFailureMode = StreamFailureMode.parse(
                properties.getProvider().getStub().getStreamFailureMode()
        );
        this.streamFailureAfterChunks = properties.getProvider().getStub().getStreamFailureAfterChunks();
        if (invokeDelay == null || invokeDelay.isNegative()
                || streamDelay == null || streamDelay.isNegative()) {
            throw new IllegalArgumentException("Stub delays must not be negative");
        }
        if (streamChunks <= 0) {
            throw new IllegalArgumentException("Stub stream chunks must be positive");
        }
        if (streamFailureAfterChunks <= 0) {
            throw new IllegalArgumentException("Stub stream failure chunk count must be positive");
        }
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> supportedModels() {
        return Set.of(model);
    }

    @Override
    public ProviderResponse invoke(ProviderRequest request) {
        if (!invokeDelay.isZero()) {
            try {
                Thread.sleep(invokeDelay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Stub invocation was interrupted", exception);
            }
        }
        String content = "stub:" + request.prompt();
        return new ProviderResponse(
                PROVIDER_ID,
                model,
                content,
                estimateTokens(request.prompt()),
                estimateTokens(content),
                "stop"
        );
    }

    @Override
    public ProviderResponse stream(ProviderRequest request, ProviderStreamConsumer consumer) {
        ProviderResponse response = invoke(request);
        if (streamFailureMode == StreamFailureMode.BEFORE_FIRST) {
            throw new IllegalStateException("Injected Stub failure before first stream chunk");
        }
        Thread providerThread = Thread.currentThread();
        consumer.onOpen(providerThread::interrupt);
        String content = response.content();
        int chunkSize = Math.max(1, (content.length() + streamChunks - 1) / streamChunks);
        try {
            int emittedChunks = 0;
            for (int offset = 0; offset < content.length(); offset += chunkSize) {
                if (consumer.isCancelled()) {
                    throw new ProviderStreamCancelledException("Stub provider stream was cancelled");
                }
                if (!streamDelay.isZero()) {
                    Thread.sleep(streamDelay);
                }
                int end = Math.min(content.length(), offset + chunkSize);
                consumer.onDelta(content.substring(offset, end));
                emittedChunks++;
                failAfterChunkIfConfigured(emittedChunks);
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderStreamCancelledException("Stub provider stream was cancelled", exception);
        } catch (java.io.IOException exception) {
            throw new ProviderStreamCancelledException("Provider stream consumer is unavailable", exception);
        }
    }

    @Override
    public ProviderHealth health() {
        return new ProviderHealth(PROVIDER_ID, ProviderHealth.Status.UP, Instant.now());
    }

    private int estimateTokens(String text) {
        return Math.max(1, (text.codePointCount(0, text.length()) + 3) / 4);
    }

    private void failAfterChunkIfConfigured(int emittedChunks) {
        if (emittedChunks != streamFailureAfterChunks) {
            return;
        }
        if (streamFailureMode == StreamFailureMode.TRUNCATE_AFTER_CHUNKS) {
            throw new ProviderStreamTruncatedException(
                    "Injected Stub stream truncation after " + emittedChunks + " chunks"
            );
        }
        if (streamFailureMode == StreamFailureMode.ERROR_AFTER_CHUNKS) {
            throw new IllegalStateException(
                    "Injected Stub stream error after " + emittedChunks + " chunks"
            );
        }
    }

    private enum StreamFailureMode {
        NONE,
        BEFORE_FIRST,
        TRUNCATE_AFTER_CHUNKS,
        ERROR_AFTER_CHUNKS;

        private static StreamFailureMode parse(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            try {
                return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported Stub stream failure mode: " + value, exception);
            }
        }
    }
}
