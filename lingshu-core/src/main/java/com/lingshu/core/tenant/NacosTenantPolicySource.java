package com.lingshu.core.tenant;

import com.lingshu.core.config.LingShuProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
        prefix = "lingshu.tenant-policy.nacos",
        name = "enabled",
        havingValue = "true"
)
public class NacosTenantPolicySource implements TenantPolicySource {

    private static final Logger LOGGER = LoggerFactory.getLogger(NacosTenantPolicySource.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final LingShuProperties.Nacos nacos;
    private final URI configUri;
    private volatile Map<String, TenantPolicy> policies = Map.of();
    private volatile String md5 = "";

    public NacosTenantPolicySource(LingShuProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.getTenantPolicy().getNacos().getTimeout())
                .build());
    }

    NacosTenantPolicySource(
            LingShuProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        this.nacos = properties.getTenantPolicy().getNacos();
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configUri = configUri(nacos);
    }

    @PostConstruct
    public void initialize() {
        refresh();
    }

    @Scheduled(fixedDelayString = "${lingshu.tenant-policy.nacos.refresh-delay-ms:5000}")
    public void refresh() {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(configUri)
                    .timeout(nacos.getTimeout())
                    .header("Accept", "application/json")
                    .header("User-Agent", "LingShu-Nacos-HTTP-Client/1")
                    .GET();
            if (nacos.getAccessToken() != null && !nacos.getAccessToken().isBlank()) {
                request.header("Authorization", "Bearer " + nacos.getAccessToken());
            }
            HttpResponse<String> response = httpClient.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Nacos returned HTTP " + response.statusCode());
            }
            Snapshot snapshot = parseSnapshot(response.body());
            if (snapshot.md5().equals(md5)) {
                return;
            }
            policies = snapshot.policies();
            md5 = snapshot.md5();
            LOGGER.info("Nacos tenant policy snapshot updated policies={} md5={}", policies.size(), md5);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Nacos tenant policy refresh interrupted; retaining the last valid snapshot");
        } catch (Exception exception) {
            LOGGER.warn("Nacos tenant policy refresh failed; retaining the last valid snapshot", exception);
        }
    }

    @Override
    public Optional<TenantPolicy> find(String tenantId) {
        return Optional.ofNullable(policies.get(tenantId));
    }

    private Snapshot parseSnapshot(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("Nacos rejected config request: " + root.path("message").asText());
        }
        JsonNode data = root.path("data");
        if (!data.path("success").asBoolean(true)) {
            throw new IllegalStateException("Nacos config response was unsuccessful");
        }
        String content = data.path("content").asText();
        String responseMd5 = data.path("md5").asText();
        if (content.isBlank() || responseMd5.isBlank()) {
            throw new IllegalStateException("Nacos config response is missing content or md5");
        }
        PolicyDocument document = objectMapper.readValue(content, PolicyDocument.class);
        if (document.policies() == null) {
            throw new IllegalStateException("Nacos tenant policy document must contain policies");
        }
        Map<String, TenantPolicy> loaded = new LinkedHashMap<>();
        for (PolicyConfig config : document.policies()) {
            TenantPolicy policy = config.toPolicy();
            if (loaded.putIfAbsent(policy.tenantId(), policy) != null) {
                throw new IllegalStateException("Duplicate Nacos tenant policy: " + policy.tenantId());
            }
        }
        return new Snapshot(responseMd5, Map.copyOf(loaded));
    }

    private URI configUri(LingShuProperties.Nacos properties) {
        String serverUrl = properties.getServerUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("Nacos server URL must be configured");
        }
        String normalized = serverUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        URI baseUri = URI.create(normalized);
        if (!"http".equalsIgnoreCase(baseUri.getScheme())
                && !"https".equalsIgnoreCase(baseUri.getScheme())) {
            throw new IllegalArgumentException("Nacos server URL must use HTTP or HTTPS");
        }
        return URI.create(normalized + "/nacos/v3/client/cs/config?dataId="
                + encode(properties.getDataId())
                + "&groupName=" + encode(properties.getGroupName())
                + "&namespaceId=" + encode(properties.getNamespaceId()));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Snapshot(String md5, Map<String, TenantPolicy> policies) {
    }

    private record PolicyDocument(List<PolicyConfig> policies) {
    }

    private record PolicyConfig(
            String tenantId,
            boolean enabled,
            Set<String> allowedModels,
            boolean piiRedactionEnabled,
            boolean exactCacheEnabled,
            boolean semanticCacheEnabled,
            int requestsPerMinute,
            int maxConcurrentRequests,
            BigDecimal inputPriceUsdPerMillion,
            BigDecimal outputPriceUsdPerMillion,
            String updatedAt
    ) {
        private TenantPolicy toPolicy() {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("Nacos tenant policy tenantId must not be blank");
            }
            Instant timestamp = updatedAt == null || updatedAt.isBlank()
                    ? Instant.now()
                    : Instant.parse(updatedAt);
            return new TenantPolicy(
                    tenantId,
                    enabled,
                    allowedModels,
                    piiRedactionEnabled,
                    exactCacheEnabled,
                    semanticCacheEnabled,
                    requestsPerMinute,
                    maxConcurrentRequests,
                    inputPriceUsdPerMillion,
                    outputPriceUsdPerMillion,
                    timestamp
            );
        }
    }
}
