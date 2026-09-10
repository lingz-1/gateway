package com.lingshu.core.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "lingshu.provider.stub.model=shared-chat-model",
                "lingshu.provider.fast-stub.model=shared-chat-model"
        }
)
class MultiProviderRoutingIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void exposesOneLogicalModelAndRecordsSelectedProvider() throws Exception {
        HttpResponse<String> models = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/models"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        String modelMarker = "\"id\":\"shared-chat-model\"";

        assertEquals(200, models.statusCode());
        assertEquals(models.body().indexOf(modelMarker), models.body().lastIndexOf(modelMarker));

        HttpResponse<String> completion = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("X-Tenant-Id", "multi-provider-test")
                        .header("X-Trace-Id", "trace-multi-provider")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"model\":\"shared-chat-model\","
                                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, completion.statusCode());
        assertTrue(completion.body().contains("\"providerAttempts\":[\"stub\"]"));
    }
}
