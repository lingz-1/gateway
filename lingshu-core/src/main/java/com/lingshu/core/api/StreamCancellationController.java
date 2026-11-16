package com.lingshu.core.api;

import com.lingshu.common.contracts.TraceHeaders;
import com.lingshu.core.streaming.StreamCancellationCoordinator;
import com.lingshu.core.streaming.StreamCancellationResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StreamCancellationController {

    private final StreamCancellationCoordinator coordinator;

    public StreamCancellationController(StreamCancellationCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/v1/stream-cancellations/{traceId}")
    public ResponseEntity<StreamCancellationResponse> cancel(
            @PathVariable String traceId,
            @RequestHeader(name = TraceHeaders.TENANT_ID, defaultValue = "default") String tenantId
    ) {
        return response(traceId, coordinator.cancel(traceId, tenantId));
    }

    @PostMapping("/internal/v1/stream-cancellations/{traceId}")
    public ResponseEntity<StreamCancellationResponse> cancelLocal(
            @PathVariable String traceId,
            @RequestHeader(name = TraceHeaders.TENANT_ID, defaultValue = "default") String tenantId
    ) {
        return response(traceId, coordinator.cancelLocal(traceId, tenantId));
    }

    private ResponseEntity<StreamCancellationResponse> response(
            String traceId,
            StreamCancellationResult result
    ) {
        if (!result.found()) {
            return ResponseEntity.notFound().build();
        }
        StreamCancellationResponse response = new StreamCancellationResponse(
                traceId,
                result.state().name(),
                result.accepted()
        );
        return result.accepted()
                ? ResponseEntity.accepted().body(response)
                : ResponseEntity.ok(response);
    }

    public record StreamCancellationResponse(
            String traceId,
            String state,
            boolean cancellationAccepted
    ) {
    }
}
