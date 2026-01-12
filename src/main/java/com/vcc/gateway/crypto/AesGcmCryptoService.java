package com.vcc.gateway.crypto;

import com.vcc.gateway.config.GwProperties;
import com.vcc.gateway.entity.UpstreamKeySecretEntity;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AES-256-GCM encryption service for upstream API keys.
 * Master key is loaded from file (e.g., /etc/gw/master.key).
 * Supports key versioning for rotation.
 */
@Service
public class AesGcmCryptoService {
    private static final Logger log = LoggerFactory.getLogger(AesGcmCryptoService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;  // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // bits

    private final GwProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<Integer, SecretKey> masterKeys = new ConcurrentHashMap<>();
    private volatile int currentKeyVersion;

    public AesGcmCryptoService(GwProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (properties.getCrypto() == null) {
            log.warn("Crypto config not set, AES-GCM encryption disabled");
            return;
        }

        String masterKeyPath = properties.getCrypto().getMasterKeyPath();
        this.currentKeyVersion = properties.getCrypto().getCurrentKeyVersion();

        if (masterKeyPath == null || masterKeyPath.isBlank()) {
            log.warn("Master key path not configured, encryption disabled");
            return;
        }

        try {
            loadMasterKey(masterKeyPath, currentKeyVersion);
            log.info("Loaded master key v{} from {}", currentKeyVersion, masterKeyPath);
        } catch (IOException e) {
            log.warn("Failed to load master key from {}: {}", masterKeyPath, e.getMessage());
        }
    }

    /**
     * Load master key from file. Supports both raw bytes and base64 encoded.
     */
    private void loadMasterKey(String path, int version) throws IOException {
        Path keyPath = Path.of(path);
        if (!Files.exists(keyPath)) {
            // Try versioned path: /etc/gw/master.key.v1
            keyPath = Path.of(path + ".v" + version);
        }

        if (!Files.exists(keyPath)) {
            throw new IOException("Master key file not found: " + path);
        }

        byte[] keyBytes = Files.readAllBytes(keyPath);

        // Try to decode as base64 if it looks like base64
        if (keyBytes.length > 32) {
            String keyStr = new String(keyBytes, StandardCharsets.UTF_8).trim();
            try {
                keyBytes = Base64.getDecoder().decode(keyStr);
            } catch (IllegalArgumentException e) {
                // Not base64, use raw bytes
            }
        }

        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "Master key must be 32 bytes (256 bits), got " + keyBytes.length);
        }

        SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
        masterKeys.put(version, secretKey);
    }

    /**
     * Check if encryption is available (master key loaded).
     */
    public boolean isEnabled() {
        return !masterKeys.isEmpty();
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     *
     * @param plaintext The data to encrypt
     * @param aad       Additional authenticated data (optional, can be null)
     * @return EncryptedData containing iv, ciphertext, tag, and key version
     */
    public EncryptedData encrypt(String plaintext, String aad) throws Exception {
        if (!isEnabled()) {
            throw new IllegalStateException("Encryption not available - master key not loaded");
        }

        SecretKey key = masterKeys.get(currentKeyVersion);
        if (key == null) {
            throw new IllegalStateException("Master key v" + currentKeyVersion + " not loaded");
        }

        // Generate random IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);

        if (aad != null && !aad.isEmpty()) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }

        byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // GCM appends 16-byte tag to ciphertext, separate them
        int ciphertextLength = ciphertextWithTag.length - 16;
        byte[] ciphertext = new byte[ciphertextLength];
        byte[] tag = new byte[16];
        System.arraycopy(ciphertextWithTag, 0, ciphertext, 0, ciphertextLength);
        System.arraycopy(ciphertextWithTag, ciphertextLength, tag, 0, 16);

        return new EncryptedData(iv, ciphertext, tag, aad, currentKeyVersion);
    }

    /**
     * Decrypt an upstream key secret entity.
     *
     * @param entity The encrypted upstream key entity
     * @return Decrypted plaintext
     */
    public String decrypt(UpstreamKeySecretEntity entity) throws Exception {
        if (!isEnabled()) {
            throw new IllegalStateException("Encryption not available - master key not loaded");
        }

        SecretKey key = masterKeys.get(entity.getKeyVersion());
        if (key == null) {
            throw new IllegalStateException(
                    "Master key v" + entity.getKeyVersion() + " not loaded for decryption");
        }

        // Reconstruct ciphertext with tag appended
        byte[] ciphertextWithTag = new byte[entity.getCiphertext().length + entity.getTag().length];
        System.arraycopy(entity.getCiphertext(), 0, ciphertextWithTag, 0, entity.getCiphertext().length);
        System.arraycopy(entity.getTag(), 0, ciphertextWithTag, entity.getCiphertext().length, entity.getTag().length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, entity.getIv());
        cipher.init(Cipher.DECRYPT_MODE, key, spec);

        if (entity.getAad() != null && !entity.getAad().isEmpty()) {
            cipher.updateAAD(entity.getAad().getBytes(StandardCharsets.UTF_8));
        }

        byte[] plaintext = cipher.doFinal(ciphertextWithTag);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /**
     * Get current key version for new encryptions.
     */
    public int getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    /**
     * Encrypted data record containing all components needed for decryption.
     */
    public record EncryptedData(
            byte[] iv,
            byte[] ciphertext,
            byte[] tag,
            String aad,
            int keyVersion
    ) {}
}
