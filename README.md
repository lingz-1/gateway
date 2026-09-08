# LingShu

LingShu is an enterprise AI capability platform and intelligent gateway. It separates network concerns from the AI request lifecycle through three Maven modules:

- `lingshu-common`: shared DTOs and error contracts.
- `lingshu-gateway`: Spring Cloud Gateway WebFlux application.
- `lingshu-core`: Spring MVC policy engine and provider abstraction.

## Development environment

The required Conda environment is `lingshu-dev` at `D:\anaconda\envs\lingshu-dev`.

Run the full verification build from PowerShell:

```powershell
.\scripts\mvn.ps1 verify
```

The helper script always uses the project Conda environment and stores Maven dependencies on drive D.

## Local applications

- Gateway: `http://localhost:8080`
- Core: `http://localhost:8081`

For the fastest local smoke test, build and start the in-memory Stub profile with one command. This mode does not require Docker, an API key, or `.env`:

```powershell
.\scripts\start-local.ps1 -Build
```

To start the production-like local infrastructure and Qwen embedding profile, add `-FullInfrastructure`. This requires `.env`, Docker, and the model under `E:\LingShuData\models\Qwen3-Embedding-4B`:

```powershell
.\scripts\start-local.ps1 -Build -FullInfrastructure
```

Alternatively, build the applications and start each one in a separate PowerShell terminal:

```powershell
.\scripts\mvn.ps1 verify
.\scripts\run-core.ps1
.\scripts\run-gateway.ps1
```

Call the non-streaming OpenAI-compatible endpoint through Gateway:

```powershell
$body = @{
    model = "stub-echo-v1"
    messages = @(@{ role = "user"; content = "hello" })
} | ConvertTo-Json -Depth 4

Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8080/v1/chat/completions" `
    -ContentType "application/json" `
    -Headers @{ "X-Tenant-Id" = "local-dev" } `
    -Body $body
