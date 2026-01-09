# Claude Gateway - Architectural Deep Dive

## 1. High-Level Architecture Overview

### System Purpose
This is an **API Gateway/Proxy** that sits between clients and Anthropic's Claude API. It provides multi-tenancy, rate limiting, and API key pooling.

```
┌─────────────────────────────────────────────────────────────────┐
│                         Clients                                  │
│    (SDKs, curl, CLI tools using Bearer token authentication)    │
└──────────────────────────┬──────────────────────────────────────┘
                           │ POST /anthropic/v1/messages
                           │ Authorization: Bearer <tenant-api-key>
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Claude Gateway (Port 8088)                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │TenantService│→ │ RateLimiter │→ │    UpstreamClient       │  │
│  │(Auth)       │  │(Token Bucket)│  │(WebClient + KeyPool)   │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└──────────────────────────┬──────────────────────────────────────┘
                           │ POST /v1/messages
                           │ x-api-key: <anthropic-api-key>
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Anthropic Claude API                           │
│                 https://api.anthropic.com                        │
└─────────────────────────────────────────────────────────────────┘
```

### Main Modules and Responsibilities

| Package | Class | Responsibility |
|---------|-------|----------------|
| `config` | `GwProperties` | Type-safe binding for all `gw.*` configuration |
| `web` | `GatewayController` | Single REST endpoint, orchestrates the proxy flow |
| `web` | `TenantContextKeys` | Constants for Reactor context propagation |
| `service` | `TenantService` | Maps customer API keys → TenantContext |
| `service` | `RateLimiter` | Per-tenant token bucket rate limiting |
| `service` | `KeyPool` | Round-robin selection of upstream Anthropic keys |
| `service` | `UpstreamClient` | Reactive HTTP client to Anthropic API |
| `model` | `TenantContext` | Immutable value object for tenant metadata |

### External Dependencies

- **Anthropic API** (`https://api.anthropic.com/v1/messages`) - the upstream service being proxied
- **Spring Boot 3.2.5** with WebFlux (reactive stack)
- **Project Reactor** (Mono/Flux) for non-blocking I/O
- **Jackson** for JSON parsing (stream detection)

---

## 2. Main Execution Path

### Startup Sequence (`Application.java:11-13`)

```java
SpringApplication.run(Application.class, args);
```

1. Spring Boot initializes, scans `com.vcc.gateway.*` for beans
2. `@EnableConfigurationProperties(GwProperties.class)` binds `application.yml` → `GwProperties`
3. Spring creates beans in dependency order:
   - `GwProperties` (config)
   - `KeyPool` (depends on GwProperties)
   - `TenantService` (depends on GwProperties)
   - `RateLimiter` (depends on GwProperties)
   - `UpstreamClient` (depends on GwProperties, KeyPool)
   - `GatewayController` (depends on all services)

### Request Flow (detailed)

**Step 1: Request arrives at `GatewayController.proxy()` (line 44-75)**

```java
@PostMapping(path = "/anthropic/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
public Mono<Void> proxy(ServerHttpRequest request, ServerHttpResponse response, @RequestBody byte[] body)
```

- Spring WebFlux routes `POST /anthropic/v1/messages` here
- `@RequestBody byte[]` buffers the entire request body (important: this is blocking behavior in a reactive pipeline)

**Step 2: Generate request ID and resolve tenant (lines 46-47)**

```java
String requestId = UUID.randomUUID().toString();
TenantContext tenant = tenantService.resolve(request.getHeaders());
```

- `TenantService.resolve()` extracts `Authorization: Bearer <key>` header
- Looks up key in pre-built `HashMap<String, TenantContext>`
- Throws `401 UNAUTHORIZED` if key is missing/invalid

**Step 3: Detect streaming mode (line 48)**

```java
boolean stream = isStream(body);
```

- Parses JSON body with Jackson to check `"stream": true`
- Uses tree model (`readTree`) to avoid deserializing the entire payload

**Step 4: Rate limiting check (lines 50-52)**

```java
if (!rateLimiter.tryConsume(tenant.getTenantId())) {
    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
}
```

- Token bucket per tenant, default 60 requests/minute
- Synchronous check before entering reactive chain

**Step 5: Forward to upstream (lines 54-65)**

```java
return upstreamClient.forward(body, stream)
    .flatMap(clientResponse -> {
        response.setStatusCode(clientResponse.statusCode());
        response.getHeaders().putAll(clientResponse.headers().asHttpHeaders());
        // ...
        return response.writeWith(bodyFlux);
    })
```

- `UpstreamClient` selects key from pool, makes HTTP POST
- Response is streamed back to client via `Flux<DataBuffer>`
- Headers are copied from upstream response

**Step 6: Logging and context (lines 66-74)**

```java
.doOnTerminate(() -> log.info("requestId={} tenantId={} stream={} status={}", ...))
.contextWrite(ctx -> ctx.put(TenantContextKeys.TENANT_CTX, tenant));
```

- Logs after response completes (success or error)
- Puts tenant in Reactor context (for downstream operators to access)

---

## 3. Core Classes Deep Dive

