package com.lingshu.core.web;

import com.lingshu.core.config.LingShuProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InternalAdminKeyFilterTest {

    @Test
    void rejectsMissingKeyAndAcceptsConfiguredKey() throws Exception {
        LingShuProperties properties = new LingShuProperties();
        properties.getSecurity().setInternalAdminKey("test-admin-key");
        InternalAdminKeyFilter filter = new InternalAdminKeyFilter(properties);

        MockHttpServletRequest rejected = new MockHttpServletRequest("GET", "/internal/billing/tenants/a");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        filter.doFilter(rejected, rejectedResponse, new MockFilterChain());
        assertEquals(401, rejectedResponse.getStatus());

        MockHttpServletRequest accepted = new MockHttpServletRequest("GET", "/internal/billing/tenants/a");
        accepted.addHeader(InternalAdminKeyFilter.HEADER, "test-admin-key");
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        filter.doFilter(accepted, acceptedResponse, new MockFilterChain());
        assertEquals(200, acceptedResponse.getStatus());
    }
}
