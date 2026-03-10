package com.vcc.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcc.gateway.config.PricingConfig;
import com.vcc.gateway.entity.TenantDailyUsageEntity;
import com.vcc.gateway.entity.UsageEventEntity;
import com.vcc.gateway.entity.UsageRequestLogEntity;
import com.vcc.gateway.model.ModelPricing;
import com.vcc.gateway.model.TenantContext;
import com.vcc.gateway.model.UsageContext;
import com.vcc.gateway.repository.TenantDailyUsageRepository;
import com.vcc.gateway.repository.UsageEventRepository;
import com.vcc.gateway.repository.UsageRequestLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Service for tracking API usage and costs.
 * Handles async logging without blocking the response stream.
 */
@Service
public class UsageTracker {
    private static final Logger log = LoggerFactory.getLogger(UsageTracker.class);
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    private final UsageRequestLogRepository requestLogRepository;
    private final UsageEventRepository usageEventRepository;
    private final TenantDailyUsageRepository dailyUsageRepository;
    private final Map<String, ModelPricing> modelPricingMap;
    private final ObjectMapper objectMapper;

    public UsageTracker(
            UsageRequestLogRepository requestLogRepository,
            UsageEventRepository usageEventRepository,
            TenantDailyUsageRepository dailyUsageRepository,
            Map<String, ModelPricing> modelPricingMap,
            ObjectMapper objectMapper) {
        this.requestLogRepository = requestLogRepository;
        this.usageEventRepository = usageEventRepository;
        this.dailyUsageRepository = dailyUsageRepository;
        this.modelPricingMap = modelPricingMap;
        this.objectMapper = objectMapper;
    }

