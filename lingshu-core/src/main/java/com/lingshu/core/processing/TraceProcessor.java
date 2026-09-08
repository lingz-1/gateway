package com.lingshu.core.processing;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Order(100)
public class TraceProcessor implements ChatProcessor {

    @Override
    public String name() {
        return "trace";
    }

    @Override
    public void process(ChatProcessingContext context) {
        if (!StringUtils.hasText(context.traceId())) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
    }
}
