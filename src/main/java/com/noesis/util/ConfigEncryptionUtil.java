package com.noesis.util;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Slf4j
public class ConfigEncryptionUtil {

    private static final ThreadLocal<byte[]> machineKey = ThreadLocal.withInitial(ConfigEncryptionUtil::deriveMachineKey);

    public static String decryptApiKey(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) return "";
        try {
            byte[] key = machineKey.get();
            byte[] encrypted = Base64.getDecoder().decode(cipherText);
            byte[] decrypted = xorCrypt(encrypted, key);
            String result = new String(decrypted, StandardCharsets.UTF_8);
            // If decryption produced non-printable chars, the machine key doesn't match
            // (different machine, or Java vs Python MAC encoding difference)
            if (isPrintableAscii(result)) {
                return result;
            }
            log.debug("Decrypted API key contains non-printable characters — machine key mismatch, falling back to empty");
            return "";
        } catch (Exception e) {
            log.debug("Failed to decrypt API key: {}", e.getMessage());
            return "";
        }
    }

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    private static byte[] xorCrypt(byte[] data, byte[] key) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return result;
    }

    private static byte[] deriveMachineKey() {
        try {
            var ni = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
            if (ni == null) return fallbackKey();
            byte[] mac = ni.getHardwareAddress();
            if (mac == null) return fallbackKey();
            long macInt = 0;
            for (byte b : mac) {
                macInt = (macInt << 8) | (b & 0xFF);
            }
            String macStr = Long.toString(macInt);
            return MessageDigest.getInstance("SHA-256").digest(macStr.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Could not derive machine key from MAC, using fallback");
            return fallbackKey();
        }
    }

    private static byte[] fallbackKey() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(
                    "noesis-fallback-key".getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return new byte[32];
        }
    }
}
