package com.lingshu.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lingshu")
public class LingShuProperties {

    private final Provider provider = new Provider();
    private final Cache cache = new Cache();
    private final Billing billing = new Billing();
    private final Database database = new Database();
    private final Security security = new Security();
    private final TenantPolicy tenantPolicy = new TenantPolicy();

    public Provider getProvider() {
        return provider;
    }

    public Cache getCache() {
        return cache;
    }

    public Billing getBilling() {
        return billing;
    }

    public Database getDatabase() {
        return database;
    }

    public Security getSecurity() {
        return security;
    }

    public TenantPolicy getTenantPolicy() {
        return tenantPolicy;
    }

    public static class TenantPolicy {

        private final Nacos nacos = new Nacos();
        private final RateLimit rateLimit = new RateLimit();

        public Nacos getNacos() {
            return nacos;
        }

        public RateLimit getRateLimit() {
            return rateLimit;
        }
    }

    public static class RateLimit {

        private String store = "memory";
        private String redisKeyPrefix = "lingshu:rate-limit:";
        private java.time.Duration permitTtl = java.time.Duration.ofMinutes(10);

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public String getRedisKeyPrefix() {
            return redisKeyPrefix;
        }

        public void setRedisKeyPrefix(String redisKeyPrefix) {
            this.redisKeyPrefix = redisKeyPrefix;
        }

        public java.time.Duration getPermitTtl() {
            return permitTtl;
        }

        public void setPermitTtl(java.time.Duration permitTtl) {
            this.permitTtl = permitTtl;
        }
    }

    public static class Nacos {

        private boolean enabled;
        private String serverUrl = "http://127.0.0.1:8848";
        private String namespaceId = "public";
        private String groupName = "LINGSHU";
        private String dataId = "lingshu-tenant-policies.json";
        private String accessToken = "";
        private java.time.Duration timeout = java.time.Duration.ofSeconds(2);
        private long refreshDelayMs = 5_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public void setServerUrl(String serverUrl) {
            this.serverUrl = serverUrl;
        }

        public String getNamespaceId() {
            return namespaceId;
        }

        public void setNamespaceId(String namespaceId) {
            this.namespaceId = namespaceId;
        }

        public String getGroupName() {
            return groupName;
        }

        public void setGroupName(String groupName) {
            this.groupName = groupName;
        }

        public String getDataId() {
            return dataId;
        }

        public void setDataId(String dataId) {
            this.dataId = dataId;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public java.time.Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(java.time.Duration timeout) {
            this.timeout = timeout;
        }

        public long getRefreshDelayMs() {
            return refreshDelayMs;
        }

        public void setRefreshDelayMs(long refreshDelayMs) {
            this.refreshDelayMs = refreshDelayMs;
        }
    }

    public static class Security {

        private boolean internalAdminKeyEnabled;
        private String internalAdminKey = "";

        public boolean isInternalAdminKeyEnabled() {
            return internalAdminKeyEnabled;
        }

        public void setInternalAdminKeyEnabled(boolean internalAdminKeyEnabled) {
            this.internalAdminKeyEnabled = internalAdminKeyEnabled;
        }

        public String getInternalAdminKey() {
            return internalAdminKey;
        }

        public void setInternalAdminKey(String internalAdminKey) {
            this.internalAdminKey = internalAdminKey;
        }
    }

    public static class Database {

        private boolean migrationEnabled;

        public boolean isMigrationEnabled() {
            return migrationEnabled;
        }

        public void setMigrationEnabled(boolean migrationEnabled) {
            this.migrationEnabled = migrationEnabled;
        }
    }

    public static class Billing {

        private boolean enabled;
        private final Virtual virtual = new Virtual();
        private final Outbox outbox = new Outbox();
        private String redisKeyPrefix = "lingshu:budget:";
        private java.time.Duration reservationTtl = java.time.Duration.ofMinutes(5);
        private String jdbcUrl = "jdbc:postgresql://127.0.0.1:54320/lingshu";
        private String username = "lingshu";
        private String password = "";
        private int maximumPoolSize = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Virtual getVirtual() {
            return virtual;
        }

        public Outbox getOutbox() {
            return outbox;
        }

        public String getRedisKeyPrefix() {
            return redisKeyPrefix;
        }

