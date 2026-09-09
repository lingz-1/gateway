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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelControllerTest {

    @LocalServerPort
    private int port;

    @Test
    void listsConfiguredModelsInStableOrder() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/v1/models"))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"object\":\"list\""));
        assertTrue(response.body().contains("\"id\":\"stub-echo-v1\""));
        assertTrue(response.body().contains("\"id\":\"stub-fast-v1\""));
        assertTrue(response.body().indexOf("stub-echo-v1") < response.body().indexOf("stub-fast-v1"));
        assertTrue(response.body().contains("\"status\":\"UP\""));
    }
}