```

Use model `stub-fast-v1` to select the second deterministic provider. Responses include processor order, selected provider, trace ID, token estimates, and elapsed time.

Exact caching is enabled by default with an in-memory development store. Cache keys use SHA-256 and isolate tenant, model, prompt version, sampling parameters, roles, and message content. The response header `X-LingShu-Cache` and metadata field `cacheStatus` report `MISS` or `EXACT`.

After starting the local infrastructure, run Core with the persistent Redis exact-cache adapter using:

```powershell
.\scripts\run-core-redis.ps1
```

Redis failures are fail-open: requests continue through the provider path and cache writes are skipped.

Run Core with Redis exact caching and PostgreSQL/pgvector similarity caching using:

```powershell
.\scripts\run-core-semantic.ps1
```

Semantic caching is disabled by default so the regular build never requires PostgreSQL. The current `lexical-hash` embedding provider is a deterministic development stub for exercising the cache pipeline; it is not a production semantic model. Configure the similarity threshold with `LINGSHU_CACHE_SEMANTIC_THRESHOLD`. The local production-like profile uses Qwen3-Embedding-4B at 2560 dimensions.

To use an OpenAI-compatible embedding endpoint, configure the provider before starting Core:

```powershell
$env:LINGSHU_EMBEDDING_PROVIDER = "openai-compatible"
$env:LINGSHU_EMBEDDING_ENDPOINT = "https://provider.example/v1/embeddings"
$env:LINGSHU_EMBEDDING_MODEL = "your-embedding-model"
$env:LINGSHU_EMBEDDING_DIMENSION = "2560"
$env:LINGSHU_EMBEDDING_API_KEY = "your-api-key"
.\scripts\run-core-semantic.ps1
```

The PostgreSQL semantic-cache production table is fixed at 2560 dimensions (stored as pgvector `halfvec(2560)` for HNSW compatibility), so the provider response and `LINGSHU_EMBEDDING_DIMENSION` must both be 2560. Provider errors, timeouts, malformed vectors, and pgvector failures are fail-open: the request continues through the normal Provider path without semantic caching. Keep real keys only in process environment variables or the ignored local `.env` file.

For the local Qwen3-Embedding-4B service, start the `embedding` Compose service and run `scripts/run-core-local-qwen.ps1`. The service listens on `127.0.0.1:8090` and reads the model from `E:/LingShuData/models/Qwen3-Embedding-4B`.

To use the DeepSeek chat provider configured in `.env` together with the local Qwen embedding service, run:

```powershell
.\scripts\run-core-deepseek.ps1
```

This enables the configured model name `deepseek-v4flash` (automatically sent to the DeepSeek API as the supported `deepseek-v4-flash` name), Redis exact caching, and PostgreSQL semantic caching. The script reads `DEEPSEEK_API_KEY` and `DEEPSEEK_BASE_URL` from `.env`; the key is never stored in source code.

### Virtual billing (development)

Virtual billing is enabled by default for development. Each tenant starts with a virtual CNY 10.00 balance. A cache miss is charged from the provider-reported input/output token usage; exact and semantic cache hits cost CNY 0. Failed requests with provider-reported usage are charged by that usage, while failures without usage are recorded as CNY 0. Set `LINGSHU_VIRTUAL_BILLING_PERSISTENCE_ENABLED=true` to persist accounts and usage records in PostgreSQL; the default remains an in-memory ledger for zero-dependency tests. Query persisted development data at `/internal/billing/tenants/{tenantId}` and `/internal/billing/tenants/{tenantId}/usage`. This does not call a billing API or enable Redis budget enforcement. A real DeepSeek request can still consume the external DeepSeek API quota, so use the stub provider for zero-cost local tests.

Evaluate the local semantic-cache threshold with the checked-in Chinese sample set:

```powershell
.\scripts\evaluate-semantic-threshold.ps1
```

The wrapper always invokes `lingshu-dev`; the dataset and report default to `E:\LingShuData\datasets` and `E:\LingShuData\logs`.

API key authentication is disabled by default. Enable it with `LINGSHU_GATEWAY_API_KEY_ENABLED=true` and provide the key through `LINGSHU_GATEWAY_API_KEY`. No external API key or infrastructure service is required for the verification build.

## Dynamic tenant policies

Tenant policies can be read and updated at `/internal/tenants/{tenantId}/policy`. The default zero-dependency profile keeps updates in a thread-safe in-memory store, so changes apply to the next request without restarting Core. Set `LINGSHU_TENANT_POLICY_PERSISTENCE_ENABLED=true` to use PostgreSQL instead. Policies control tenant availability, allowed models, PII redaction, exact and semantic caching, request rate, concurrency, and virtual token prices.

An optional Nacos read source is available through `LINGSHU_TENANT_POLICY_NACOS_ENABLED=true`. It polls the Nacos 3.x HTTP configuration API, compares the returned MD5, and atomically replaces the remote snapshot only after successful validation. Nacos policies take precedence over the local or PostgreSQL store; a fetch or parse failure keeps the last valid remote snapshot. The adapter is disabled by default, uses no Nacos SDK, and accepts its bearer token only through `LINGSHU_NACOS_ACCESS_TOKEN`.

The Nacos data item is a JSON document with a `policies` array. Each entry uses the same fields as the internal tenant-policy API:

```json
{
  "policies": [
    {
      "tenantId": "tenant-a",
      "enabled": true,
      "allowedModels": ["stub-model"],
      "piiRedactionEnabled": true,
      "exactCacheEnabled": true,
      "semanticCacheEnabled": false,
      "requestsPerMinute": 60,
      "maxConcurrentRequests": 8,
      "inputPriceUsdPerMillion": 0.22,
      "outputPriceUsdPerMillion": 0.66
    }
  ]
}
```

Request, latency, token, virtual-cost, and failure metrics include a `tenant` tag so Prometheus can aggregate them per tenant.

Protect these internal endpoints outside local development by enabling `LINGSHU_INTERNAL_ADMIN_KEY_ENABLED` and supplying `LINGSHU_INTERNAL_ADMIN_KEY` through the process environment.

## Local infrastructure

Redis and PostgreSQL/pgvector are isolated under the `lingshu` Compose project. Their persistent data is bind-mounted to `E:\LingShuData`; the Docker installation directory is not used as project storage.

```powershell
.\scripts\infra.ps1 pull
.\scripts\infra.ps1 up
.\scripts\infra.ps1 status
```

- PostgreSQL/pgvector: `127.0.0.1:54320`, database/user `lingshu`.
- Redis: `127.0.0.1:63790`.
- Local passwords are stored in the ignored `.env` file; `.env.example` documents required variables.

Stop only the LingShu infrastructure with:

```powershell
.\scripts\infra.ps1 down
```