        public void setRedisKeyPrefix(String redisKeyPrefix) {
            this.redisKeyPrefix = redisKeyPrefix;
        }

        public java.time.Duration getReservationTtl() {
            return reservationTtl;
        }

        public void setReservationTtl(java.time.Duration reservationTtl) {
            this.reservationTtl = reservationTtl;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public static class Virtual {

            private boolean enabled = true;
            private java.math.BigDecimal initialBalanceCny = new java.math.BigDecimal("10.00");
            private java.math.BigDecimal inputPriceUsdPerMillion = new java.math.BigDecimal("0.22");
            private java.math.BigDecimal outputPriceUsdPerMillion = new java.math.BigDecimal("0.66");
            private java.math.BigDecimal usdToCny = new java.math.BigDecimal("7.20");
            private boolean persistenceEnabled;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public java.math.BigDecimal getInitialBalanceCny() {
                return initialBalanceCny;
            }

            public void setInitialBalanceCny(java.math.BigDecimal initialBalanceCny) {
                this.initialBalanceCny = initialBalanceCny;
            }

            public java.math.BigDecimal getInputPriceUsdPerMillion() {
                return inputPriceUsdPerMillion;
            }

            public void setInputPriceUsdPerMillion(java.math.BigDecimal inputPriceUsdPerMillion) {
                this.inputPriceUsdPerMillion = inputPriceUsdPerMillion;
            }

            public java.math.BigDecimal getOutputPriceUsdPerMillion() {
                return outputPriceUsdPerMillion;
            }

            public void setOutputPriceUsdPerMillion(java.math.BigDecimal outputPriceUsdPerMillion) {
                this.outputPriceUsdPerMillion = outputPriceUsdPerMillion;
            }

            public java.math.BigDecimal getUsdToCny() {
                return usdToCny;
            }

            public void setUsdToCny(java.math.BigDecimal usdToCny) {
                this.usdToCny = usdToCny;
            }

            public boolean isPersistenceEnabled() {
                return persistenceEnabled;
            }

            public void setPersistenceEnabled(boolean persistenceEnabled) {
                this.persistenceEnabled = persistenceEnabled;
            }
        }

        public static class Outbox {

            private boolean enabled;
            private int batchSize = 100;
            private java.time.Duration pollInterval = java.time.Duration.ofSeconds(5);
            private java.time.Duration claimTimeout = java.time.Duration.ofMinutes(1);
            private String kafkaTopic = "lingshu.billing.events";

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getBatchSize() {
                return batchSize;
            }

            public void setBatchSize(int batchSize) {
                this.batchSize = batchSize;
            }

            public java.time.Duration getPollInterval() {
                return pollInterval;
            }

            public void setPollInterval(java.time.Duration pollInterval) {
                this.pollInterval = pollInterval;
            }

            public java.time.Duration getClaimTimeout() {
                return claimTimeout;
            }

            public void setClaimTimeout(java.time.Duration claimTimeout) {
                this.claimTimeout = claimTimeout;
            }

            public String getKafkaTopic() {
                return kafkaTopic;
            }

            public void setKafkaTopic(String kafkaTopic) {
                this.kafkaTopic = kafkaTopic;
            }
        }
    }

    public static class Cache {

        private final Exact exact = new Exact();
        private final Semantic semantic = new Semantic();

        public Exact getExact() {
            return exact;
        }

        public Semantic getSemantic() {
            return semantic;
        }
    }

    public static class Semantic {

        private boolean enabled;
        private double similarityThreshold = 0.92;
        private java.time.Duration ttl = java.time.Duration.ofHours(24);
        private int embeddingDimension = 64;
        private String embeddingProvider = "lexical-hash";
        private String embeddingModel = "";
        private String embeddingEndpoint = "http://127.0.0.1:11434/v1/embeddings";
        private String embeddingApiKey = "";
        private java.time.Duration embeddingTimeout = java.time.Duration.ofSeconds(10);
        private int embeddingMaxInputChars = 20_000;
        private String jdbcUrl = "jdbc:postgresql://127.0.0.1:54320/lingshu";
        private String username = "lingshu";
        private String password = "";
        private int maximumPoolSize = 4;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public java.time.Duration getTtl() {
            return ttl;
        }

        public void setTtl(java.time.Duration ttl) {
            this.ttl = ttl;
        }

