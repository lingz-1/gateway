package com.lingshu.core.processing;

import com.lingshu.common.dto.ProviderRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(300)
public class ProviderInvokeProcessor implements ChatProcessor {

    @Override
    public String name() {
        return "provider-invoke";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return !context.completed();
    }

    @Override
    public void process(ChatProcessingContext context) {
        ProviderRequest providerRequest = new ProviderRequest(
                context.traceId(),
                context.tenantId(),
                context.request().model(),
                context.request().messages(),
                context.request().temperature(),
                context.request().max_tokens(),
                context.request().top_p()
        );
        context.providerResponse(context.provider().invoke(providerRequest));
    }
}
