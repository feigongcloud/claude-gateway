package com.vcc.gateway.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pricing information for a specific Claude model.
 * Prices are per 1M tokens in USD.
 */
public record ModelPricing(
    String model,
    BigDecimal inputPricePerMillion,
    BigDecimal outputPricePerMillion,
    BigDecimal cacheCreationPricePerMillion,
    BigDecimal cacheReadPricePerMillion
) {

    public static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    /**
     * Calculate total cost for given token counts.
     */
    public BigDecimal calculateCost(int inputTokens, int outputTokens,
                                     int cacheCreationTokens, int cacheReadTokens) {
        BigDecimal inputCost = inputPricePerMillion
            .multiply(BigDecimal.valueOf(inputTokens))
            .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

        BigDecimal outputCost = outputPricePerMillion
            .multiply(BigDecimal.valueOf(outputTokens))
            .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

        BigDecimal cacheCreationCost = cacheCreationPricePerMillion
            .multiply(BigDecimal.valueOf(cacheCreationTokens))
            .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

        BigDecimal cacheReadCost = cacheReadPricePerMillion
            .multiply(BigDecimal.valueOf(cacheReadTokens))
            .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);

        return inputCost.add(outputCost).add(cacheCreationCost).add(cacheReadCost);
    }
}
