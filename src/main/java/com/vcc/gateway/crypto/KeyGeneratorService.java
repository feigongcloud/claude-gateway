package com.vcc.gateway.crypto;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Service for generating and hashing customer API keys.
 * Keys are generated with a prefix (aic_) and stored as SHA-256 hashes.
 */
@Service
public class KeyGeneratorService {

    private static final String KEY_PREFIX = "aic_";
    private static final int KEY_RANDOM_BYTES = 32;  // 256 bits of randomness
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generate a new API key with random entropy.
     * Format: aic_ + 32 bytes base64url encoded (43 chars)
     * Total length: ~47 characters
     *
     * @return GeneratedKey containing plaintext, prefix, and hash
     */
    public GeneratedKey generateApiKey() {
        // Generate random bytes
        byte[] randomBytes = new byte[KEY_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);

        // Encode as base64url (URL-safe, no padding)
        String randomPart = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(randomBytes);

        // Full key: aic_ + base64url encoded random bytes
        String fullKey = KEY_PREFIX + randomPart;

        // Prefix for display (first 12 chars including aic_)
        String prefix = fullKey.substring(0, Math.min(12, fullKey.length()));

        // SHA-256 hash for storage
        String hash = sha256Hex(fullKey);

        return new GeneratedKey(fullKey, prefix, hash);
    }

    /**
     * Hash an API key using SHA-256.
     *
     * @param apiKey The plaintext API key
     * @return 64-character hex string (256 bits)
     */
    public String hashApiKey(String apiKey) {
        return sha256Hex(apiKey);
    }

    /**
     * Validate API key format.
     *
     * @param apiKey The API key to validate
     * @return true if valid format
     */
    public boolean isValidKeyFormat(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        // Must start with prefix and have reasonable length
        return apiKey.startsWith(KEY_PREFIX) && apiKey.length() >= 20;
    }

    /**
     * Extract display prefix from a full API key.
     *
     * @param apiKey The full API key
     * @return First 12 characters for display
     */
    public String extractPrefix(String apiKey) {
        if (apiKey == null || apiKey.length() < 8) {
            return "****";
        }
        return apiKey.substring(0, Math.min(12, apiKey.length()));
    }

    /**
     * Compute SHA-256 hash and return as hex string.
     */
    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Result of key generation containing plaintext (shown once), prefix, and hash.
     */
    public record GeneratedKey(
            String plaintext,  // Full key - only returned once at creation
            String prefix,     // Display prefix (e.g., aic_dGhpc2...)
            String hash        // SHA-256 hash for storage (64 hex chars)
    ) {}
}