        public int getEmbeddingDimension() {
            return embeddingDimension;
        }

        public void setEmbeddingDimension(int embeddingDimension) {
            this.embeddingDimension = embeddingDimension;
        }

        public String getEmbeddingProvider() {
            return embeddingProvider;
        }

        public void setEmbeddingProvider(String embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public String getEmbeddingEndpoint() {
            return embeddingEndpoint;
        }

        public void setEmbeddingEndpoint(String embeddingEndpoint) {
            this.embeddingEndpoint = embeddingEndpoint;
        }

        public String getEmbeddingApiKey() {
            return embeddingApiKey;
        }

        public void setEmbeddingApiKey(String embeddingApiKey) {
            this.embeddingApiKey = embeddingApiKey;
        }

        public java.time.Duration getEmbeddingTimeout() {
            return embeddingTimeout;
        }

        public void setEmbeddingTimeout(java.time.Duration embeddingTimeout) {
            this.embeddingTimeout = embeddingTimeout;
        }

        public int getEmbeddingMaxInputChars() {
            return embeddingMaxInputChars;
        }

        public void setEmbeddingMaxInputChars(int embeddingMaxInputChars) {
            this.embeddingMaxInputChars = embeddingMaxInputChars;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public void setJdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }
    }

    public static class Exact {

        private boolean enabled = true;
        private java.time.Duration ttl = java.time.Duration.ofMinutes(10);
        private int maxEntries = 10_000;
        private String promptVersion = "v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.time.Duration getTtl() {
            return ttl;
        }

        public void setTtl(java.time.Duration ttl) {
            this.ttl = ttl;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public void setPromptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
        }
    }

    public static class Provider {

        private final Stub stub = new Stub();
        private final FastStub fastStub = new FastStub();
        private final DeepSeek deepseek = new DeepSeek();
        private final OpenAi openai = new OpenAi();

        public Stub getStub() {
            return stub;
        }

        public FastStub getFastStub() {
            return fastStub;
        }

        public DeepSeek getDeepseek() {
            return deepseek;
        }

        public OpenAi getOpenai() {
            return openai;
        }
    }

    public static class Stub {

        private boolean enabled = true;
        private String model = "stub-echo-v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class FastStub {

        private boolean enabled = true;
        private String model = "stub-fast-v1";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class DeepSeek extends HttpChatProvider {

        public DeepSeek() {
            super("deepseek-v4flash", "deepseek-v4-flash", "https://api.deepseek.com");
        }
    }

    public static class OpenAi extends HttpChatProvider {

        public OpenAi() {
            super("gpt-4.1-mini", "gpt-4.1-mini", "https://api.openai.com/v1");
        }
    }

    public static class HttpChatProvider {

        private boolean enabled;
        private String model;
        private String upstreamModel;
        private String baseUrl;
        private String apiKey = "";
        private java.time.Duration timeout = java.time.Duration.ofSeconds(60);
        private int maxAttempts = 1;
        private java.time.Duration retryBackoff = java.time.Duration.ofMillis(200);
        private int maxConcurrentRequests = 8;
        private int circuitFailureThreshold = 5;
        private java.time.Duration circuitOpenDuration = java.time.Duration.ofSeconds(30);

        protected HttpChatProvider(String model, String upstreamModel, String baseUrl) {
            this.model = model;
            this.upstreamModel = upstreamModel;
            this.baseUrl = baseUrl;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getUpstreamModel() {
            return upstreamModel;
        }

        public void setUpstreamModel(String upstreamModel) {
            this.upstreamModel = upstreamModel;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public java.time.Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(java.time.Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public java.time.Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(java.time.Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public int getMaxConcurrentRequests() {
            return maxConcurrentRequests;
        }

        public void setMaxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
        }

        public int getCircuitFailureThreshold() {
            return circuitFailureThreshold;
        }

        public void setCircuitFailureThreshold(int circuitFailureThreshold) {
            this.circuitFailureThreshold = circuitFailureThreshold;
        }

        public java.time.Duration getCircuitOpenDuration() {
            return circuitOpenDuration;
        }

        public void setCircuitOpenDuration(java.time.Duration circuitOpenDuration) {
            this.circuitOpenDuration = circuitOpenDuration;
        }
    }
}
