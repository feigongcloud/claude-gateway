package com.vcc.gateway.web;

import com.vcc.gateway.entity.TenantDailyUsageEntity;
import com.vcc.gateway.entity.UsageEventEntity;
import com.vcc.gateway.entity.UsageRequestLogEntity;
import com.vcc.gateway.repository.TenantDailyUsageRepository;
import com.vcc.gateway.repository.UsageEventRepository;
import com.vcc.gateway.repository.UsageRequestLogRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin API endpoints for querying usage data.
 * Protected by AdminSecurityFilter via X-Admin-Api-Key header.
 */
@RestController
@RequestMapping("/admin/usage")
public class UsageAdminController {

    private final UsageRequestLogRepository requestLogRepository;
    private final UsageEventRepository usageEventRepository;
    private final TenantDailyUsageRepository dailyUsageRepository;

    public UsageAdminController(
            UsageRequestLogRepository requestLogRepository,
            UsageEventRepository usageEventRepository,
            TenantDailyUsageRepository dailyUsageRepository) {
        this.requestLogRepository = requestLogRepository;
        this.usageEventRepository = usageEventRepository;
        this.dailyUsageRepository = dailyUsageRepository;
    }

    // ==================== Request Logs ====================

    /**
     * Get recent request logs for a tenant.
     * GET /admin/usage/tenants/{tenantId}/requests?limit=100
     */
    @GetMapping("/tenants/{tenantId}/requests")
    public Flux<UsageRequestLogEntity> getTenantRequests(
            @PathVariable String tenantId,
            @RequestParam(defaultValue = "100") int limit) {
        return requestLogRepository.findRecentByTenant(tenantId, Math.min(limit, 1000));
    }

    /**
     * Get request logs by time range.
     * GET /admin/usage/tenants/{tenantId}/requests/range?from=...&to=...
     */
    @GetMapping("/tenants/{tenantId}/requests/range")
    public Flux<UsageRequestLogEntity> getTenantRequestsByRange(
            @PathVariable String tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return requestLogRepository.findByTenantIdAndTimeRange(tenantId, from, to);
    }

    // ==================== Usage Events ====================

    /**
     * Get usage events for a tenant.
     * GET /admin/usage/tenants/{tenantId}/events
     */
    @GetMapping("/tenants/{tenantId}/events")
    public Flux<UsageEventEntity> getTenantUsageEvents(
            @PathVariable String tenantId) {
        return usageEventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Get usage event for a specific request.
     * GET /admin/usage/requests/{requestId}/event
     */
    @GetMapping("/requests/{requestId}/event")
    public Mono<ResponseEntity<UsageEventEntity>> getRequestUsageEvent(
            @PathVariable String requestId) {
        return usageEventRepository.findByRequestId(requestId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ==================== Daily Usage ====================

    /**
     * Get daily usage for a tenant.
     * GET /admin/usage/tenants/{tenantId}/daily?from=2024-01-01&to=2024-01-31
     */
    @GetMapping("/tenants/{tenantId}/daily")
    public Flux<TenantDailyUsageEntity> getTenantDailyUsage(
            @PathVariable String tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dailyUsageRepository.findByTenantIdAndDayBetweenOrderByDayDesc(tenantId, from, to);
    }

    /**
     * Get today's usage summary for a tenant.
     * GET /admin/usage/tenants/{tenantId}/today
     */
    @GetMapping("/tenants/{tenantId}/today")
    public Mono<ResponseEntity<TenantDailyUsageEntity>> getTenantTodayUsage(
            @PathVariable String tenantId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return dailyUsageRepository.findByTenantIdAndDay(tenantId, today)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ==================== Summary Endpoints ====================

    /**
     * Get usage summary for a tenant (current month).
     * GET /admin/usage/tenants/{tenantId}/summary
     */
    @GetMapping("/tenants/{tenantId}/summary")
    public Mono<ResponseEntity<Map<String, Object>>> getTenantUsageSummary(
            @PathVariable String tenantId) {
        Instant monthStart = LocalDate.now(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        Mono<Long> requestCount = requestLogRepository.countByTenantSince(tenantId, monthStart);
        Mono<Long> totalTokens = usageEventRepository.sumTokensByTenantSince(tenantId, monthStart)
                .defaultIfEmpty(0L);
        Mono<BigDecimal> totalCost = usageEventRepository.sumCostByTenantSince(tenantId, monthStart)
                .defaultIfEmpty(BigDecimal.ZERO);

        return Mono.zip(requestCount, totalTokens, totalCost)
                .map(tuple -> {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("tenantId", tenantId);
                    summary.put("period", "current_month");
                    summary.put("requestCount", tuple.getT1());
                    summary.put("totalTokens", tuple.getT2());
                    summary.put("totalCostUsd", tuple.getT3());
                    summary.put("monthStart", monthStart.toString());
                    return ResponseEntity.ok(summary);
                });
    }
}
