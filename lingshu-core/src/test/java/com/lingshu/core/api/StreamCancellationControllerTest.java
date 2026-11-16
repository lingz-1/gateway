package com.lingshu.core.api;

import com.lingshu.core.streaming.StreamLifecycleRegistry;
import com.lingshu.core.streaming.StreamCancellationCoordinator;
import com.lingshu.core.streaming.StreamCancellationResult;
import com.lingshu.core.streaming.StreamOwnerDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

class StreamCancellationControllerTest {

    @Test
    void returnsAcceptedThenIdempotentSuccess() {
        StreamLifecycleRegistry registry = new StreamLifecycleRegistry();
        registry.open("trace-cancel", "tenant-a");
        StreamCancellationController controller = new StreamCancellationController(coordinator(registry));

        ResponseEntity<StreamCancellationController.StreamCancellationResponse> first =
                controller.cancel("trace-cancel", "tenant-a");
        ResponseEntity<StreamCancellationController.StreamCancellationResponse> duplicate =
                controller.cancel("trace-cancel", "tenant-a");

        assertEquals(HttpStatus.ACCEPTED, first.getStatusCode());
        assertTrue(first.getBody().cancellationAccepted());
        assertEquals(HttpStatus.OK, duplicate.getStatusCode());
        assertFalse(duplicate.getBody().cancellationAccepted());
    }

    @Test
    void hidesStreamsOwnedByAnotherTenant() {
        StreamLifecycleRegistry registry = new StreamLifecycleRegistry();
        registry.open("trace-private", "tenant-a");
        StreamCancellationController controller = new StreamCancellationController(coordinator(registry));

        ResponseEntity<StreamCancellationController.StreamCancellationResponse> response =
                controller.cancel("trace-private", "tenant-b");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    private StreamCancellationCoordinator coordinator(StreamLifecycleRegistry registry) {
        StreamOwnerDirectory directory = new StreamOwnerDirectory() {
            @Override
            public boolean register(String traceId, String tenantId) {
                return true;
            }

            @Override
            public Optional<StreamOwner> find(String traceId) {
                return Optional.empty();
            }

            @Override
            public StreamOwner localOwner(String tenantId) {
                return new StreamOwner("test", "http://127.0.0.1:8081", tenantId);
            }
        };
        return new StreamCancellationCoordinator(
                registry,
                directory,
                (owner, traceId, tenantId) -> StreamCancellationResult.notFound()
        );
    }
}
