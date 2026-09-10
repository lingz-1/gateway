package com.lingshu.core.api;

import com.lingshu.common.dto.ChatCompletionRequest;
import com.lingshu.common.dto.ChatCompletionResponse;
import com.lingshu.common.dto.ChatMessage;
import com.lingshu.common.dto.ProviderResponse;
import com.lingshu.core.processing.ChatProcessingContext;
import com.lingshu.core.processing.ChatProcessingResult;
import com.lingshu.core.processing.ChatProcessorEngine;
import com.lingshu.core.billing.VirtualBillingCharge;
import com.lingshu.core.billing.VirtualBillingService;
import com.lingshu.core.provider.ProviderUsageException;
import com.lingshu.core.provider.ProviderRoutingException;
import com.lingshu.core.observability.LingShuMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ChatCompletionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatCompletionService.class);

    private final ChatProcessorEngine engine;
    private final VirtualBillingService virtualBillingService;
    private final LingShuMetrics metrics;

    public ChatCompletionService(
            ChatProcessorEngine engine,
            VirtualBillingService virtualBillingService,
            LingShuMetrics metrics
    ) {
        this.engine = engine;
        this.virtualBillingService = virtualBillingService;
        this.metrics = metrics;
    }

    public ChatCompletionResponse complete(
            ChatCompletionRequest request,
            String traceId,
            String tenantId
    ) {
        ChatProcessingResult result;
        long startedAt = System.nanoTime();
        try {
            result = engine.execute(request, traceId, tenantId);
        } catch (RuntimeException exception) {
            int inputTokens = 0;
            int outputTokens = 0;
            if (exception instanceof ProviderUsageException usageException) {
                inputTokens = usageException.inputTokens();
                outputTokens = usageException.outputTokens();
            } else if (exception instanceof ProviderRoutingException routingException) {
                inputTokens = routingException.inputTokens();
                outputTokens = routingException.outputTokens();
            }
            VirtualBillingCharge billing = virtualBillingService.recordFailure(
                    tenantId,
                    traceId,
                    inputTokens,
                    outputTokens
            );
            LOGGER.info(
                    "Virtual billing recorded failed request traceId={} tenantId={} inputTokens={} outputTokens={} costCny={} remainingBalanceCny={}",
                    traceId,
                    tenantId,
                    billing.inputTokens(),
                    billing.outputTokens(),
                    billing.costCny(),
                    billing.remainingBalanceCny()
            );
            metrics.failure(tenantId, exception.getClass().getSimpleName(), elapsedMs(startedAt), billing);
            throw exception;
        }
        ChatProcessingContext context = result.context();
        ProviderResponse providerResponse = context.providerResponse();
        VirtualBillingCharge billing = context.virtualBillingCharge();
        if (billing == null) {
            billing = virtualBillingService.charge(
                    tenantId,
                    traceId,
                    providerResponse,
                    context.cacheStatus()
            );
        }
        LOGGER.info(
                "Virtual billing recorded traceId={} tenantId={} inputTokens={} outputTokens={} costCny={} remainingBalanceCny={} cacheStatus={}",
                traceId,
                tenantId,
                billing.inputTokens(),
                billing.outputTokens(),
                billing.costCny(),
                billing.remainingBalanceCny(),
                context.cacheStatus()
        );
        metrics.success(tenantId, providerResponse.provider(), providerResponse.model(), context.cacheStatus(),
                result.totalDurationMs(), billing);

        ChatCompletionResponse.Usage usage = new ChatCompletionResponse.Usage(
                providerResponse.inputTokens(),
                providerResponse.outputTokens(),
                providerResponse.inputTokens() + providerResponse.outputTokens()
        );
        ChatCompletionResponse.Choice choice = new ChatCompletionResponse.Choice(
                0,
                new ChatMessage("assistant", providerResponse.content()),
                providerResponse.finishReason()
        );
        ChatCompletionResponse.Metadata metadata = new ChatCompletionResponse.Metadata(
                traceId,
                tenantId,
                providerResponse.provider(),
                context.cacheStatus(),
                result.totalDurationMs(),
                context.steps(),
                billing.costCny(),
                billing.remainingBalanceCny(),
                context.providerAttempts()
        );

        return new ChatCompletionResponse(
                "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""),
                "chat.completion",
                Instant.now().getEpochSecond(),
                providerResponse.model(),
                List.of(choice),
                usage,
                metadata
        );
    }

    private long elapsedMs(long startedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }
}
