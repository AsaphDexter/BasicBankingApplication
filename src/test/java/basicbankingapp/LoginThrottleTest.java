package basicbankingapp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private TestClock clock;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
        throttle = new LoginThrottle(3, Duration.ofMinutes(5), clock);
    }

    @Test
    void clientsStartWithAFullAllowance() {
        assertTrue(throttle.retryAfter("10.0.0.1").isEmpty());
    }

    @Test
    void theClientIsBlockedOnceItReachesTheLimit() {
        throttle.recordFailure("10.0.0.1");
        throttle.recordFailure("10.0.0.1");
        assertTrue(throttle.retryAfter("10.0.0.1").isEmpty());
        throttle.recordFailure("10.0.0.1");
        assertTrue(throttle.retryAfter("10.0.0.1").isPresent());
    }

    @Test
    void theWaitCountsDownFromTheOldestFailure() {
        throttle.recordFailure("10.0.0.1");
        clock.advance(Duration.ofMinutes(1));
        throttle.recordFailure("10.0.0.1");
        throttle.recordFailure("10.0.0.1");
        assertEquals(Duration.ofMinutes(4), throttle.retryAfter("10.0.0.1").orElseThrow());
        clock.advance(Duration.ofMinutes(2));
        assertEquals(Duration.ofMinutes(2), throttle.retryAfter("10.0.0.1").orElseThrow());
    }

    @Test
    void theBlockEndsWhenTheOldestFailureLeavesTheWindow() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("10.0.0.1");
        }
        clock.advance(Duration.ofMinutes(5));
        assertTrue(throttle.retryAfter("10.0.0.1").isEmpty());
    }

    @Test
    void oneBlockedClientDoesNotAffectAnother() {
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("10.0.0.1");
        }
        assertTrue(throttle.retryAfter("10.0.0.1").isPresent());
        assertTrue(throttle.retryAfter("10.0.0.2").isEmpty());
    }

    @Test
    void aDisabledThrottleNeverBlocks() {
        LoginThrottle off = LoginThrottle.disabled();
        for (int i = 0; i < 1000; i++) {
            off.recordFailure("10.0.0.1");
        }
        assertTrue(off.retryAfter("10.0.0.1").isEmpty());
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new LoginThrottle(-1, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new LoginThrottle(3, Duration.ZERO));
    }
}