### `GwProperties.java` - Configuration Binding

**Problem solved**: Type-safe access to YAML configuration with validation.

**Design**:
```java
@ConfigurationProperties(prefix = "gw")
@Validated
public class GwProperties {
    @NotBlank private String upstreamBaseUrl;
    @NotEmpty private List<String> upstreamApiKeys;
    // ...
}
```

**Why this design**:
- `@Validated` + `@NotBlank`/`@NotEmpty` fail fast at startup if config is missing
- Nested `TenantConfig` class mirrors YAML structure
- JavaBean getters/setters required for Spring binding

**Trade-off**: Mutable POJO (Spring requires setters), but values don't change at runtime.

---

### `TenantService.java` - Authentication

**Problem solved**: Map customer API keys to tenant metadata.

**Key implementation** (lines 16-21):
```java
public TenantService(GwProperties properties) {
    for (GwProperties.TenantConfig tenant : properties.getTenants()) {
        tenantsByKey.put(tenant.getApiKey(), new TenantContext(...));
    }
}
```

**Why this design**:
- Pre-loads all tenants into `HashMap` at startup → O(1) lookup at runtime
- `HashMap` (not `ConcurrentHashMap`) because map is built once, never modified

**Critical detail** (line 31):
```java
String apiKey = authorizationHeader.substring("Bearer ".length()).trim();
```
- The `.trim()` is important - handles whitespace in headers

---

### `RateLimiter.java` - Token Bucket Algorithm

**Problem solved**: Per-tenant rate limiting without external dependencies (Redis, etc).

**Key implementation** (lines 17-19):
```java
public boolean tryConsume(String tenantId) {
    TokenBucket bucket = buckets.computeIfAbsent(tenantId, id -> new TokenBucket(defaultRpm));
    return bucket.tryConsume();
}
```

**Token bucket mechanics** (lines 42-53):
```java
private void refill() {
    double tokensPerSecond = capacity / 60.0d;  // capacity is RPM
    double add = (elapsedNanos / 1_000_000_000.0d) * tokensPerSecond;
    tokens = Math.min(capacity, tokens + add);
}
```

**Design trade-offs**:
- `synchronized` on `tryConsume()` - simple but creates contention under load
- `ConcurrentHashMap` for buckets - thread-safe lazy creation
- `double` for tokens - allows fractional token accumulation for smooth refill

**Edge case handling** (line 28):
```java
this.capacity = Math.max(1, capacity);  // Prevents divide-by-zero
```

---

### `KeyPool.java` - Round-Robin Key Selection

**Problem solved**: Distribute load across multiple Anthropic API keys.

**Key implementation** (lines 30-32):
```java
public String nextKey() {
    int next = Math.floorMod(index.getAndIncrement(), keys.size());
    return keys.get(next);
}
```

**Why `Math.floorMod`**:
- `AtomicInteger` can overflow to negative after 2^31 increments
- `%` in Java returns negative for negative operands
- `Math.floorMod(-1, 3)` returns `2`, not `-1`

**Trade-off**: Round-robin doesn't consider key health/quota. If one key is exhausted, requests will still hit it.

---

### `UpstreamClient.java` - HTTP Proxy

**Problem solved**: Forward requests to Anthropic API with correct headers.

**Key implementation** (lines 34-42):
```java
return webClient
    .post()
    .uri("/v1/messages")
    .header("x-api-key", apiKey)
    .header("anthropic-version", properties.getAnthropicVersion())
    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
    .header(HttpHeaders.ACCEPT, stream ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE)
    .body(BodyInserters.fromValue(body))
```

**Why `exchangeToMono` instead of `retrieve()`** (line 43):
```java
.exchangeToMono(clientResponse -> { ... })
```
- `retrieve()` would throw on 4xx/5xx
- `exchangeToMono` gives access to raw response for proxying

**Current bug** (lines 43-50): On error, body is consumed for logging but then `Mono.just(clientResponse)` returns a response with already-consumed body. The downstream `response.writeWith(bodyFlux)` will write empty.

---

### `GatewayController.java` - Orchestration

**Problem solved**: Single endpoint that orchestrates auth, rate limiting, proxying.

**Stream detection** (lines 77-85):
```java
private boolean isStream(byte[] body) {
    JsonNode root = objectMapper.readTree(body);
    JsonNode streamNode = root.get("stream");
    return streamNode != null && streamNode.isBoolean() && streamNode.booleanValue();
}
```

**Why Jackson tree model**:
- Don't need full Anthropic request schema
- Only need to check one field
- `readTree` is faster than binding to POJO

---

## 4. Important Technical Details

### Implicit Assumptions

1. **Tenant map is static**: No way to add/remove tenants at runtime without restart
2. **All tenants share the same rate limit**: `defaultRpm` applies uniformly (line 18 in RateLimiter)
3. **Body fits in memory**: `@RequestBody byte[]` loads entire body into heap
4. **Keys are trusted**: No validation that keys start with `sk-ant-`

### Non-Obvious Behaviors

