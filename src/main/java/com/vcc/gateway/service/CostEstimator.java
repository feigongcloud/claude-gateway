package com.vcc.gateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vcc.gateway.model.ModelPricing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Estimates request cost BEFORE sending to upstream API.
 * Uses conservative overestimation to ensure sufficient balance is reserved.
 *
 * Estimation strategy:
 * 1. Parse request body to extract model and messages
 * 2. Estimate input tokens from message content (heuristic: 1 token ≈ 4 chars)
 * 3. Use max_tokens from request or default (1000) for output estimate
 * 4. Apply safety margins: 20% buffer on input, 50% on output
 * 5. Calculate cost using model pricing
 *
 * The overestimation prevents most cases where actual > estimated.
 */
@Service
public class CostEstimator {
    private static final Logger log = LoggerFactory.getLogger(CostEstimator.class);

    private final Map<String, ModelPricing> modelPricingMap;
    private final ObjectMapper objectMapper;

    // Safety margins for estimation (configurable via properties)
    private static final BigDecimal INPUT_TOKEN_MULTIPLIER = new BigDecimal("1.2");  // 20% buffer
    private static final BigDecimal OUTPUT_TOKEN_MULTIPLIER = new BigDecimal("1.5"); // 50% buffer
    private static final int DEFAULT_ESTIMATED_OUTPUT_TOKENS = 1000;  // Conservative default
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    public CostEstimator(Map<String, ModelPricing> modelPricingMap, ObjectMapper objectMapper) {
        this.modelPricingMap = modelPricingMap;
        this.objectMapper = objectMapper;
    }

    /**
     * Estimate cost for a request.
     * Returns CostEstimate with breakdown and safety margins applied.
     */
    public CostEstimate estimateCost(byte[] requestBody) {
        try {
            JsonNode root = objectMapper.readTree(requestBody);

            // Extract model
            String model = root.path("model").asText("unknown");

            // Estimate input tokens from message content
            int estimatedInputTokens = estimateInputTokens(root);

            // Estimate output tokens from max_tokens or use default
            int estimatedOutputTokens = root.path("max_tokens").asInt(DEFAULT_ESTIMATED_OUTPUT_TOKENS);

            // Get pricing for the model
            ModelPricing pricing = modelPricingMap.get(model);
            if (pricing == null) {
                // Try prefix matching for version compatibility
                pricing = findPricingByPrefix(model);
                if (pricing == null) {
                    log.warn("No pricing for model: {}, using highest pricing as fallback", model);
                    pricing = getHighestPricing();
                }
            }

            // Calculate with safety margins
            BigDecimal inputCost = pricing.inputPricePerMillion()
                .multiply(BigDecimal.valueOf(estimatedInputTokens))
                .multiply(INPUT_TOKEN_MULTIPLIER)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

            BigDecimal outputCost = pricing.outputPricePerMillion()
                .multiply(BigDecimal.valueOf(estimatedOutputTokens))
                .multiply(OUTPUT_TOKEN_MULTIPLIER)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

            BigDecimal totalEstimate = inputCost.add(outputCost);

            log.debug("Cost estimate: model={}, inputTokens={}, outputTokens={}, cost=${}",
                     model, estimatedInputTokens, estimatedOutputTokens, totalEstimate);

            return new CostEstimate(
                model,
                estimatedInputTokens,
                estimatedOutputTokens,
                totalEstimate,
                inputCost,
                outputCost
            );

        } catch (Exception e) {
            log.error("Failed to estimate cost: {}", e.getMessage(), e);
            // Return safe maximum estimate to prevent blocking requests
            return new CostEstimate("unknown", 1000, 1000, new BigDecimal("1.0"),
                                   BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /**
     * Estimate input tokens from message content.
     * Rough heuristic: 1 token ≈ 4 characters for English text.
     */
    private int estimateInputTokens(JsonNode root) {
        int charCount = 0;

        // Count characters in messages array
        JsonNode messagesNode = root.path("messages");
        if (messagesNode.isArray()) {
            for (JsonNode msg : messagesNode) {
                String content = msg.path("content").asText("");
                charCount += content.length();
            }
        }

        // Add system prompt if present
        String system = root.path("system").asText("");
        charCount += system.length();

        // Rough conversion: 4 chars = 1 token
        int tokens = charCount / 4;

        // Minimum estimate to avoid division by zero in pricing
        return Math.max(tokens, 100);
    }

    /**
     * Find pricing by model prefix for version compatibility.
     * Example: "claude-opus-4-6" matches "claude-opus" pricing.
     */
    private ModelPricing findPricingByPrefix(String model) {
        for (Map.Entry<String, ModelPricing> entry : modelPricingMap.entrySet()) {
            String baseModel = entry.getKey().split("-\\d")[0]; // Remove version suffix
            if (model.startsWith(baseModel)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Get highest pricing model as fallback.
     * Uses the most expensive model to ensure sufficient reserves.
     */
    private ModelPricing getHighestPricing() {
        return modelPricingMap.values().stream()
            .max((a, b) -> a.outputPricePerMillion().compareTo(b.outputPricePerMillion()))
            .orElse(new ModelPricing(
                "fallback",
                new BigDecimal("15.0"),   // $15 per 1M input tokens
                new BigDecimal("75.0"),   // $75 per 1M output tokens
                new BigDecimal("18.75"),  // $18.75 per 1M cache creation tokens
                new BigDecimal("1.50")    // $1.50 per 1M cache read tokens
            ));
    }

    /**
     * Value object for cost estimate with breakdown.
     */
    public record CostEstimate(
        String model,
        int estimatedInputTokens,
        int estimatedOutputTokens,
        BigDecimal totalCost,
        BigDecimal inputCost,
        BigDecimal outputCost
    ) {
        public int totalTokens() {
            return estimatedInputTokens + estimatedOutputTokens;
        }
    }
}
