package basicbankingapp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Slows password guessing: after {@code maxFailures} failed attempts from one client within
 * {@code window}, that client is refused until its oldest failure falls out of the window.
 * Only failures count, and a success does NOT reset the count (otherwise an attacker could
 * log in to their own account between guesses).
 */
public final class LoginThrottle {

    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new HashMap<>();

    public LoginThrottle(int maxFailures, Duration window) {
        this(maxFailures, window, Clock.systemUTC());
    }

    LoginThrottle(int maxFailures, Duration window, Clock clock) {
        if (maxFailures < 0 || window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Invalid throttle settings");
        }
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /** A throttle that never blocks anyone. */
    public static LoginThrottle disabled() {
        return new LoginThrottle(0, Duration.ofMinutes(1));
    }

    /** How long the client must wait, or empty if it may try. */
    public synchronized Optional<Duration> retryAfter(String client) {
        if (maxFailures == 0) {
            return Optional.empty();
        }
        Deque<Instant> recent = prune(client);
        if (recent == null || recent.size() < maxFailures) {
            return Optional.empty();
        }
        Duration wait = Duration.between(clock.instant(), recent.peekFirst().plus(window));
        return Optional.of(wait.isNegative() ? Duration.ofSeconds(1) : wait);
    }

    public synchronized void recordFailure(String client) {
        if (maxFailures == 0) {
            return;
        }
        if (failures.size() > MAX_TRACKED_CLIENTS) {
            failures.keySet().removeIf(key -> prune(key) == null);
        }
        failures.computeIfAbsent(client, key -> new ArrayDeque<>()).addLast(clock.instant());
    }

    /** Drops failures that left the window. Returns null (and forgets the client) if none remain. */
    private Deque<Instant> prune(String client) {
        Deque<Instant> recent = failures.get(client);
        if (recent == null) {
            return null;
        }
        Instant cutoff = clock.instant().minus(window);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.removeFirst();
        }
        if (recent.isEmpty()) {
            failures.remove(client);
            return null;
        }
        return recent;
    }
}
