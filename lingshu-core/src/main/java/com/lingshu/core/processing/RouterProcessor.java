package com.lingshu.core.processing;

import com.lingshu.core.provider.ModelProviderRouter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(200)
public class RouterProcessor implements ChatProcessor {

    private final ModelProviderRouter router;

    public RouterProcessor(ModelProviderRouter router) {
        this.router = router;
    }

    @Override
    public String name() {
        return "router";
    }

    @Override
    public boolean shouldProcess(ChatProcessingContext context) {
        return !context.completed();
    }

    @Override
    public void process(ChatProcessingContext context) {
        context.provider(router.route(context.request().model()));
    }
}
