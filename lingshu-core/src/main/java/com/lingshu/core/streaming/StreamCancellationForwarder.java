package com.lingshu.core.streaming;

public interface StreamCancellationForwarder {

    StreamCancellationResult forward(
            StreamOwnerDirectory.StreamOwner owner,
            String traceId,
            String tenantId
    );
}
