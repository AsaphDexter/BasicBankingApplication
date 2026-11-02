package basicbankingapp;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Login tokens for the API. A token is 256 bits from {@link SecureRandom}, expires after a fixed
 * time and can be revoked (logout). Held in memory only, so restarting the server signs everyone out.
 */
public final class SessionStore {

    private static final int TOKEN_BYTES = 32;

    private record Session(String username, Instant expiresAt) { }

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Duration lifetime;
    private final Clock clock;

    public SessionStore(Duration lifetime) {
        this(lifetime, Clock.systemUTC());
    }

    /** The clock can be replaced in tests to check expiry without waiting. */
    SessionStore(Duration lifetime, Clock clock) {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("Session lifetime must be positive");
        }
        this.lifetime = lifetime;
        this.clock = clock;
    }

    public Duration getLifetime() {
        return lifetime;
    }

    public String create(String username) {
        purgeExpired();
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(username, clock.instant().plus(lifetime)));
        return token;
    }

    /** The username the token belongs to, or empty if the token is unknown, revoked or expired. */
    public Optional<String> usernameFor(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(session.expiresAt())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    public void revoke(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    int activeCount() {
        purgeExpired();
        return sessions.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !now.isBefore(s.expiresAt()));
    }
}