1. **Request body is buffered twice**: Once by `@RequestBody byte[]`, once when `BodyInserters.fromValue()` serializes for upstream
2. **Context is written AFTER the pipeline is built** (line 74): `contextWrite` applies to the whole chain above it
3. **Rate limiter is checked synchronously**: Even though the rest is reactive, `tryConsume()` blocks

### Potential Pitfalls & Edge Cases

| Issue | Location | Impact |
|-------|----------|--------|
| **Error response body lost** | `UpstreamClient:46-48` | After logging error body, client receives empty response |
| **No retry logic** | `UpstreamClient` | Transient failures (network, 5xx) fail immediately |
| **No timeout** | `UpstreamClient` | Upstream can hang indefinitely |
| **Memory pressure** | `GatewayController:45` | Large request bodies buffered in heap |
| **Rate limit bypass** | `GatewayController:50-52` | Check happens before reactive chain, but exception might not be properly handled by WebFlux |
| **Key logging in production** | `UpstreamClient:32-33` | Debug logging exposes key prefix |
| **Integer overflow** | `KeyPool:31` | After 2^31 requests, `index` wraps (handled by `floorMod`) |

### Security Concerns

1. **API keys in `application.yml`**: Should use environment variables or secrets manager
2. **No TLS termination**: Relies on external proxy for HTTPS
3. **No request validation**: Arbitrary JSON passed to Anthropic (potential for abuse)
4. **Tenant key not rotatable**: Changing key requires config change + restart

---

## 5. Network/Service Details

### Request Entry
- HTTP POST on port 8088 (configurable via `server.port`)
- Path: `/anthropic/v1/messages`
- Required header: `Authorization: Bearer <tenant-key>`
- Body: Any JSON (passed through to Anthropic)

### Processing Pipeline
```
HTTP Request
    → Netty (WebFlux)
    → GatewayController.proxy()
    → TenantService.resolve() [sync, throws 401]
    → RateLimiter.tryConsume() [sync, throws 429]
    → UpstreamClient.forward() [async]
        → KeyPool.nextKey() [sync]
        → WebClient.post() [async]
    → Response.writeWith() [streaming]
```

### Response Generation
- Status code: Copied from upstream
- Headers: Copied from upstream (with Content-Type override for streaming)
- Body: Streamed directly from upstream via `Flux<DataBuffer>`

---

## 6. Configuration Reference

### `application.yml`
```yaml
server:
  port: 8088                    # Gateway listen port

logging:
  level:
    org.springframework.web.reactive.function.client.ExchangeFunctions: TRACE  # Log outbound requests
    reactor.netty.http.client: DEBUG  # Log HTTP client details

gw:
  upstreamBaseUrl: https://api.anthropic.com    # Anthropic API endpoint
  anthropicVersion: 2023-06-01                  # Sent as anthropic-version header
  upstreamApiKeys:                              # Pool of Anthropic keys (round-robin)
    - sk-ant-xxx
    - sk-ant-yyy
  defaultRpm: 60                                # Rate limit (requests per minute per tenant)
  tenants:                                      # Customer key → tenant mapping
    - apiKey: demo-key                          # Customer's Bearer token
      tenantId: demo-tenant                     # For logging/rate limiting
      userId: demo-user                         # Metadata (not currently used)
      plan: basic                               # Metadata (not currently used)
```

### Environment Variable Override
Spring Boot allows overriding via environment:
```bash
GW_UPSTREAMBASEURL=https://api.anthropic.com
GW_UPSTREAMAPIKEYS_0=sk-ant-xxx
GW_DEFAULTRPM=120
```

---

## 7. Suggested Reading Order

If you only have time for 20% of the code, read in this order:

### Priority 1: Core Flow (understand what this does)
1. **`GatewayController.java`** (87 lines) - The entire request lifecycle in one method. Start here.
2. **`application.yml`** (18 lines) - Understand the configuration structure.

### Priority 2: Authentication & Rate Limiting (understand access control)
3. **`TenantService.java`** (45 lines) - How customer keys map to tenants.
4. **`RateLimiter.java`** (56 lines) - Token bucket implementation details.

### Priority 3: Upstream Communication (understand the proxy mechanics)
5. **`UpstreamClient.java`** (53 lines) - How requests are forwarded.
6. **`KeyPool.java`** (34 lines) - Key rotation logic.

### Lower Priority (read if needed)
- `GwProperties.java` - Standard Spring config binding, read if you need to add config
- `TenantContext.java` - Simple value object
- `TenantContextKeys.java` - Single constant
- `Application.java` - Boilerplate entry point
- `pom.xml` - Dependencies (note: Java 21 declared but compiler targets 11 - potential issue)

---

## Summary

This is a well-structured, minimal API gateway with clear separation of concerns. The codebase is ~400 lines of Java, intentionally simple. Key strengths:
- Clean reactive design with Spring WebFlux
- Proper separation: auth, rate limiting, proxying in separate services
- Stateless (except in-memory rate limit buckets)

Key areas for improvement:
- Error handling (error bodies are lost)
- Observability (no metrics, distributed tracing)
- Resilience (no retries, timeouts, circuit breakers)
- Configuration (secrets in YAML, no dynamic tenant management)
