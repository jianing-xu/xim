package cn.xjn.xim.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque random credentials; only hashes are persisted. Never log credentials or tickets. */
public final class SessionTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    public static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) return null;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static long ttlMillis() {
        long seconds = Long.getLong("xim.session.ttlSeconds", 604800L);
        if (seconds < 1 || seconds > 2592000) throw new IllegalArgumentException("Invalid session TTL");
        return seconds * 1000;
    }
    private SessionTokens() {}
}
