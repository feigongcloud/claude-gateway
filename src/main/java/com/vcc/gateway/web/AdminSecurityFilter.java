package com.vcc.gateway.web;

import com.vcc.gateway.config.GwProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Security filter for Admin API endpoints.
 * Validates X-Admin-Api-Key header against configured admin keys.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminSecurityFilter implements WebFilter {
    private static final Logger log = LoggerFactory.getLogger(AdminSecurityFilter.class);

    private static final String ADMIN_PATH_PREFIX = "/admin";
    private static final String ADMIN_ACTOR_ATTR = "adminActor";

    private final String apiKeyHeader;
    private final List<String> adminApiKeys;
    private final boolean isConfigured;

    public AdminSecurityFilter(GwProperties properties) {
        GwProperties.AdminConfig adminConfig = properties.getAdmin();
        if (adminConfig != null) {
            this.apiKeyHeader = adminConfig.getApiKeyHeader() != null
                    ? adminConfig.getApiKeyHeader()
                    : "X-Admin-Api-Key";
            this.adminApiKeys = adminConfig.getAdminApiKeys() != null
                    ? List.copyOf(adminConfig.getAdminApiKeys())
                    : List.of();
            this.isConfigured = !this.adminApiKeys.isEmpty();
        } else {
            this.apiKeyHeader = "X-Admin-Api-Key";
            this.adminApiKeys = List.of();
            this.isConfigured = false;
        }

        if (isConfigured) {
            log.info("AdminSecurityFilter configured with {} admin keys", adminApiKeys.size());
        } else {
            log.warn("AdminSecurityFilter: No admin API keys configured - admin endpoints will be inaccessible");
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Only filter /admin/* paths
        if (!path.startsWith(ADMIN_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        // Check if admin API is configured
        if (!isConfigured) {
            return unauthorized(exchange, "Admin API not configured");
        }

        // Get API key from header
        String providedKey = exchange.getRequest().getHeaders().getFirst(apiKeyHeader);
        if (providedKey == null || providedKey.isBlank()) {
            return unauthorized(exchange, "Missing " + apiKeyHeader + " header");
        }

        // Validate API key
        if (!adminApiKeys.contains(providedKey)) {
            log.warn("Invalid admin API key from {}",
                    exchange.getRequest().getRemoteAddress());
            return unauthorized(exchange, "Invalid admin API key");
        }

        // Set actor attribute for audit logging
        // Use key prefix as actor identity (first 8 chars)
        String actor = "admin:" + maskKey(providedKey);
        exchange.getAttributes().put(ADMIN_ACTOR_ATTR, actor);

        log.debug("Admin request authenticated: {} {}", actor, path);
        return chain.filter(exchange);
    }

    /**
     * Return 401 Unauthorized response.
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format("{\"error\":\"Unauthorized\",\"message\":\"%s\"}", message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(bytes))
        );
    }

    /**
     * Mask API key for logging (show first 8 chars).
     */
    private String maskKey(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 8) + "...";
    }
}
