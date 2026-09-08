package com.lingshu.common.contracts;

public final class TraceHeaders {

    public static final String TRACE_ID = "X-Trace-Id";
    public static final String TENANT_ID = "X-Tenant-Id";
    public static final String PROVIDER = "X-LingShu-Provider";
    public static final String CACHE_STATUS = "X-LingShu-Cache";
    public static final String PROCESSING_TIME = "X-Processing-Time-Ms";

    private TraceHeaders() {
    }
}
