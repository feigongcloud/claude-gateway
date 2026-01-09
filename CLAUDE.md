# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Java 21 Spring Boot application that implements an Anthropic-compatible API Gateway. It acts as a transparent proxy between clients and the official Anthropic Claude API, providing multi-tenancy, rate limiting, and API key pooling.

## Build Commands

```bash
mvn clean package          # Build the project
mvn clean install          # Build and install locally
mvn spring-boot:run        # Run the application (port 8088)
```

Requires Java 21+ and Maven.

## Architecture

```
Client (Authorization: Bearer <customer_key>)
  ↓
Claude API Gateway (Spring WebFlux)
  ↓ (x-api-key: <anthropic_key>)
Anthropic Claude API
```

### Core Components

| Package | Component | Purpose |
|---------|-----------|---------|
| `web` | `GatewayController` | Main endpoint (`POST /anthropic/v1/messages`), handles streaming/non-streaming requests |
| `service` | `TenantService` | Bearer token validation, resolves customer key to TenantContext |
| `service` | `RateLimiter` | Token bucket rate limiting per tenant (default 60 rpm) |
| `service` | `UpstreamClient` | Reactive HTTP client to Anthropic API |
| `service` | `KeyPool` | Round-robin management of multiple upstream Anthropic API keys |
| `config` | `GwProperties` | Type-safe configuration binding |

### Request Flow

1. Client sends POST with Bearer token
2. TenantService validates token → TenantContext
3. RateLimiter checks tenant quota (429 if exceeded)
4. Controller detects `"stream": true` in body using Jackson Tree Model
5. UpstreamClient forwards request with Anthropic key from KeyPool
6. Response returned as-is (SSE for streaming, JSON otherwise)

## Key Patterns

- **Reactive programming**: Uses Project Reactor (`Mono<T>`, `Flux<T>`) throughout
- **Stream detection**: Parses JSON body to detect stream flag without full deserialization
- **Context propagation**: Tenant context stored in Reactor Context (`TENANT_CTX` key)
- **Key pooling**: AtomicInteger counter for round-robin key selection

## Configuration

Configuration in `src/main/resources/application.yml`:

```yaml
gw:
  upstreamBaseUrl: https://api.anthropic.com
  anthropicVersion: 2023-06-01
  upstreamApiKeys:
    - KEY_1
    - KEY_2
  defaultRpm: 60
  tenants:
    - apiKey: demo-key
      tenantId: demo-tenant
      userId: demo-user
      plan: basic
```

## Testing the API

```bash
curl -X POST http://localhost:8088/anthropic/v1/messages \
  -H "Authorization: Bearer demo-key" \
  -H "Content-Type: application/json" \
  -d '{"model":"claude-3-opus-20240229","max_tokens":1024,"messages":[{"role":"user","content":"Hello"}]}'
```
