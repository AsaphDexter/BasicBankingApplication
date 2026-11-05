package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionStoreTest {

    private TestClock clock;
    private SessionStore store;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        store = new SessionStore(Duration.ofMinutes(30), clock);
    }

    @Test
    void tokenMapsBackToItsUser() {
        String token = store.create("alice");
        assertEquals("alice", store.usernameFor(token).orElseThrow());
    }

    @Test
    void tokensAreLongRandomAndUnique() {
        String first = store.create("alice");
        String second = store.create("alice");
        assertNotEquals(first, second);
        assertTrue(first.length() >= 43); // 256 bits, Base64 without padding
        assertFalse(first.contains("alice"));
    }

    @Test
    void unknownOrNullTokensAreRejected() {
        assertTrue(store.usernameFor("not-a-token").isEmpty());
        assertTrue(store.usernameFor("").isEmpty());
        assertTrue(store.usernameFor(null).isEmpty());
    }

    @Test
    void tokenStopsWorkingWhenItExpires() {
        String token = store.create("alice");
        clock.advance(Duration.ofMinutes(29));
        assertTrue(store.usernameFor(token).isPresent());
        clock.advance(Duration.ofMinutes(1)); // exactly at the lifetime: expired
        assertTrue(store.usernameFor(token).isEmpty());
    }

    @Test
    void revokedTokenStopsWorkingImmediately() {
        String token = store.create("alice");
        store.revoke(token);
        assertTrue(store.usernameFor(token).isEmpty());
        store.revoke(token); // revoking twice is harmless
        store.revoke(null);
    }

    @Test
    void revokingOneTokenLeavesTheOthersAlone() {
        String first = store.create("alice");
        String second = store.create("alice");
        store.revoke(first);
        assertTrue(store.usernameFor(second).isPresent());
    }

    @Test
    void expiredTokensAreCleanedOutOfMemory() {
        store.create("alice");
        store.create("bob");
        assertEquals(2, store.activeCount());
        clock.advance(Duration.ofMinutes(31));
        assertEquals(0, store.activeCount());
    }

    @Test
    void lifetimeMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new SessionStore(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new SessionStore(Duration.ofMinutes(-1)));
    }

    @Test
    void revokeAllExceptKeepsOnlyTheNamedSession() {
        String keep = store.create("alice");
        String other = store.create("alice");
        String bobs = store.create("bob");
        store.revokeAllExcept("alice", keep);
        assertTrue(store.usernameFor(keep).isPresent());
        assertTrue(store.usernameFor(other).isEmpty());
        assertTrue(store.usernameFor(bobs).isPresent()); // other users are untouched
    }
}
