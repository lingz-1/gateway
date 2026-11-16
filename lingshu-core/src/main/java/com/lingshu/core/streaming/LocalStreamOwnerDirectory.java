package com.lingshu.core.streaming;

import com.lingshu.core.config.LingShuProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "lingshu.streaming",
        name = "routing-store",
        havingValue = "memory",
        matchIfMissing = true
)
public class LocalStreamOwnerDirectory implements StreamOwnerDirectory {

    private final String instanceId;
    private final String baseUrl;

    public LocalStreamOwnerDirectory(LingShuProperties properties) {
        this.instanceId = properties.getStreaming().getInstanceId();
        this.baseUrl = properties.getStreaming().getInstanceBaseUrl();
    }

    @Override
    public boolean register(String traceId, String tenantId) {
        return true;
    }

    @Override
    public Optional<StreamOwner> find(String traceId) {
        return Optional.empty();
    }

    @Override
    public StreamOwner localOwner(String tenantId) {
        return new StreamOwner(instanceId, baseUrl, tenantId);
    }
}
