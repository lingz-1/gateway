package com.lingshu.core.web;

import com.lingshu.common.contracts.TraceHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TraceIdFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void exposesTraceAndTenantInMdcOnlyDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceHeaders.TRACE_ID, "trace-mdc");
        request.addHeader(TraceHeaders.TENANT_ID, "tenant-mdc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Map<String, String>> requestContext = new AtomicReference<>();

        new TraceIdFilter().doFilter(request, response,
                (servletRequest, servletResponse) -> requestContext.set(MDC.getCopyOfContextMap()));

        assertEquals("trace-mdc", requestContext.get().get("traceId"));
        assertEquals("tenant-mdc", requestContext.get().get("tenantId"));
        assertEquals("trace-mdc", response.getHeader(TraceHeaders.TRACE_ID));
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("tenantId"));
    }
}
