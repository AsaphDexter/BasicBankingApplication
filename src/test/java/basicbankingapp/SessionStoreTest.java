package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionStoreTest {

    /** A clock the test can move forward, so expiry is checked without waiting. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MutableClock clock;
    private SessionStore store;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
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
}
