package com.lingshu.core.streaming;

import com.lingshu.core.observability.StreamTerminationReason;

public record StreamCancellationResult(
        boolean found,
        boolean accepted,
        StreamTerminationReason state
) {

    public static StreamCancellationResult notFound() {
        return new StreamCancellationResult(false, false, null);
    }
}
