package com.vcc.gateway.web;

import com.vcc.gateway.model.TenantContext;
import com.vcc.gateway.model.UsageContext;
import com.vcc.gateway.service.RateLimiter;
import com.vcc.gateway.service.TenantService;
import com.vcc.gateway.service.UpstreamClient;
import com.vcc.gateway.service.UsageTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@RestController
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final TenantService tenantService;
    private final RateLimiter rateLimiter;
    private final UpstreamClient upstreamClient;
    private final UsageTracker usageTracker;
    private final ObjectMapper objectMapper;

    public GatewayController(
            TenantService tenantService,
            RateLimiter rateLimiter,
            UpstreamClient upstreamClient,
            UsageTracker usageTracker,
            ObjectMapper objectMapper) {
        this.tenantService = tenantService;
        this.rateLimiter = rateLimiter;
        this.upstreamClient = upstreamClient;
        this.usageTracker = usageTracker;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> proxy(
            ServerHttpRequest request, ServerHttpResponse response, @RequestBody byte[] body) {
        String requestId = UUID.randomUUID().toString();
        boolean stream = isStream(body);
        String model = extractModel(body);

        log.debug("requestId={} stream={} model={} starting proxy", requestId, stream, model);

        // Create usage context for tracking
        UsageContext usageContext = new UsageContext(requestId, model, stream);

        // Use reactive tenant resolution for non-blocking auth
        return tenantService.resolveReactive(request.getHeaders())
                .flatMap(tenant -> {
                    // Apply per-tenant rate limiting with QuotaPolicy
                    if (!rateLimiter.tryConsume(tenant)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"));
                    }

                    // Forward to upstream and write response directly
                    return upstreamClient.forwardAndWrite(body, stream, response, usageContext)
                            .doFinally(signal -> {
                                log.info("=== [GW] doFinally called for requestId={}, signal={}", requestId, signal);
                                log.info("[GW] About to call usageTracker.logAsync - tenant={}, usageContext.tokens={}, usageContext.status={}",
                                        tenant.getTenantId(), usageContext.getTotalTokens(), usageContext.getStatusCode());

                                // Log usage async (fire and forget)
                                usageTracker.logAsync(tenant, usageContext);

                                log.info("[GW] requestId={} tenantId={} stream={} status={} tokens={} duration_ms={}",
                                        requestId,
                                        tenant.getTenantId(),
                                        stream,
                                        response.getStatusCode() != null ? response.getStatusCode().value() : "n/a",
                                        usageContext.getTotalTokens(),
                                        usageContext.getDurationMs());
                            })
                            .contextWrite(ctx -> ctx.put(TenantContextKeys.TENANT_CTX, tenant));
                });
    }

    private boolean isStream(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode streamNode = root.get("stream");
            return streamNode != null && streamNode.isBoolean() && streamNode.booleanValue();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON body", ex);
        }
    }

    private String extractModel(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode modelNode = root.get("model");
            return modelNode != null ? modelNode.asText("unknown") : "unknown";
        } catch (IOException ex) {
            return "unknown";
        }
    }

    @GetMapping("/test/ip")
    public Mono<String> getOutboundIp() {
        return WebClient.create()
                .get()
                .uri("https://ifconfig.me/ip")
                .retrieve()
                .bodyToMono(String.class);
    }
}
