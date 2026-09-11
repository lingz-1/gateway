package com.lingshu.core.provider;

import java.io.IOException;

@FunctionalInterface
public interface ProviderStreamConsumer {

    void onDelta(String content) throws IOException;

    default void onToolCallDelta(
            int index,
            String id,
            String type,
            String name,
            String arguments
    ) throws IOException {
        // Consumers may ignore tool call deltas.
    }

    default void onOpen(Runnable cancelUpstream) {
        // Consumers may register an upstream cancellation callback.
    }

    default boolean isCancelled() {
        return false;
    }
}
