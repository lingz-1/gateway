package com.lingshu.core.streaming;

import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.core.config.LingShuProperties;
import com.lingshu.core.observability.StreamTerminationReason;
import com.lingshu.core.web.InternalAdminKeyFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class HttpStreamCancellationForwarder implements StreamCancellationForwarder {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final String internalAdminKey;

    public HttpStreamCancellationForwarder(ObjectMapper objectMapper, LingShuProperties properties) {
        this.objectMapper = objectMapper;
        this.timeout = properties.getStreaming().getForwardTimeout();
        this.internalAdminKey = properties.getSecurity().getInternalAdminKey();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("LINGSHU_STREAM_FORWARD_TIMEOUT must be positive");
        }
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public StreamCancellationResult forward(
            StreamOwnerDirectory.StreamOwner owner,
            String traceId,
            String tenantId
    ) {
        URI target = UriComponentsBuilder.fromUriString(owner.baseUrl())
                .pathSegment("internal", "v1", "stream-cancellations", traceId)
                .build()
                .encode()
                .toUri();
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
                .timeout(timeout)
                .header(TraceHeaders.TENANT_ID, tenantId)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (internalAdminKey != null && !internalAdminKey.isBlank()) {
            request.header(InternalAdminKeyFilter.HEADER, internalAdminKey);
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 404) {
                return StreamCancellationResult.notFound();
            }
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                throw new StreamRoutingUnavailableException(
                        "Streaming owner returned HTTP " + response.statusCode()
                );
            }
            JsonNode body = objectMapper.readTree(response.body());
            return new StreamCancellationResult(
                    true,
                    body.path("cancellationAccepted").asBoolean(false),
                    StreamTerminationReason.valueOf(body.path("state").asText())
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StreamRoutingUnavailableException("Streaming cancellation forwarding was interrupted", exception);
        } catch (Exception exception) {
            throw new StreamRoutingUnavailableException("Unable to reach streaming owner", exception);
        }
    }
}
