package com.vcc.gateway.util;

import com.vcc.gateway.crypto.AesGcmCryptoService;
import com.vcc.gateway.entity.UpstreamKeySecretEntity;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.Base64;

/**
 * 命令行工具：解密upstream_key_secret表中的API密钥
 *
 * 使用方法：
 * java -cp target/classes:$(mvn dependency:build-classpath -Dmdep.outputFile=/dev/stdout -q) \
 *      com.vcc.gateway.util.DecryptUpstreamKeyCLI \
 *      jdbc:mysql://localhost:3306/claude \
 *      username \
 *      password \
 *      /path/to/master.key
 */
public class DecryptUpstreamKeyCLI {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: java DecryptUpstreamKeyCLI <jdbc-url> <username> <password> <master-key-path>");
            System.err.println("Example: java DecryptUpstreamKeyCLI " +
                    "jdbc:mysql://localhost:3306/claude gateway gateway123 ./config/master.key");
            System.exit(1);
        }

        String jdbcUrl = args[0];
        String username = args[1];
        String password = args[2];
        String masterKeyPath = args[3];

        try {
            // 加载master key
            SecretKey masterKey = loadMasterKey(masterKeyPath);
            System.out.println("✓ Loaded master key from: " + masterKeyPath);
            System.out.println();

            // 连接数据库
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                System.out.println("✓ Connected to database: " + jdbcUrl);
                System.out.println();

                // 查询并解密
                String sql = "SELECT upstream_key_id, provider, status, key_version, " +
                           "iv, ciphertext, tag, aad, created_at " +
                           "FROM upstream_key_secret " +
                           "ORDER BY created_at DESC";

                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(sql)) {

                    System.out.println("=".repeat(100));
                    System.out.println("UPSTREAM API KEYS (DECRYPTED)");
                    System.out.println("=".repeat(100));

                    int count = 0;
                    while (rs.next()) {
                        count++;
                        String keyId = rs.getString("upstream_key_id");
                        String provider = rs.getString("provider");
                        String status = rs.getString("status");
                        int keyVersion = rs.getInt("key_version");
                        byte[] iv = rs.getBytes("iv");
                        byte[] ciphertext = rs.getBytes("ciphertext");
                        byte[] tag = rs.getBytes("tag");
                        String aad = rs.getString("aad");
                        Timestamp createdAt = rs.getTimestamp("created_at");

                        // 解密
                        String decryptedKey = decrypt(masterKey, iv, ciphertext, tag, aad);

                        // 打印结果
                        System.out.println("\n[" + count + "] Key ID: " + keyId);
                        System.out.println("    Provider:   " + provider);
                        System.out.println("    Status:     " + status);
                        System.out.println("    Version:    " + keyVersion);
                        System.out.println("    Created:    " + createdAt);
                        System.out.println("    Decrypted:  " + maskKey(decryptedKey));
                        System.out.println("    Full Key:   " + decryptedKey);
                    }

                    System.out.println("\n" + "=".repeat(100));
                    System.out.println("Total: " + count + " key(s)");
                    System.out.println("=".repeat(100));
                }
            }

        } catch (Exception e) {
            System.err.println("✗ Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static SecretKey loadMasterKey(String path) throws IOException {
        Path keyPath = Path.of(path);
        if (!Files.exists(keyPath)) {
            throw new IOException("Master key file not found: " + path);
        }

        byte[] keyBytes = Files.readAllBytes(keyPath);

        // 尝试Base64解码
        if (keyBytes.length > 32) {
            String keyStr = new String(keyBytes, StandardCharsets.UTF_8).trim();
            try {
                keyBytes = Base64.getDecoder().decode(keyStr);
            } catch (IllegalArgumentException e) {
                // 不是base64，使用原始字节
            }
        }

        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "Master key must be 32 bytes (256 bits), got " + keyBytes.length);
        }

        return new SecretKeySpec(keyBytes, "AES");
    }

    private static String decrypt(SecretKey key, byte[] iv, byte[] ciphertext,
                                  byte[] tag, String aad) throws Exception {
        // 重组密文+tag
        byte[] ciphertextWithTag = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, ciphertextWithTag, 0, ciphertext.length);
        System.arraycopy(tag, 0, ciphertextWithTag, ciphertext.length, tag.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, spec);

        if (aad != null && !aad.isEmpty()) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }

        byte[] plaintext = cipher.doFinal(ciphertextWithTag);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static String maskKey(String key) {
        if (key == null || key.length() < 12) {
            return "***";
        }
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }
}
