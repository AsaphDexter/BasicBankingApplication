package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    // A low iteration count keeps the tests fast; the real app uses the much higher default.
    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test
    void correctPasswordVerifies() {
        String stored = hasher.hash("Secret123".toCharArray());
        assertTrue(hasher.verify("Secret123".toCharArray(), stored));
    }

    @Test
    void wrongPasswordIsRejected() {
        String stored = hasher.hash("Secret123".toCharArray());
        assertFalse(hasher.verify("secret123".toCharArray(), stored));
        assertFalse(hasher.verify("".toCharArray(), stored));
    }

    @Test
    void samePasswordHashesDifferentlyBecauseOfSalt() {
        String first = hasher.hash("Secret123".toCharArray());
        String second = hasher.hash("Secret123".toCharArray());
        assertNotEquals(first, second);
        assertTrue(hasher.verify("Secret123".toCharArray(), first));
        assertTrue(hasher.verify("Secret123".toCharArray(), second));
    }

    @Test
    void storedValueDoesNotContainThePassword() {
        String stored = hasher.hash("Secret123".toCharArray());
        assertFalse(stored.contains("Secret123"));
        assertTrue(stored.startsWith("pbkdf2_sha256$1000$"));
    }

    @Test
    void hashStoresItsIterationCountSoOldHashesStillVerify() {
        String stored = new PasswordHasher(2_000).hash("Secret123".toCharArray());
        assertTrue(new PasswordHasher(5_000).verify("Secret123".toCharArray(), stored));
    }

    @Test
    void malformedOrMissingStoredValuesVerifyAsFalseInsteadOfThrowing() {
        char[] password = "Secret123".toCharArray();
        assertFalse(hasher.verify(password, null));
        assertFalse(hasher.verify(password, ""));
        assertFalse(hasher.verify(password, "not-a-hash"));
        assertFalse(hasher.verify(password, "pbkdf2_sha256$abc$AAAA$BBBB"));
        assertFalse(hasher.verify(null, hasher.hash(password)));
    }

    @Test
    void iterationCountMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(0));
    }
}
