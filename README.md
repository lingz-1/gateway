# LingShu

LingShu is an enterprise AI capability platform and intelligent gateway. It separates network concerns from the AI request lifecycle through three Maven modules:

- `lingshu-common`: shared DTOs and error contracts.
- `lingshu-gateway`: Spring Cloud Gateway WebFlux application.
- `lingshu-core`: Spring MVC policy engine and provider abstraction.

The repository also includes `lingshu-web`, a React and TypeScript user console for chatting through the gateway and inspecting cache, token, billing, trace, and processor metadata.

## Development environment

The required Conda environment is named `lingshu-dev`. The PowerShell wrappers accept an explicit `LINGSHU_CONDA_EXE`, recognize the existing `D:\anaconda` workstation layout, and otherwise discover `conda` from `PATH`.

Run the full verification build from PowerShell:

```powershell
.\scripts\mvn.ps1 verify
```

The helper script always uses the project Conda environment and stores Maven dependencies inside that environment. Set `LINGSHU_MAVEN_REPOSITORY` to override the cache directory.

## Local applications

- Web console: `http://localhost:5173`
- Gateway: `http://localhost:8080`
- Core: `http://localhost:8081`

For the fastest local smoke test, build and start the in-memory Stub profile with one command. This mode does not require Docker, an API key, or `.env`:

```powershell
.\scripts\start-local.ps1 -Build
```

Open `http://localhost:5173` after the command reports that LingShu is ready. The default profile starts the Web console, Gateway, and Core with deterministic local Stub providers. Stop all three applications with:

```powershell
.\scripts\stop-local.ps1
```

Run the frontend unit tests and production build with the environment-bound npm wrapper:

```powershell
.\scripts\npm.ps1 test
.\scripts\npm.ps1 run build
```

With all three applications running, exercise the real browser flow in headless Microsoft Edge. The test sends streaming requests, verifies an exact-cache hit, checks the mobile drawers, and saves screenshots under the ignored `lingshu-web/test-results` directory:

```powershell
.\scripts\python.ps1 .\scripts\test-web-e2e.py
```

Run the dependency-free mixed streaming/non-streaming load test against Gateway:

```powershell
.\scripts\python.ps1 .\scripts\load-test.py --requests 200 --concurrency 20 --prompt-cardinality 200 --stream-ratio 0.5
```

Add `--warm-cache` and use a smaller prompt cardinality for an exact-cache baseline. The script reports throughput, latency P50/P95/P99, streaming TTFT P50/P95/P99, HTTP status codes, errors, and cache-result counts. The first recorded local baseline is documented in [`docs/performance-baseline.md`](docs/performance-baseline.md).

To start the production-like local infrastructure and Qwen embedding profile, add `-FullInfrastructure`. This requires `.env`, Docker, and the model under `${LINGSHU_DATA_ROOT}/models/Qwen3-Embedding-4B`. The checked-in example defaults `LINGSHU_DATA_ROOT` to `E:\LingShuData`, but another absolute data directory may be supplied locally:

```powershell
.\scripts\start-local.ps1 -Build -FullInfrastructure
```

Alternatively, build the applications and start each one in a separate PowerShell terminal:

