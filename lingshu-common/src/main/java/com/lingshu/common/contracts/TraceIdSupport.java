package com.lingshu.common.contracts;

import java.util.UUID;
import java.util.regex.Pattern;

public final class TraceIdSupport {

    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private TraceIdSupport() {
    }

    public static String resolve(String candidate) {
        if (candidate != null && VALID_TRACE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
