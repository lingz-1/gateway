package com.lingshu.common.contracts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ApiErrorTest {

    @Test
    void createsStableErrorContract() {
        ApiError error = ApiError.of(ErrorCode.INVALID_REQUEST, "invalid payload", "trace-1");

        assertEquals("INVALID_REQUEST", error.code());
        assertEquals("invalid payload", error.message());
        assertEquals("trace-1", error.traceId());
        assertNotNull(error.timestamp());
    }
}
