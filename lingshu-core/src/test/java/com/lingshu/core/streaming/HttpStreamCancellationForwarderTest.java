package com.lingshu.core.streaming;

import com.lingshu.core.config.LingShuProperties;
import com.lingshu.core.observability.StreamTerminationReason;
import com.lingshu.core.web.InternalAdminKeyFilter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpStreamCancellationForwarderTest {

    @Test
    void forwardsToOwnerWithTenantAndInternalAuthentication() throws Exception {
        AtomicReference<String> tenantHeader = new AtomicReference<>();
        AtomicReference<String> adminHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/stream-cancellations/trace-http", exchange -> {
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant-Id"));
            adminHeader.set(exchange.getRequestHeaders().getFirst(InternalAdminKeyFilter.HEADER));
            byte[] body = ("{\"traceId\":\"trace-http\","
                    + "\"state\":\"CLIENT_CANCEL_REQUESTED\","
                    + "\"cancellationAccepted\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            LingShuProperties properties = new LingShuProperties();
            properties.getStreaming().setForwardTimeout(Duration.ofSeconds(2));
            properties.getSecurity().setInternalAdminKey("cluster-secret");
            HttpStreamCancellationForwarder forwarder = new HttpStreamCancellationForwarder(
                    new ObjectMapper(),
                    properties
            );
            StreamOwnerDirectory.StreamOwner owner = new StreamOwnerDirectory.StreamOwner(
                    "core-a",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "tenant-a"
            );

            StreamCancellationResult result = forwarder.forward(owner, "trace-http", "tenant-a");

            assertTrue(result.found());
            assertTrue(result.accepted());
            assertEquals(StreamTerminationReason.CLIENT_CANCEL_REQUESTED, result.state());
            assertEquals("tenant-a", tenantHeader.get());
            assertEquals("cluster-secret", adminHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsOwnerFailureInsteadOfPretendingCancellationSucceeded() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/stream-cancellations/trace-failure", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            LingShuProperties properties = new LingShuProperties();
            properties.getStreaming().setForwardTimeout(Duration.ofSeconds(2));
            HttpStreamCancellationForwarder forwarder = new HttpStreamCancellationForwarder(
                    new ObjectMapper(),
                    properties
            );
            StreamOwnerDirectory.StreamOwner owner = new StreamOwnerDirectory.StreamOwner(
                    "core-a",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "tenant-a"
            );

            assertThrows(StreamRoutingUnavailableException.class,
                    () -> forwarder.forward(owner, "trace-failure", "tenant-a"));
        } finally {
            server.stop(0);
        }
    }
}