    /**
     * Parse usage from non-streaming JSON response.
     * Returns the extracted usage data without modifying the response.
     */
    public UsageData parseNonStreamingUsage(byte[] responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode usageNode = root.get("usage");

            if (usageNode == null) {
                log.debug("No usage node found in response");
                return null;
            }

            int inputTokens = usageNode.path("input_tokens").asInt(0);
            int outputTokens = usageNode.path("output_tokens").asInt(0);
            int cacheCreation = usageNode.path("cache_creation_input_tokens").asInt(0);
            int cacheRead = usageNode.path("cache_read_input_tokens").asInt(0);

            // Extract message ID
            String msgId = root.path("id").asText(null);

            String rawJson = objectMapper.writeValueAsString(usageNode);

            return new UsageData(inputTokens, outputTokens, cacheCreation, cacheRead, rawJson, msgId);
        } catch (Exception e) {
            log.warn("Failed to parse usage from response: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse usage from SSE message_start event.
     * Format: {"type":"message_start","message":{"id":"msg_xxx","usage":{"input_tokens":123}}}
     */
    public UsageData parseMessageStartUsage(String sseData) {
        try {
            JsonNode root = objectMapper.readTree(sseData);
            if (!"message_start".equals(root.path("type").asText())) {
                return null;
            }

            JsonNode messageNode = root.path("message");
            JsonNode usageNode = messageNode.path("usage");
            int inputTokens = usageNode.path("input_tokens").asInt(0);
            int cacheCreation = usageNode.path("cache_creation_input_tokens").asInt(0);
            int cacheRead = usageNode.path("cache_read_input_tokens").asInt(0);

            // Extract message ID from message.id
            String msgId = messageNode.path("id").asText(null);

            return new UsageData(inputTokens, 0, cacheCreation, cacheRead, null, msgId);
        } catch (Exception e) {
            log.trace("Failed to parse message_start: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse usage from SSE message_delta event.
     * Format: {"type":"message_delta","usage":{"output_tokens":456}}
     */
    public UsageData parseMessageDeltaUsage(String sseData) {
        try {
            JsonNode root = objectMapper.readTree(sseData);
            if (!"message_delta".equals(root.path("type").asText())) {
                return null;
            }

            JsonNode usageNode = root.path("usage");
            int outputTokens = usageNode.path("output_tokens").asInt(0);

            String rawJson = objectMapper.writeValueAsString(usageNode);

            // message_delta does not contain message ID
            return new UsageData(0, outputTokens, 0, 0, rawJson, null);
        } catch (Exception e) {
            log.trace("Failed to parse message_delta: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract SSE data from a line.
     * SSE format: "data: {...json...}"
     */
    public String extractSseData(String line) {
        if (line != null && line.startsWith("data: ")) {
            return line.substring(6).trim();
        }
        return null;
    }

    /**
     * Get current time in Beijing timezone as LocalDateTime.
     * LocalDateTime has no timezone info, so R2DBC writes it directly as literal value.
     * This ensures database timestamps match Beijing time (UTC+8).
     */
    private java.time.LocalDateTime nowBeijing() {
        return java.time.LocalDateTime.now(BEIJING_ZONE);
    }

    /**
     * Calculate cost for given usage.
     */
    public BigDecimal calculateCost(String model, int inputTokens, int outputTokens,
                                     int cacheCreation, int cacheRead) {
        ModelPricing pricing = modelPricingMap.get(model);
        if (pricing == null) {
            // Try to find a matching model by prefix
            for (Map.Entry<String, ModelPricing> entry : modelPricingMap.entrySet()) {
                if (model.startsWith(entry.getKey().split("-\\d")[0])) {
                    pricing = entry.getValue();
                    break;
                }
            }
        }

        if (pricing == null) {
            log.warn("No pricing found for model: {}, using zero cost", model);
            return BigDecimal.ZERO;
        }

        return pricing.calculateCost(inputTokens, outputTokens, cacheCreation, cacheRead);
    }

    /**
     * Log request and usage asynchronously.
     * This method fires and forgets - DB failures do not affect the caller.
     */
    public void logAsync(TenantContext tenant, UsageContext usage) {
        log.info("[USAGE] logAsync called: requestId={}, tenantId={}, status={}, tokens={}",
                usage.getRequestId(), tenant.getTenantId(), usage.getStatusCode(), usage.getTotalTokens());

        Mono.defer(() -> saveRequestLog(tenant, usage)
                .flatMap(requestLog -> {
                    Integer status = usage.getStatusCode();
                    boolean success = status != null && status >= 200 && status < 300;
                    boolean hasTokens = usage.getTotalTokens() > 0;

                    log.info("[USAGE] Conditions: success={}, hasTokens={}, status={}, totalTokens={}",
                            success, hasTokens, status, usage.getTotalTokens());

                    if (success && hasTokens) {
                        log.info("[USAGE] Saving usage event and daily usage...");
                        return saveUsageEvent(tenant, usage)
                            .doOnSuccess(e -> log.info("[USAGE] Usage event saved: eventId={}", e.getEventId()))
                            .doOnError(e -> log.error("[USAGE] Failed to save usage event: {}", e.getMessage(), e))
                            .then(updateDailyUsage(tenant, usage, true));
                    } else {
                        log.info("[USAGE] Skipping usage event (success={}, hasTokens={}), only updating daily usage",
                                success, hasTokens);
                        return updateDailyUsage(tenant, usage, false);
                    }
                }))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                v -> log.info("[USAGE] ✓ Usage logged for request {}", usage.getRequestId()),
                e -> log.error("[USAGE] ✗ Failed to log usage for request {}: {}",
                    usage.getRequestId(), e.getMessage(), e)
            );
    }

    private Mono<UsageRequestLogEntity> saveRequestLog(TenantContext tenant, UsageContext usage) {
        UsageRequestLogEntity entity = new UsageRequestLogEntity();
        entity.setRequestId(usage.getRequestId());
        entity.setTenantId(tenant.getTenantId());
        entity.setUserId(tenant.getUserId());
        entity.setPlan(tenant.getPlan());
        entity.setModel(usage.getModel());
        entity.setStream(usage.isStream());
        entity.setStatusCode(usage.getStatusCode() != null ? usage.getStatusCode() : 0);
        entity.setDurationMs((int) usage.getDurationMs());
        entity.setUpstreamKeyId(usage.getUpstreamKeyId());
        entity.setErrorCode(usage.getErrorCode());
        entity.setCreatedAt(nowBeijing());

        log.info("[USAGE] Saving request log: requestId={}, tenantId={}, status={}",
                entity.getRequestId(), entity.getTenantId(), entity.getStatusCode());

        return requestLogRepository.save(entity)
                .doOnSuccess(saved -> log.info("[USAGE] ✓ Request log saved: requestId={}", saved.getRequestId()))
                .doOnError(e -> log.error("[USAGE] ✗ Failed to save request log: {}", e.getMessage(), e));
    }

    private Mono<UsageEventEntity> saveUsageEvent(TenantContext tenant, UsageContext usage) {
        BigDecimal cost = calculateCost(
            usage.getModel(),
            usage.getInputTokens(),
            usage.getOutputTokens(),
            usage.getCacheCreationInputTokens(),
            usage.getCacheReadInputTokens()
        );

        UsageEventEntity entity = new UsageEventEntity();
        entity.setRequestId(usage.getRequestId());
        entity.setMsgId(usage.getMsgId());
        entity.setXtraceId(usage.getXtraceId());
        entity.setTenantId(tenant.getTenantId());
        entity.setUserId(tenant.getUserId());
        entity.setModel(usage.getModel());
        entity.setStream(usage.isStream());
        entity.setInputTokens(usage.getInputTokens());
        entity.setOutputTokens(usage.getOutputTokens());
        entity.setTotalTokens(usage.getTotalTokens());
        entity.setCacheCreationInputTokens(usage.getCacheCreationInputTokens());
        entity.setCacheReadInputTokens(usage.getCacheReadInputTokens());
        entity.setPricingVersion(PricingConfig.PRICING_VERSION);
        entity.setCostUsd(cost);
        entity.setRawUsageJson(usage.getRawUsageJson());
        entity.setCreatedAt(nowBeijing());

        return usageEventRepository.save(entity);
    }

    private Mono<Void> updateDailyUsage(TenantContext tenant, UsageContext usage, boolean success) {
        LocalDate today = LocalDate.now(BEIJING_ZONE);
        BigDecimal cost = success ? calculateCost(
            usage.getModel(),
            usage.getInputTokens(),
            usage.getOutputTokens(),
            usage.getCacheCreationInputTokens(),
            usage.getCacheReadInputTokens()
        ) : BigDecimal.ZERO;

        return dailyUsageRepository.upsertDailyUsage(
            tenant.getTenantId(),
            today,
            1,  // request_count
            success ? 1 : 0,  // success_count
            success ? 0 : 1,  // error_count
            success ? usage.getInputTokens() : 0,
            success ? usage.getOutputTokens() : 0,
            success ? usage.getTotalTokens() : 0,
            cost
        );
    }

    /**
     * Value object for parsed usage data.
     */
    public record UsageData(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        String rawJson,
        String msgId
    ) {
        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
