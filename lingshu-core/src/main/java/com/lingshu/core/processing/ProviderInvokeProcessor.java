package com.lingshu.core.processing;

import com.lingshu.common.dto.ProviderRequest;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.provider.ModelProvider;
import com.lingshu.core.provider.ModelProviderRouter;
import com.lingshu.core.provider.ProviderRoutingException;
import com.lingshu.core.provider.ProviderUsageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(300)
public class ProviderInvokeProcessor implements ChatProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderInvokeProcessor.class);

    private final ModelProviderRouter router;

    public ProviderInvokeProcessor(ModelProviderRouter router) {
        this.router = router;
    }

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
        List<ModelProvider> candidates = context.providerCandidates().isEmpty()
                ? List.of(context.provider())
                : context.providerCandidates();
        int failedInputTokens = 0;
        int failedOutputTokens = 0;
        RuntimeException lastFailure = null;
        for (ModelProvider candidate : candidates) {
            context.provider(candidate);
            context.addProviderAttempt(candidate.id());
            long startedAt = router.beginAttempt(candidate);
            try {
                ProviderResponse response = candidate.invoke(providerRequest);
                router.recordSuccess(candidate, startedAt);
                context.providerResponse(withFailedUsage(response, failedInputTokens, failedOutputTokens));
                return;
            } catch (ProviderUsageException exception) {
                router.recordFailure(candidate, startedAt);
                failedInputTokens += exception.inputTokens();
                failedOutputTokens += exception.outputTokens();
                lastFailure = exception;
                logFallback(context, candidate, exception);
            } catch (ProviderRoutingException exception) {
                router.recordFailure(candidate, startedAt);
                failedInputTokens += exception.inputTokens();
                failedOutputTokens += exception.outputTokens();
                lastFailure = exception;
                logFallback(context, candidate, exception);
            } catch (IllegalStateException exception) {
                router.recordFailure(candidate, startedAt);
                lastFailure = exception;
                logFallback(context, candidate, exception);
            }
        }
        throw new ProviderRoutingException(
                "All providers failed for model: " + context.request().model(),
                lastFailure,
                failedInputTokens,
                failedOutputTokens
        );
    }

    private void logFallback(
            ChatProcessingContext context,
            ModelProvider candidate,
            RuntimeException exception
    ) {
        LOGGER.warn(
                "Provider attempt failed traceId={} tenantId={} model={} provider={} failure={}",
                context.traceId(),
                context.tenantId(),
                context.request().model(),
                candidate.id(),
                exception.getClass().getSimpleName()
        );
    }

    private ProviderResponse withFailedUsage(
            ProviderResponse response,
            int failedInputTokens,
            int failedOutputTokens
    ) {
        if (failedInputTokens == 0 && failedOutputTokens == 0) {
            return response;
        }
        return new ProviderResponse(
                response.provider(),
                response.model(),
                response.content(),
                response.inputTokens() + failedInputTokens,
                response.outputTokens() + failedOutputTokens,
                response.finishReason()
        );
    }
}
