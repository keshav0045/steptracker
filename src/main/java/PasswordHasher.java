import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** PBKDF2 password hashing with an independent random salt per account. */
public final class PasswordHasher {
    public static final int ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private final SecureRandom random = new SecureRandom();

    public Credentials hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return new Credentials(salt, derive(password, salt, ITERATIONS), ITERATIONS);
    }

    public boolean verify(char[] password, byte[] salt, byte[] expected, int iterations) {
        if (salt == null || expected == null || iterations < 100_000 || iterations > 2_000_000) return false;
        return MessageDigest.isEqual(derive(password, salt, iterations), expected);
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    public record Credentials(byte[] salt, byte[] hash, int iterations) {}
}
