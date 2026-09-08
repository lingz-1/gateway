package com.lingshu.core.tenant;

import com.lingshu.core.config.LingShuProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NacosTenantPolicySourceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicReference<String> requestQuery = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/nacos/v3/client/cs/config", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void refreshesPoliciesAndRetainsLastSnapshotWhenNacosFails() throws Exception {
        responseBody.set(nacosResponse("md5-v1", policyDocument(true)));
        LingShuProperties properties = properties();
        NacosTenantPolicySource source = new NacosTenantPolicySource(
                properties,
                objectMapper,
                HttpClient.newHttpClient()
        );

        source.refresh();

        TenantPolicy first = source.find("tenant-nacos").orElseThrow();
        assertTrue(first.piiRedactionEnabled());
        assertTrue(requestQuery.get().contains("dataId=lingshu-tenant-policies.json"));
        assertTrue(requestQuery.get().contains("groupName=LINGSHU"));
        assertEquals("Bearer token-test", authorization.get());

        responseBody.set(nacosResponse("md5-v2", policyDocument(false)));
        source.refresh();

        TenantPolicy updated = source.find("tenant-nacos").orElseThrow();
        assertFalse(updated.piiRedactionEnabled());

        statusCode.set(503);
        source.refresh();

        assertFalse(source.find("tenant-nacos").orElseThrow().piiRedactionEnabled());
    }

    private LingShuProperties properties() {
        LingShuProperties properties = new LingShuProperties();
        LingShuProperties.Nacos nacos = properties.getTenantPolicy().getNacos();
        nacos.setServerUrl("http://127.0.0.1:" + server.getAddress().getPort());
        nacos.setNamespaceId("public");
        nacos.setGroupName("LINGSHU");
        nacos.setDataId("lingshu-tenant-policies.json");
        nacos.setAccessToken("token-test");
        return properties;
    }

    private String nacosResponse(String md5, String content) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "code", 0,
                "message", "success",
                "data", Map.of(
                        "success", true,
                        "content", content,
                        "contentType", "json",
                        "md5", md5
                )
        ));
    }

    private String policyDocument(boolean piiRedactionEnabled) {
        return """
                {
                  "policies": [
                    {
                      "tenantId": "tenant-nacos",
                      "enabled": true,
                      "allowedModels": ["stub-echo-v1"],
                      "piiRedactionEnabled": %s,
                      "exactCacheEnabled": false,
                      "semanticCacheEnabled": false,
                      "requestsPerMinute": 20,
                      "maxConcurrentRequests": 3,
                      "inputPriceUsdPerMillion": 0.22,
                      "outputPriceUsdPerMillion": 0.66,
                      "updatedAt": "2026-08-30T00:00:00Z"
                    }
                  ]
                }
                """.formatted(piiRedactionEnabled);
    }

    private void respond(HttpExchange exchange) throws IOException {
        requestQuery.set(exchange.getRequestURI().getRawQuery());
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode.get(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