```powershell
.\scripts\mvn.ps1 verify
.\scripts\run-core.ps1
.\scripts\run-gateway.ps1
.\scripts\run-web.ps1
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

Use model `stub-fast-v1` to select the second deterministic provider. Responses include processor order, selected provider, provider attempt order, trace ID, token estimates, and elapsed time.

The Chat Completions request accepts the OpenAI-compatible scalar sampling fields `temperature`, `max_tokens`, `top_p`, `seed`, `frequency_penalty`, and `presence_penalty`. They are validated at the API boundary, forwarded to compatible upstream providers, and included in exact and semantic cache isolation.

Function tool calling is supported across request forwarding and provider responses. Requests may provide `tools` and `tool_choice`; assistant messages may contain `tool_calls`, and tool result messages use `role: "tool"` with `tool_call_id`. Both non-streaming responses and streamed `delta.tool_calls` preserve call IDs, function names, and JSON argument fragments. Tool-enabled conversations bypass exact and semantic caches so generated call IDs and tool decisions are never replayed from cache.

Message `content` accepts either a plain string or OpenAI-compatible structured content blocks. User messages support `text`, `image_url`, `input_audio`, and `file`; assistant messages additionally support `refusal` alongside text. Structured requests retain their complete content in the exact-cache key, while semantic caching is skipped because the development embedding path is text-only. PII redaction only transforms textual fields, and the Stub provider represents media as `[image]`, `[audio]`, or `[file]` without echoing binary or data-URL payloads.

Multiple providers may advertise the same logical model. The router ignores unhealthy candidates and orders the remaining candidates using recent latency, current in-flight load, and consecutive-failure penalties. If an invocation fails, Core automatically tries the next candidate. Failed-attempt token usage is retained for billing, and `metadata.providerAttempts` exposes the attempted provider order. For a local fallback exercise, configure `LINGSHU_STUB_MODEL` and `LINGSHU_FAST_STUB_MODEL` with the same value before starting Core.

Streaming requests use the Provider streaming SPI end to end. DeepSeek SSE deltas are forwarded through Core and Gateway as they arrive, the upstream body is closed when the client disconnects, and cache writes, usage metadata, billing, and success metrics are finalized only after the provider stream ends. When a successful upstream stream omits `usage`, Core falls back to a deterministic local token estimate. Accumulated content is bounded by `LINGSHU_DEEPSEEK_MAX_STREAM_RESPONSE_CHARS` or `LINGSHU_OPENAI_MAX_STREAM_RESPONSE_CHARS` (default `1000000`) before a delta is sent downstream. Provider fallback is allowed before the first emitted delta; after output starts, LingShu fails the stream instead of mixing content from different providers.

Exact caching is enabled by default with an in-memory development store. Cache keys use SHA-256 and isolate tenant, model, prompt version, sampling parameters, roles, and complete message content, including structured media descriptors. The response header `X-LingShu-Cache` and metadata field `cacheStatus` report `MISS` or `EXACT`. Tool-enabled conversations always report `MISS` because their cache processors are intentionally skipped.

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

This enables the logical model name `deepseek-v4flash` while sending the configured upstream model `deepseek-v4-flash` to DeepSeek, plus Redis exact caching and PostgreSQL semantic caching. The script reads `DEEPSEEK_API_KEY` and `DEEPSEEK_BASE_URL` from `.env`; the key is never stored in source code.

Core also includes a disabled-by-default OpenAI Chat Completions provider using the same non-streaming and SSE reliability path. Configure `OPENAI_API_KEY`, optionally set `OPENAI_BASE_URL`, `LINGSHU_OPENAI_MODEL`, and `LINGSHU_OPENAI_UPSTREAM_MODEL`, then enable it with `LINGSHU_PROVIDER_OPENAI_ENABLED=true`. `LINGSHU_*_MODEL` is the gateway's logical routing name; `LINGSHU_*_UPSTREAM_MODEL` is sent to the provider API. Giving OpenAI and DeepSeek the same logical model name while retaining their distinct upstream model names enables health/latency/load-aware selection and fallback between both real providers. OpenAI recommends the Responses API for new projects, but Chat Completions remains available here because LingShu exposes an OpenAI-compatible Chat Completions gateway contract.

### Virtual billing (development)

Virtual billing is enabled by default for development. Each tenant starts with a virtual CNY 10.00 balance. A cache miss is charged from the provider-reported input/output token usage; exact and semantic cache hits cost CNY 0. Failed requests with provider-reported usage are charged by that usage, while failures without usage are recorded as CNY 0. Set `LINGSHU_VIRTUAL_BILLING_PERSISTENCE_ENABLED=true` to persist accounts and usage records in PostgreSQL; the default remains an in-memory ledger for zero-dependency tests. Query persisted development data at `/internal/billing/tenants/{tenantId}` and `/internal/billing/tenants/{tenantId}/usage`. This does not call a billing API or enable Redis budget enforcement. A real DeepSeek request can still consume the external DeepSeek API quota, so use the stub provider for zero-cost local tests.

Evaluate the local semantic-cache threshold with the checked-in Chinese sample set:

```powershell
.\scripts\evaluate-semantic-threshold.ps1
```

The wrapper always invokes `lingshu-dev`; the dataset and report default to the `datasets` and `logs` directories below `LINGSHU_DATA_ROOT`.

API key authentication is disabled by default. Enable it with `LINGSHU_GATEWAY_API_KEY_ENABLED=true` and provide the key through `LINGSHU_GATEWAY_API_KEY`. No external API key or infrastructure service is required for the verification build.

Gateway validates Chat Completions requests before forwarding them to Core. `X-Tenant-Id` is required by default and must contain 1–128 ASCII letters, digits, dots, underscores, or hyphens, starting with a letter or digit. The endpoint requires `application/json`, rejects duplicate tenant headers, and limits both fixed-length and chunked request bodies to 20 MiB. Override the size with `LINGSHU_GATEWAY_MAX_BODY_BYTES`, or disable the tenant-header requirement only for legacy local clients with `LINGSHU_GATEWAY_TENANT_ID_REQUIRED=false`. Policy rejections use the shared JSON error contract and always include `X-Trace-Id` plus `Cache-Control: no-store`.

## Dynamic tenant policies

Tenant policies can be read and updated at `/internal/tenants/{tenantId}/policy`. The default zero-dependency profile keeps updates in a thread-safe in-memory store, so changes apply to the next request without restarting Core. Set `LINGSHU_TENANT_POLICY_PERSISTENCE_ENABLED=true` to use PostgreSQL instead. Policies control tenant availability, allowed models, PII redaction, exact and semantic caching, request rate, concurrency, and virtual token prices.

Tenant request limits also default to an in-memory fixed-minute window. Set `LINGSHU_TENANT_RATE_LIMIT_STORE=redis` to enforce RPM and concurrent-request limits across Core instances. Acquisition uses one Redis Lua script, Redis server time, and tenant-scoped keys in the same cluster hash slot. Concurrent permits expire after `LINGSHU_TENANT_RATE_LIMIT_PERMIT_TTL` (default `10m`) so a crashed instance cannot hold capacity forever; configure this TTL above the longest allowed request duration. Redis acquisition failures are fail-closed, while release failures are recovered by the permit TTL.

The full-infrastructure and Redis Core launch scripts select the Redis limiter automatically. The default `start-local.ps1` path explicitly keeps the in-memory limiter so the frontend remains testable without infrastructure.

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

Redis and PostgreSQL/pgvector are isolated under the `lingshu` Compose project. Their persistent data is bind-mounted below `LINGSHU_DATA_ROOT`; `.env.example` uses `E:\LingShuData` for this workstation, but the repository does not require Docker itself to be installed on a specific drive. `infra.ps1` discovers `docker` from `PATH`, with `LINGSHU_DOCKER_COMPOSE_EXE` available for a standalone Compose executable.

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

## Web verification

Run the frontend unit tests and production build through the project environment:

```powershell
.\scripts\npm.ps1 test
.\scripts\npm.ps1 run build
```

With all three local applications running, execute the Edge/Playwright browser smoke test. It sends a streaming request, repeats it in a new conversation, verifies an exact-cache hit, and writes an ignored screenshot under `lingshu-web/test-results`:

```powershell
.\scripts\python.ps1 .\scripts\test-web-e2e.py
```
