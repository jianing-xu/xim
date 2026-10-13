package cn.xjn.xim.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Versioned PBKDF2-HMAC-SHA256, 600000 iterations, 128-bit random salt, 256-bit key. */
public final class PasswordHash {
    private static final int ITERATIONS = 600_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY = hash("unknown-account-padding");
    private PasswordHash() {}
    public static String hash(String password) {
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    public static boolean verify(String password, String encoded) {
        if (!AccountPolicy.validLoginPassword(password)) return false;
        boolean absent = encoded == null;
        if (absent) encoded = DUMMY;
        try {
            String[] parts = encoded.split("\\$", -1);
            if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) return false;
            int rounds = Integer.parseInt(parts[1]);
            if (rounds < ITERATIONS || rounds > 2_000_000) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != 16 || expected.length != 32) return false;
            boolean matches = MessageDigest.isEqual(expected, derive(password, salt, rounds));
            return !absent && matches;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    private static byte[] derive(String password, byte[] salt, int iterations) {
        char[] chars = password.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, 256);
        Arrays.fill(chars, '\0');
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("Password hashing unavailable"); }
        finally { spec.clearPassword(); }
    }
}
