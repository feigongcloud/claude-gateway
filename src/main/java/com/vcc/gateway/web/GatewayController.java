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
    private final com.vcc.gateway.service.CostEstimator costEstimator;
    private final com.vcc.gateway.service.BalanceService balanceService;

    public GatewayController(
            TenantService tenantService,
            RateLimiter rateLimiter,
            UpstreamClient upstreamClient,
            UsageTracker usageTracker,
            ObjectMapper objectMapper,
            com.vcc.gateway.service.CostEstimator costEstimator,
            com.vcc.gateway.service.BalanceService balanceService) {
        this.tenantService = tenantService;
        this.rateLimiter = rateLimiter;
        this.upstreamClient = upstreamClient;
        this.usageTracker = usageTracker;
        this.objectMapper = objectMapper;
        this.costEstimator = costEstimator;
        this.balanceService = balanceService;
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

        // Estimate cost for balance reservation
        com.vcc.gateway.service.CostEstimator.CostEstimate estimate = costEstimator.estimateCost(body);
        log.info("requestId={} estimated cost: ${}", requestId, estimate.totalCost());

        // Use reactive tenant resolution for non-blocking auth
        return tenantService.resolveReactive(request.getHeaders())
                .flatMap(tenant -> {
                    // Apply per-tenant rate limiting with QuotaPolicy
                    if (!rateLimiter.tryConsume(tenant)) {
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"));
                    }

                    // Phase 1: Check and reserve balance
                    return balanceService.checkAndReserve(tenant.getTenantId(), requestId, estimate)
                        .flatMap(reserveResult -> {
                            // Store reserve info for Phase 2 settlement
                            usageContext.setReserveTransactionId(reserveResult.transactionId());
                            usageContext.setReservedCost(reserveResult.reservedAmount());

                            // Forward to upstream and write response directly
                            return upstreamClient.forwardAndWrite(body, stream, response, usageContext)
                                    .doFinally(signal -> {
                                        log.info("=== [GW] doFinally called for requestId={}, signal={}", requestId, signal);

                                        // Phase 2: Settle balance
                                        Integer statusCode = usageContext.getStatusCode();
                                        boolean success = statusCode != null && statusCode >= 200 && statusCode < 300;

                                        if (success && usageContext.getTotalTokens() > 0) {
                                            // Success - settle with actual cost
                                            java.math.BigDecimal actualCost = calculateActualCost(usageContext);
                                            balanceService.settleActualCost(
                                                tenant.getTenantId(),
                                                usageContext.getReserveTransactionId(),
                                                usageContext.getReservedCost(),
                                                actualCost,
                                                usageContext.getTotalTokens(),
                                                model
                                            ).subscribe(
                                                v -> log.info("[GW] ✓ Balance settled for requestId={}", requestId),
                                                e -> log.error("[GW] ✗ Balance settlement failed: {}", e.getMessage())
                                            );
                                        } else {
                                            // Failure or no tokens - refund reserve
                                            balanceService.refundReserve(
                                                tenant.getTenantId(),
                                                usageContext.getReserveTransactionId(),
                                                usageContext.getReservedCost()
                                            ).subscribe(
                                                v -> log.info("[GW] ✓ Reserve refunded for requestId={}", requestId),
                                                e -> log.error("[GW] ✗ Refund failed: {}", e.getMessage())
                                            );
                                        }

                                        // Existing usage tracking
                                        usageTracker.logAsync(tenant, usageContext);

                                        log.info("[GW] requestId={} tenantId={} stream={} status={} tokens={} duration_ms={}",
                                                requestId,
                                                tenant.getTenantId(),
                                                stream,
                                                response.getStatusCode() != null ? response.getStatusCode().value() : "n/a",
                                                usageContext.getTotalTokens(),
                                                usageContext.getDurationMs());
                                    });
                        })
                        .onErrorResume(e -> {
                            // If balance check fails, return error immediately
                            if (e instanceof ResponseStatusException) {
                                response.setStatusCode(((ResponseStatusException) e).getStatusCode());
                                return response.setComplete();
                            }
                            return Mono.error(e);
                        })
                        .contextWrite(ctx -> ctx.put(TenantContextKeys.TENANT_CTX, tenant));
                });
    }

    /**
     * Calculate actual cost from usage context using actual token counts.
     */
    private java.math.BigDecimal calculateActualCost(UsageContext usage) {
        return usageTracker.calculateCost(
            usage.getModel(),
            usage.getInputTokens(),
            usage.getOutputTokens(),
            usage.getCacheCreationInputTokens(),
            usage.getCacheReadInputTokens()
        );
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
