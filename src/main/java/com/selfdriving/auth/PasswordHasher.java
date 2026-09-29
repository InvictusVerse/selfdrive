package com.selfdriving.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Password hashing with PBKDF2-HMAC-SHA256 (built into Java): a random 16-byte salt per
 * password and 600 000 iterations (the OWASP recommendation), so a stolen database cannot be
 * turned back into passwords quickly. Stored as
 * {@code pbkdf2-sha256$iterations$salt$hash} (Base64), so the cost can be raised later
 * without breaking existing accounts. Checking compares in constant time.
 */
public final class PasswordHasher {

    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    /** Fewer iterations are only for tests, where hashing speed matters more than strength. */
    public PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    public String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder();
        return PREFIX + "$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public boolean verify(char[] password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(PREFIX)) {
            return false;
        }
        try {
            int n = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, n);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Password rules: at least 8 characters with a letter and a digit, not the username.
     *
     * @return a problem to show, or null if the password is acceptable
     */
    public static String problem(String username, char[] password) {
        if (password.length < 8) {
            return "Use at least 8 characters";
        }
        boolean letter = false;
        boolean digit = false;
        for (char c : password) {
            letter |= Character.isLetter(c);
            digit |= Character.isDigit(c);
        }
        if (!letter || !digit) {
            return "Use letters and at least one digit";
        }
        if (username != null && new String(password).equalsIgnoreCase(username)) {
            return "The password cannot be the username";
        }
        return null;
    }

    /** Clears a password from memory once it is no longer needed. */
    public static void wipe(char[] password) {
        Arrays.fill(password, '\0');
    }
}
