package com.lingshu.core.observability;

public enum StreamTerminationReason {
    COMPLETED,
    CLIENT_CANCEL_REQUESTED,
    CLIENT_DISCONNECTED,
    TIMEOUT,
    UPSTREAM_TRUNCATED,
    UPSTREAM_ERROR,
    DOWNSTREAM_WRITE_FAILED,
    INTERNAL_ERROR
}
