package com.vcc.gateway.config;

import com.vcc.gateway.model.ModelPricing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class PricingConfig {
    private static final Logger log = LoggerFactory.getLogger(PricingConfig.class);

    public static final String PRICING_VERSION = "v2";

    @Bean
    public Map<String, ModelPricing> modelPricingMap() {
        Map<String, ModelPricing> pricing = new HashMap<>();

        // ==================== Claude 4.6 Series (Latest - 2026) ====================

        // Claude Opus 4.6 (Most capable flagship model)
        // Official pricing from https://platform.claude.com/docs/en/about-claude/pricing
        pricing.put("claude-opus-4-6", new ModelPricing(
            "claude-opus-4-6",
            new BigDecimal("5.00"),    // $5/1M input tokens
            new BigDecimal("25.00"),   // $25/1M output tokens
            new BigDecimal("6.25"),    // $6.25/1M cache writes (5-min TTL)
            new BigDecimal("0.50")     // $0.50/1M cache reads
        ));

        // Claude Sonnet 4.6 (Balanced performance)
        pricing.put("claude-sonnet-4-6", new ModelPricing(
            "claude-sonnet-4-6",
            new BigDecimal("3.00"),    // $3/1M input tokens
            new BigDecimal("15.00"),   // $15/1M output tokens
            new BigDecimal("3.75"),    // $3.75/1M cache writes
            new BigDecimal("0.30")     // $0.30/1M cache reads
        ));

        // ==================== Claude 4.5 Series ====================

        // Claude Opus 4.5 (Previous flagship)
        pricing.put("claude-opus-4-5", new ModelPricing(
            "claude-opus-4-5",
            new BigDecimal("5.00"),    // $5/1M input tokens
            new BigDecimal("25.00"),   // $25/1M output tokens
            new BigDecimal("6.25"),    // $6.25/1M cache writes
            new BigDecimal("0.50")     // $0.50/1M cache reads
        ));

        // Support dated version as well
        pricing.put("claude-opus-4-5-20251101", new ModelPricing(
            "claude-opus-4-5-20251101",
            new BigDecimal("5.00"),
            new BigDecimal("25.00"),
            new BigDecimal("6.25"),
            new BigDecimal("0.50")
        ));

        // Claude Sonnet 4.5 (High performance, cost-effective)
        pricing.put("claude-sonnet-4-5", new ModelPricing(
            "claude-sonnet-4-5",
            new BigDecimal("3.00"),    // $3/1M input tokens
            new BigDecimal("15.00"),   // $15/1M output tokens
            new BigDecimal("3.75"),    // $3.75/1M cache writes
            new BigDecimal("0.30")     // $0.30/1M cache reads
        ));

        // Support dated version
        pricing.put("claude-sonnet-4-5-20250929", new ModelPricing(
            "claude-sonnet-4-5-20250929",
            new BigDecimal("3.00"),
            new BigDecimal("15.00"),
            new BigDecimal("3.75"),
            new BigDecimal("0.30")
        ));

        // Claude Haiku 4.5 (Fast and economical)
        pricing.put("claude-haiku-4-5", new ModelPricing(
            "claude-haiku-4-5",
            new BigDecimal("1.00"),    // $1/1M input tokens
            new BigDecimal("5.00"),    // $5/1M output tokens
            new BigDecimal("1.25"),    // $1.25/1M cache writes
            new BigDecimal("0.10")     // $0.10/1M cache reads
        ));

        // ==================== Claude 4.1 Series ====================

        // Claude Opus 4.1
        pricing.put("claude-opus-4-1", new ModelPricing(
            "claude-opus-4-1",
            new BigDecimal("15.00"),   // $15/1M input tokens
            new BigDecimal("75.00"),   // $75/1M output tokens
            new BigDecimal("18.75"),   // $18.75/1M cache writes
            new BigDecimal("1.50")     // $1.50/1M cache reads
        ));

        // ==================== Claude 4 Series ====================

        // Claude Opus 4
        pricing.put("claude-opus-4", new ModelPricing(
            "claude-opus-4",
            new BigDecimal("15.00"),   // $15/1M input tokens
            new BigDecimal("75.00"),   // $75/1M output tokens
            new BigDecimal("18.75"),   // $18.75/1M cache writes
            new BigDecimal("1.50")     // $1.50/1M cache reads
        ));

        // Claude Sonnet 4
        pricing.put("claude-sonnet-4", new ModelPricing(
            "claude-sonnet-4",
            new BigDecimal("3.00"),    // $3/1M input tokens
            new BigDecimal("15.00"),   // $15/1M output tokens
            new BigDecimal("3.75"),    // $3.75/1M cache writes
            new BigDecimal("0.30")     // $0.30/1M cache reads
        ));

        // ==================== Claude 3.7 Series ====================

        // Claude Sonnet 3.7 (deprecated but still available)
        pricing.put("claude-sonnet-3-7", new ModelPricing(
            "claude-sonnet-3-7",
            new BigDecimal("3.00"),    // $3/1M input tokens
            new BigDecimal("15.00"),   // $15/1M output tokens
            new BigDecimal("3.75"),    // $3.75/1M cache writes
            new BigDecimal("0.30")     // $0.30/1M cache reads
        ));

        // ==================== Claude 3.5 Series ====================

        // Claude 3.5 Sonnet (October 2024 - Latest 3.5)
        pricing.put("claude-3-5-sonnet-20241022", new ModelPricing(
            "claude-3-5-sonnet-20241022",
            new BigDecimal("3.00"),    // $3/1M input tokens
            new BigDecimal("15.00"),   // $15/1M output tokens
            new BigDecimal("3.75"),    // $3.75/1M cache writes
            new BigDecimal("0.30")     // $0.30/1M cache reads
        ));

        // Claude 3.5 Sonnet (June 2024 - Previous version)
        pricing.put("claude-3-5-sonnet-20240620", new ModelPricing(
            "claude-3-5-sonnet-20240620",
            new BigDecimal("3.00"),
            new BigDecimal("15.00"),
            new BigDecimal("3.75"),
            new BigDecimal("0.30")
        ));

        // Claude 3.5 Haiku (October 2024 - Latest)
        pricing.put("claude-3-5-haiku-20241022", new ModelPricing(
            "claude-3-5-haiku-20241022",
            new BigDecimal("1.00"),    // $1/1M input tokens
            new BigDecimal("5.00"),    // $5/1M output tokens
            new BigDecimal("1.25"),    // $1.25/1M cache writes
            new BigDecimal("0.10")     // $0.10/1M cache reads
        ));

        // Claude 3.5 Haiku (Earlier version - lower pricing)
        pricing.put("claude-3-5-haiku", new ModelPricing(
            "claude-3-5-haiku",
            new BigDecimal("0.80"),    // $0.80/1M input tokens
            new BigDecimal("4.00"),    // $4/1M output tokens
            new BigDecimal("1.00"),    // $1/1M cache writes
            new BigDecimal("0.08")     // $0.08/1M cache reads
        ));

        // ==================== Claude 3 Series ====================

        // Claude 3 Opus (Most powerful Claude 3 - deprecated)
        pricing.put("claude-3-opus-20240229", new ModelPricing(
            "claude-3-opus-20240229",
            new BigDecimal("15.00"),   // $15/1M input tokens
            new BigDecimal("75.00"),   // $75/1M output tokens
            new BigDecimal("18.75"),   // $18.75/1M cache writes
            new BigDecimal("1.50")     // $1.50/1M cache reads
        ));

        // Generic model name
        pricing.put("claude-3-opus", new ModelPricing(
            "claude-3-opus",
            new BigDecimal("15.00"),
            new BigDecimal("75.00"),
            new BigDecimal("18.75"),
            new BigDecimal("1.50")
        ));

        // Claude 3 Sonnet (Balanced performance)
        pricing.put("claude-3-sonnet-20240229", new ModelPricing(
            "claude-3-sonnet-20240229",
            new BigDecimal("3.00"),
            new BigDecimal("15.00"),
            new BigDecimal("3.75"),
            new BigDecimal("0.30")
        ));

        // Claude 3 Haiku (Fastest and most compact)
        pricing.put("claude-3-haiku-20240307", new ModelPricing(
            "claude-3-haiku-20240307",
            new BigDecimal("0.25"),    // $0.25/1M input tokens
            new BigDecimal("1.25"),    // $1.25/1M output tokens
            new BigDecimal("0.30"),    // $0.30/1M cache writes
            new BigDecimal("0.03")     // $0.03/1M cache reads
        ));

        // Generic model name
        pricing.put("claude-3-haiku", new ModelPricing(
            "claude-3-haiku",
            new BigDecimal("0.25"),
            new BigDecimal("1.25"),
            new BigDecimal("0.30"),
            new BigDecimal("0.03")
        ));

        // ==================== Legacy Models (NOT in official docs, kept for backward compatibility) ====================

        // WARNING: Claude 2.x and Instant models are no longer listed in official Anthropic pricing
        // Keep for backward compatibility only - consider migrating to Claude 3+ models

        // Claude 2.1 (Legacy - NOT officially supported)
        pricing.put("claude-2.1", new ModelPricing(
            "claude-2.1",
            new BigDecimal("8.00"),    // Historical pricing
            new BigDecimal("24.00"),
            new BigDecimal("0.00"),    // No cache pricing
            new BigDecimal("0.00")
        ));

        // Claude 2.0 (Legacy - NOT officially supported)
        pricing.put("claude-2.0", new ModelPricing(
            "claude-2.0",
            new BigDecimal("8.00"),
            new BigDecimal("24.00"),
            new BigDecimal("0.00"),
            new BigDecimal("0.00")
        ));

        // Claude Instant 1.2 (Deprecated - NOT officially supported)
        pricing.put("claude-instant-1.2", new ModelPricing(
            "claude-instant-1.2",
            new BigDecimal("0.80"),
            new BigDecimal("2.40"),
            new BigDecimal("0.00"),
            new BigDecimal("0.00")
        ));

        log.info("Loaded pricing for {} models (version={})", pricing.size(), PRICING_VERSION);
        log.info("Supported model families: Claude 4.6 (latest), Claude 4.5, Claude 4, Claude 3.7, Claude 3.5, Claude 3");

        return pricing;
    }
}
