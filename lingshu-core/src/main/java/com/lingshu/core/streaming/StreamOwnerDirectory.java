package com.lingshu.core.streaming;

import java.util.Optional;

public interface StreamOwnerDirectory {

    boolean register(String traceId, String tenantId);

    Optional<StreamOwner> find(String traceId);

    StreamOwner localOwner(String tenantId);

    record StreamOwner(
            String instanceId,
            String baseUrl,
            String tenantId
    ) {
    }
}
