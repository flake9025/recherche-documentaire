package fr.vvlabs.recherche.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utilitaire de chiffrement/dÃ©chiffrement AES-GCM
 * - ImplÃ©mentation cÅ“ur en byte[]
 * - Adaptateurs String <-> Base64 explicites
 */
@Slf4j
public final class CipherUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12;   // bytes
    private static final int AES_KEY_SIZE = 256;   // bits

    private CipherUtil() {
        // utilitaire
    }

    // ============================================================
    // =============== API STRING (Base64) ========================
    // ============================================================

    public static String encrypt(String plaintext, byte[] secretKey) throws Exception {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }

        byte[] encrypted = encrypt(
                plaintext.getBytes(StandardCharsets.UTF_8),
                secretKey
        );

        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static String decrypt(String encryptedBase64, byte[] secretKey) throws Exception {
        if (encryptedBase64 == null || encryptedBase64.isEmpty()) {
            return encryptedBase64;
        }

        byte[] encrypted = Base64.getDecoder().decode(encryptedBase64);
        byte[] decrypted = decrypt(encrypted, secretKey);

        return new String(decrypted, StandardCharsets.UTF_8);
    }

    // ============================================================
    // =============== API BYTE[] (cÅ“ur crypto) ===================
    // ============================================================

    public static byte[] encrypt(byte[] data, byte[] secretKey) throws Exception {
        if (data == null || data.length == 0) {
            return data;
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(secretKey, "AES"),
                new GCMParameterSpec(GCM_TAG_LENGTH, iv)
        );

        byte[] encrypted = cipher.doFinal(data);

        ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
        buffer.put(iv);
        buffer.put(encrypted);

        return buffer.array();
    }

    public static byte[] decrypt(byte[] encryptedData, byte[] secretKey) throws Exception {
        if (encryptedData == null || encryptedData.length == 0) {
            return encryptedData;
        }

        ByteBuffer buffer = ByteBuffer.wrap(encryptedData);

        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);

        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(secretKey, "AES"),
                new GCMParameterSpec(GCM_TAG_LENGTH, iv)
        );

        return cipher.doFinal(encrypted);
    }

    // ============================================================
    // ===================== UTILITAIRES ==========================
    // ============================================================

    public static byte[] generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE);
        SecretKey secretKey = keyGen.generateKey();
        return secretKey.getEncoded();
    }

    public static String keyToBase64(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    public static byte[] keyFromBase64(String base64Key) {
        return Base64.getDecoder().decode(base64Key);
    }
}

